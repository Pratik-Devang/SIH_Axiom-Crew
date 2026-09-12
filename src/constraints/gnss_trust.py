"""
gnss_trust.py
=============
GNSS Trust Manager — score, accept, or reject incoming GNSS fixes.

Role 4 responsibility
---------------------
This module decides **whether to trust a GNSS fix**.  It does NOT perform
any EKF update (that is Role 3's job).  It emits :class:`TrustDecision`
events that Role 3 consumes to decide whether to incorporate the fix into
the filter state.

Output fed to
-------------
- Role 3 (INS/ESKF): TrustDecision.accepted controls whether Role 3 runs
  a GNSS measurement update step.
- Role 6 (Integration/Eval): every TrustDecision is written to the JSONL
  event log for drift analysis.

Data contract
-------------
Input  : raw GNSS fix fields (lat, lon, timestamp, optional hdop/accuracy/sats)
Output : TrustDecision dataclass (see below)
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path

import yaml

try:
    from src.preprocessing.coordinates import haversine_distance
except ImportError:
    from coordinate_transform import haversine_distance

# ---------------------------------------------------------------------------
# Structured logger
# ---------------------------------------------------------------------------
logger = logging.getLogger(__name__)


def _load_config(config_path: str | Path = "configs/role4.yaml") -> dict:
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


# ---------------------------------------------------------------------------
# Data contracts (shared with Role 3, Role 6)
# ---------------------------------------------------------------------------

@dataclass
class GNSSFix:
    """A single incoming GNSS measurement.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp of the fix (seconds).
    lat : float
        Latitude, decimal degrees WGS84.
    lon : float
        Longitude, decimal degrees WGS84.
    hdop : float, optional
        Horizontal Dilution of Precision. Use ``None`` if unavailable.
    accuracy_m : float, optional
        Reported 1-sigma horizontal accuracy in metres. Use ``None`` if
        unavailable.
    num_satellites : int, optional
        Number of satellites used in solution.
    speed_m_s : float, optional
        Speed-over-ground from GNSS, m/s.
    """

    timestamp: float
    lat: float
    lon: float
    hdop: float | None = None
    accuracy_m: float | None = None
    num_satellites: int | None = None
    speed_m_s: float | None = None


@dataclass
class TrustDecision:
    """Output of the GNSS Trust Manager for a single fix.

    This is the object Role 3 consumes to decide whether to run a
    GNSS measurement update, and Role 6 logs for evaluation.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp matching the input fix.
    accepted : bool
        True if the fix passes all trust checks.
    score : float
        Composite trust score in [0.0, 1.0].  Even rejected fixes carry
        a score so Role 3 can implement soft down-weighting if desired.
    reason : str
        Human-readable reason for accept/reject (for logging / debugging).
    fix : GNSSFix
        The original fix that was evaluated.
    """

    timestamp: float
    accepted: bool
    score: float
    reason: str
    fix: GNSSFix


# ---------------------------------------------------------------------------
# Trust Manager
# ---------------------------------------------------------------------------

class GNSSTrustManager:
    """Rule-based GNSS fix trust scorer.

    Loads all thresholds from ``configs/role4.yaml`` under the
    ``gnss_trust`` key.  No thresholds are hardcoded.

    Parameters
    ----------
    config_path : str or Path
        Path to the YAML config file.
    event_log_path : str or Path, optional
        Path to JSONL event log file.  If ``None``, the path from config
        is used.

    Examples
    --------
    >>> manager = GNSSTrustManager()
    >>> fix = GNSSFix(timestamp=time.time(), lat=19.051, lon=72.894, hdop=1.2)
    >>> decision = manager.evaluate(fix)
    >>> decision.accepted
    True
    """

    def __init__(
        self,
        config_path: str | Path = "configs/role4.yaml",
        event_log_path: str | Path | None = None,
        enable_logging: bool = True,
    ) -> None:
        cfg = _load_config(config_path)
        self._cfg = cfg["gnss_trust"]
        self._log_cfg = cfg["logging"]

        self._event_log = None
        if enable_logging:
            log_path = Path(event_log_path or self._log_cfg["event_log_path"])
            log_path.parent.mkdir(parents=True, exist_ok=True)
            # The manager owns this long-lived handle and closes it in close().
            self._event_log = open(log_path, "a", buffering=1)  # noqa: SIM115

        self._last_accepted_fix: GNSSFix | None = None

        logger.debug("GNSSTrustManager initialised with config from %s", config_path)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def evaluate(self, fix: GNSSFix, now: float | None = None) -> TrustDecision:
        """Evaluate a GNSS fix and return a TrustDecision.

        Runs the following checks in order (first failure short-circuits
        the score to 0 and marks the fix rejected):

        1. Fix age check (if wall-clock is available)
        2. Satellite count check
        3. HDOP check
        4. Accuracy check
        5. Jump-distance sanity check

        Then computes a weighted composite score from the non-binary
        dimensions (HDOP, accuracy, age, jump).

        Parameters
        ----------
        fix : GNSSFix
            The incoming GNSS fix to evaluate.

        Returns
        -------
        TrustDecision
            Accept/reject decision with score and reason.
        """
        checks_passed: list[str] = []
        partial_scores: dict[str, float] = {}

        # Live callers use wall-clock time. Replay callers pass the current
        # trip timestamp so relative ``time_since_start_s`` fixes are not
        # incorrectly rejected as decades old.
        current_time = time.time() if now is None else float(now)

        # --- 1. Fix age ---
        age_s = max(0.0, current_time - fix.timestamp)
        max_age = self._cfg["max_fix_age_s"]
        if age_s > max_age:
            reason = f"Fix too stale: age={age_s:.1f}s > max={max_age}s"
            return self._reject(fix, score=0.0, reason=reason)
        age_score = max(0.0, 1.0 - age_s / max_age)
        partial_scores["age"] = age_score
        checks_passed.append(f"age={age_s:.2f}s")

        # --- 2. Satellite count ---
        min_sats = self._cfg["min_satellites"]
        if fix.num_satellites is not None and fix.num_satellites < min_sats:
            reason = f"Too few satellites: {fix.num_satellites} < {min_sats}"
            return self._reject(fix, score=0.0, reason=reason)
        checks_passed.append(f"sats={fix.num_satellites}")

        # --- 3. HDOP ---
        max_hdop = self._cfg["max_hdop"]
        if fix.hdop is not None:
            if fix.hdop > max_hdop:
                reason = f"HDOP too high: {fix.hdop:.2f} > {max_hdop}"
                return self._reject(fix, score=0.0, reason=reason)
            hdop_score = max(0.0, 1.0 - (fix.hdop - 1.0) / (max_hdop - 1.0))
        else:
            hdop_score = 0.5  # unknown → neutral
        partial_scores["hdop"] = hdop_score
        checks_passed.append(f"hdop={fix.hdop}")

        # --- 4. Accuracy ---
        max_acc = self._cfg["max_accuracy_m"]
        if fix.accuracy_m is not None:
            if fix.accuracy_m > max_acc:
                reason = f"Accuracy too poor: {fix.accuracy_m:.1f}m > {max_acc}m"
                return self._reject(fix, score=0.0, reason=reason)
            acc_score = max(0.0, 1.0 - fix.accuracy_m / max_acc)
        else:
            acc_score = 0.5  # unknown → neutral
        partial_scores["accuracy"] = acc_score
        checks_passed.append(f"accuracy={fix.accuracy_m}")

        # --- 5. Jump-distance sanity ---
        if self._last_accepted_fix is not None:
            prev = self._last_accepted_fix
            dt = fix.timestamp - prev.timestamp
            dist_m = haversine_distance(prev.lat, prev.lon, fix.lat, fix.lon)

            max_speed = self._cfg["max_speed_m_s"]
            jump_factor = self._cfg["jump_factor"]
            abs_max = self._cfg["abs_max_jump_m"]

            speed_limit_m = max_speed * abs(dt) * jump_factor if dt > 0 else abs_max
            max_jump = min(abs_max, max(speed_limit_m, 0.0))

            if dist_m > max_jump:
                reason = (
                    f"Jump too large: {dist_m:.1f}m > {max_jump:.1f}m "
                    f"(dt={dt:.2f}s)"
                )
                return self._reject(fix, score=0.0, reason=reason)

            jump_score = max(0.0, 1.0 - dist_m / max_jump) if max_jump > 0 else 1.0
        else:
            jump_score = 1.0  # no previous fix to compare against
        partial_scores["jump"] = jump_score
        checks_passed.append(f"jump_score={jump_score:.2f}")

        # --- Composite score ---
        w = self._cfg
        score = (
            w["weight_hdop"] * partial_scores.get("hdop", 0.5) +
            w["weight_accuracy"] * partial_scores.get("accuracy", 0.5) +
            w["weight_age"] * partial_scores.get("age", 1.0) +
            w["weight_jump"] * partial_scores.get("jump", 1.0)
        )
        score = max(0.0, min(1.0, score))

        reason = "All checks passed: " + ", ".join(checks_passed)
        self._last_accepted_fix = fix
        return self._accept(fix, score=score, reason=reason)

    def close(self) -> None:
        """Flush and close the event log file."""
        if self._event_log is not None:
            self._event_log.close()

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _accept(self, fix: GNSSFix, score: float, reason: str) -> TrustDecision:
        decision = TrustDecision(
            timestamp=fix.timestamp,
            accepted=True,
            score=score,
            reason=reason,
            fix=fix,
        )
        self._log_event("GNSS_ACCEPT", decision)
        return decision

    def _reject(self, fix: GNSSFix, score: float, reason: str) -> TrustDecision:
        decision = TrustDecision(
            timestamp=fix.timestamp,
            accepted=False,
            score=score,
            reason=reason,
            fix=fix,
        )
        self._log_event("GNSS_REJECT", decision)
        return decision

    def _log_event(self, event_type: str, decision: TrustDecision) -> None:
        """Write a structured JSON event to the JSONL log (Role 6 format)."""
        record = {
            "event_type": event_type,
            "timestamp": decision.timestamp,
            "accepted": decision.accepted,
            "score": round(decision.score, 4),
            "reason": decision.reason,
            "fix_lat": decision.fix.lat,
            "fix_lon": decision.fix.lon,
            "hdop": decision.fix.hdop,
            "accuracy_m": decision.fix.accuracy_m,
            "num_satellites": decision.fix.num_satellites,
        }
        if self._event_log is not None:
            self._event_log.write(json.dumps(record) + "\n")
        logger.debug("GNSSTrust %s score=%.3f reason=%s",
                     event_type, decision.score, decision.reason)
