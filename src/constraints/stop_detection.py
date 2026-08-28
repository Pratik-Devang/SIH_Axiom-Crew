"""
stop_detector.py
================
Detect stationary vehicle state from speed measurements.

Role 4 responsibility
---------------------
This module decides **when the vehicle is stopped**.  It does NOT implement
the ZUPT EKF measurement update (that is Role 3's job).  It emits
:class:`ConstraintEvent` objects of type ``ZUPT`` that Role 3 consumes.

Output fed to
-------------
- Role 3 (INS/ESKF): ConstraintEvent(type="ZUPT", value=0.0, confidence)
  triggers the ZUPT measurement update in the ESKF.
- Role 6 (Integration/Eval): every stop event is written to the JSONL log.

Algorithm
---------
Maintains a sliding window of speed samples.  When both mean speed and
variance are below their respective thresholds for at least
``min_stationary_samples`` consecutive samples, a ZUPT event is emitted.
"""

from __future__ import annotations

import json
import logging
import statistics
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Deque, List, Optional

import yaml

logger = logging.getLogger(__name__)


def _load_config(config_path: str | Path = "configs/role4.yaml") -> dict:
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


# ---------------------------------------------------------------------------
# Shared dataclasses (cross-role contract)
# ---------------------------------------------------------------------------

@dataclass
class ConstraintEvent:
    """Structured constraint signal consumed by Role 3's EKF.

    This is the **canonical output format** for all vehicle-state
    constraints produced by Role 4.  Role 3 uses ``type`` to route the
    event to the correct EKF measurement-update function.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp of the event.
    type : str
        Constraint type.  Defined values:
        - ``"ZUPT"``  : Zero-velocity Update — vehicle is stationary.
        - ``"NHC"``   : Non-Holonomic Constraint violation detected.
        - ``"MAP_SNAP"`` : Map-matched position update.
    value : float
        Numeric payload.  For ZUPT: 0.0 (zero speed).
                          For NHC: lateral velocity estimate (m/s).
                          For MAP_SNAP: along-road distance residual (m).
    confidence : float
        Confidence in [0.0, 1.0].  Role 3 may use this as a measurement
        noise scaling factor.
    metadata : dict
        Optional extra fields (e.g. edge_id for MAP_SNAP, duration for ZUPT).
    """

    timestamp: float
    type: str          # "ZUPT" | "NHC" | "MAP_SNAP"
    value: float
    confidence: float
    metadata: dict = None

    def __post_init__(self) -> None:
        if self.metadata is None:
            self.metadata = {}
        self.confidence = max(0.0, min(1.0, self.confidence))


@dataclass
class StopEvent:
    """Summary of a detected stationary episode.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp at which stop was confirmed.
    is_stopped : bool
        True if vehicle is currently stopped.
    confidence : float
        Confidence in [0.0, 1.0].
    duration_s : float
        How long the vehicle has been stationary (seconds).
    mean_speed_m_s : float
        Mean speed in the detection window.
    """

    timestamp: float
    is_stopped: bool
    confidence: float
    duration_s: float
    mean_speed_m_s: float


# ---------------------------------------------------------------------------
# Stop Detector
# ---------------------------------------------------------------------------

