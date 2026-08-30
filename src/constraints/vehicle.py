"""
vehicle_constraints.py
======================
Non-Holonomic Constraint (NHC) violation detection for ground vehicles.

Role 4 responsibility
---------------------
This module flags when the vehicle's lateral velocity exceeds the NHC
threshold — indicating wheel-slip, sharp turns, or sensor drift.  It does
NOT implement the EKF measurement update (Role 3's job).  It emits
:class:`ConstraintEvent` objects of type ``NHC``.

Non-Holonomic Constraint background
-------------------------------------
For a normal wheeled vehicle, lateral velocity (perpendicular to the
vehicle's heading) should be near zero (the vehicle cannot slide sideways).
A persistent non-zero lateral velocity is a strong indicator of IMU drift
or a filter error.  The ESKF uses this as a pseudo-measurement: v_lateral ≈ 0.

This module flags NHC **violations** (when lateral speed exceeds the
threshold) and NHC **satisfaction** (when it is within bounds).  Role 3
uses the ``satisfied`` signal to gate when the NHC pseudo-measurement is
trustworthy enough to inject.

Output fed to
-------------
- Role 3 (INS/ESKF): ConstraintEvent(type="NHC", value=lateral_v, confidence)
- Role 6 (Integration/Eval): JSONL event log.
"""

from __future__ import annotations

import json
import logging
import math
import statistics
from collections import deque
from dataclasses import dataclass
from pathlib import Path

import yaml

try:
    from src.constraints.stop_detection import ConstraintEvent
except ImportError:
    from navigation.stop_detector import ConstraintEvent

logger = logging.getLogger(__name__)


def _load_config(config_path: str | Path = "configs/role4.yaml") -> dict:
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


# ---------------------------------------------------------------------------
# NHC state dataclass
# ---------------------------------------------------------------------------

@dataclass
class NHCState:
    """Snapshot of NHC assessment at a single timestep.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp.
    lateral_velocity_m_s : float
        Estimated lateral velocity magnitude (m/s).  Ideally ~0 for normal
        driving.
    violation : bool
        True if lateral velocity exceeds the NHC threshold.
    confidence : float
        Confidence in the NHC estimate [0.0, 1.0].  Lower if vehicle speed
        is too low for reliable heading estimation.
    speed_m_s : float
        Forward speed magnitude used in this estimate.
    heading_rate_deg_s : float
        Approximate yaw rate (deg/s) over the window.
    """

    timestamp: float
    lateral_velocity_m_s: float
    violation: bool
    confidence: float
    speed_m_s: float
    heading_rate_deg_s: float


# ---------------------------------------------------------------------------
# Vehicle Constraint Detector
# ---------------------------------------------------------------------------