class StopDetector:
    """Sliding-window stop detection from speed samples.

    Loads all thresholds from ``configs/role4.yaml`` under the
    ``stop_detector`` key.

    Parameters
    ----------
    config_path : str or Path
        Path to YAML config.
    event_log_path : str or Path, optional
        JSONL output path.  Defaults to config value.

    Examples
    --------
    >>> detector = StopDetector()
    >>> event = detector.update(timestamp=1000.0, speed_m_s=0.1)
    >>> event.is_stopped  # True after enough zero-speed samples
    """

    def __init__(
        self,
        config_path: str | Path = "configs/role4.yaml",
        event_log_path: Optional[str | Path] = None,
    ) -> None:
        cfg = _load_config(config_path)
        self._cfg = cfg["stop_detector"]
        self._log_cfg = cfg["logging"]

        log_path = Path(event_log_path or self._log_cfg["event_log_path"])
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self._event_log = open(log_path, "a", buffering=1)

        win = self._cfg["window_size"]
        self._speed_window: Deque[float] = deque(maxlen=win)
        self._time_window: Deque[float] = deque(maxlen=win)
        self._consecutive_stopped: int = 0
        self._stop_start_time: Optional[float] = None
        self._last_state_stopped: bool = False

        logger.debug("StopDetector initialised, window_size=%d", win)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def update(self, timestamp: float, speed_m_s: float) -> StopEvent:
        """Feed a new speed sample and get current stop state.

        Parameters
        ----------
        timestamp : float
            POSIX timestamp of the speed measurement.
        speed_m_s : float
            Speed magnitude in m/s (must be ≥ 0).

        Returns
        -------
        StopEvent
            Current assessed stop state.
        """
        speed_m_s = abs(speed_m_s)
        self._speed_window.append(speed_m_s)
        self._time_window.append(timestamp)

        speeds = list(self._speed_window)
        mean_speed = statistics.mean(speeds)

        variance = statistics.variance(speeds) if len(speeds) > 1 else 0.0

        thr_speed = self._cfg["speed_threshold_m_s"]
        thr_var = self._cfg["variance_threshold"]
        min_samples = self._cfg["min_stationary_samples"]

        is_sample_stopped = (mean_speed < thr_speed) and (variance < thr_var)

        if is_sample_stopped:
            self._consecutive_stopped += 1
            if self._stop_start_time is None:
                self._stop_start_time = timestamp
        else:
            self._consecutive_stopped = 0
            self._stop_start_time = None

        is_stopped = self._consecutive_stopped >= min_samples
        duration_s = (
            (timestamp - self._stop_start_time)
            if (is_stopped and self._stop_start_time is not None)
            else 0.0
        )

        # Confidence grows with each additional stopped sample
        conf_per = self._cfg["confidence_per_sample"]
        confidence = min(1.0, self._consecutive_stopped * conf_per) if is_stopped else 0.0

        stop_event = StopEvent(
            timestamp=timestamp,
            is_stopped=is_stopped,
            confidence=confidence,
            duration_s=duration_s,
            mean_speed_m_s=mean_speed,
        )

        # Log on state transitions and ongoing stops
        if is_stopped != self._last_state_stopped:
            event_type = "STOP_START" if is_stopped else "STOP_END"
            self._log_event(event_type, stop_event)
            logger.info("StopDetector: %s at t=%.2f (conf=%.2f)",
                        event_type, timestamp, confidence)

        self._last_state_stopped = is_stopped
        return stop_event

    def to_constraint_event(self, stop_event: StopEvent) -> Optional[ConstraintEvent]:
        """Convert a StopEvent to a ConstraintEvent for Role 3.

        Returns ``None`` if the vehicle is not stopped (no ZUPT to emit).

        Parameters
        ----------
        stop_event : StopEvent
            Output of :meth:`update`.

        Returns
        -------
        ConstraintEvent or None
            ZUPT constraint event, or None if moving.
        """
        if not stop_event.is_stopped:
            return None
        return ConstraintEvent(
            timestamp=stop_event.timestamp,
            type="ZUPT",
            value=0.0,
            confidence=stop_event.confidence,
            metadata={
                "duration_s": stop_event.duration_s,
                "mean_speed_m_s": stop_event.mean_speed_m_s,
            },
        )

    def close(self) -> None:
        """Flush and close the event log."""
        self._event_log.close()

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _log_event(self, event_type: str, stop_event: StopEvent) -> None:
        record = {
            "event_type": event_type,
            "timestamp": stop_event.timestamp,
            "is_stopped": stop_event.is_stopped,
            "confidence": round(stop_event.confidence, 4),
            "duration_s": round(stop_event.duration_s, 3),
            "mean_speed_m_s": round(stop_event.mean_speed_m_s, 4),
        }
        self._event_log.write(json.dumps(record) + "\n")