class VehicleConstraintDetector:
    """Detect Non-Holonomic Constraint violations from IMU/GNSS-derived state.

    Accepts forward speed and heading samples, maintains a sliding window,
    and estimates lateral velocity from the turning geometry:

        v_lateral ≈ v_forward × sin(Δheading)

    where ``Δheading`` is the heading change over the window.  For a normal
    vehicle this should be ~0.

    Loads all thresholds from ``configs/role4.yaml`` under
    ``vehicle_constraints``.

    Parameters
    ----------
    config_path : str or Path
        Path to YAML config.
    event_log_path : str or Path, optional
        JSONL event log path.

    Examples
    --------
    >>> detector = VehicleConstraintDetector()
    >>> state = detector.update(timestamp=1.0, speed_m_s=10.0, heading_deg=90.0)
    >>> state.violation
    False
    """

    def __init__(
        self,
        config_path: str | Path = "configs/role4.yaml",
        event_log_path: str | Path | None = None,
        enable_logging: bool = True,
    ) -> None:
        cfg = _load_config(config_path)
        self._cfg = cfg["vehicle_constraints"]
        self._log_cfg = cfg["logging"]

        self._event_log = None
        if enable_logging:
            log_path = Path(event_log_path or self._log_cfg["event_log_path"])
            log_path.parent.mkdir(parents=True, exist_ok=True)
            # The detector owns this long-lived handle and closes it in close().
            self._event_log = open(log_path, "a", buffering=1)  # noqa: SIM115

        win = self._cfg["nhc_window_size"]
        self._speed_window: deque[float] = deque(maxlen=win)
        self._heading_window: deque[float] = deque(maxlen=win)
        self._time_window: deque[float] = deque(maxlen=win)

        logger.debug("VehicleConstraintDetector initialised, window=%d", win)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def update(
        self,
        timestamp: float,
        speed_m_s: float,
        heading_deg: float,
    ) -> NHCState:
        """Feed a new (speed, heading) sample and assess NHC.

        Parameters
        ----------
        timestamp : float
            POSIX timestamp of the measurement.
        speed_m_s : float
            Forward speed magnitude in m/s (must be ≥ 0).
        heading_deg : float
            Vehicle heading in degrees, clockwise from North [0, 360).

        Returns
        -------
        NHCState
            Current NHC assessment.
        """
        speed_m_s = abs(speed_m_s)
        heading_deg = heading_deg % 360.0

        self._speed_window.append(speed_m_s)
        self._heading_window.append(heading_deg)
        self._time_window.append(timestamp)

        if len(self._speed_window) < 2:
            # Not enough samples yet
            return NHCState(
                timestamp=timestamp,
                lateral_velocity_m_s=0.0,
                violation=False,
                confidence=0.0,
                speed_m_s=speed_m_s,
                heading_rate_deg_s=0.0,
            )

        headings = list(self._heading_window)
        speeds = list(self._speed_window)
        times = list(self._time_window)

        # Heading change over the window (unwrap to avoid 0/360 discontinuity)
        h_start, h_end = headings[0], headings[-1]
        delta_heading = ((h_end - h_start + 180.0) % 360.0) - 180.0  # signed
        dt = times[-1] - times[0] if times[-1] != times[0] else 1e-6
        heading_rate = delta_heading / dt  # deg/s

        mean_speed = statistics.mean(speeds)
        min_speed = self._cfg["nhc_min_speed_m_s"]

        # NHC confidence is low at very low speeds (pivot turns, start-stop)
        if mean_speed < min_speed:
            confidence = 0.0
            lateral_v = 0.0
        else:
            speed_confidence = min(1.0, mean_speed / (min_speed * 3.0))
            confidence = speed_confidence
            # lateral velocity estimate: v × sin(Δheading)
            heading_rad = math.radians(delta_heading)
            lateral_v = abs(mean_speed * math.sin(heading_rad))

        threshold = self._cfg["nhc_lateral_threshold_m_s"]
        min_conf = self._cfg["nhc_min_confidence"]
        violation = (lateral_v > threshold) and (confidence >= min_conf)

        state = NHCState(
            timestamp=timestamp,
            lateral_velocity_m_s=lateral_v,
            violation=violation,
            confidence=confidence,
            speed_m_s=mean_speed,
            heading_rate_deg_s=heading_rate,
        )

        self._log_event(state)
        return state

    def to_constraint_event(self, state: NHCState) -> ConstraintEvent | None:
        """Convert NHCState to a ConstraintEvent for Role 3.

        Always returns an event (Role 3 uses both the violation flag and the
        lateral velocity value — satisfaction events are also useful).
        Returns ``None`` only if confidence is too low.

        Parameters
        ----------
        state : NHCState

        Returns
        -------
        ConstraintEvent or None
        """
        min_conf = self._cfg["nhc_min_confidence"]
        if state.confidence < min_conf:
            return None
        return ConstraintEvent(
            timestamp=state.timestamp,
            type="NHC",
            value=state.lateral_velocity_m_s,
            confidence=state.confidence,
            metadata={
                "violation": state.violation,
                "speed_m_s": state.speed_m_s,
                "heading_rate_deg_s": state.heading_rate_deg_s,
            },
        )

    def close(self) -> None:
        """Flush and close the event log."""
        if self._event_log is not None:
            self._event_log.close()

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _log_event(self, state: NHCState) -> None:
        record = {
            "event_type": "NHC_VIOLATION" if state.violation else "NHC_OK",
            "timestamp": state.timestamp,
            "lateral_velocity_m_s": round(state.lateral_velocity_m_s, 4),
            "violation": state.violation,
            "confidence": round(state.confidence, 4),
            "speed_m_s": round(state.speed_m_s, 4),
            "heading_rate_deg_s": round(state.heading_rate_deg_s, 3),
        }
        if self._event_log is not None:
            self._event_log.write(json.dumps(record) + "\n")
        if state.violation:
            logger.warning(
                "NHC violation: lateral_v=%.3f m/s conf=%.2f at t=%.2f",
                state.lateral_velocity_m_s, state.confidence, state.timestamp,
            )
