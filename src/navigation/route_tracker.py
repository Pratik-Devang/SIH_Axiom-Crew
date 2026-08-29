"""
route_tracker.py
================
Route progress estimation during GNSS-available and GNSS-denied navigation.

During GNSS availability
------------------------
The vehicle's GNSS-derived ENU position is projected onto the route polyline
using multi-criteria candidate scoring to establish reliable route progress
and matched segment before outage.

During GNSS denial
------------------
Previous route progress + estimated forward displacement from ESKF velocity
(and TCN speed) propagates route progress along the route geometry.  The
estimated heading is compared against the current route segment bearing to
detect when a turn is being executed, which triggers a transition to the
next route segment's bearing.

Design rules
------------
- Route progress NEVER decreases unless an explicit U-turn is detected.
- Segment matching uses a bounded search window — not full-route search.
- Heading consistency is a hard-gating factor: geometrically close but
  heading-incompatible segments score poorly and are rarely matched.
- The ``route_confidence`` field reflects how trustworthy the current
  route match is.  Low confidence suppresses the route constraint update
  in route_update.py so that a bad match does not corrupt the ESKF state.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

from src.navigation.route import Route, RouteManeuver, RouteSegment


# --------------------------------------------------------------------------
# Helper
# --------------------------------------------------------------------------

def _wrap_to_pi(angle_rad: float) -> float:
    """Wrap angle (radians) to (-pi, pi]."""
    return (angle_rad + math.pi) % (2.0 * math.pi) - math.pi


def _bearing_to_heading_rad(bearing_deg: float) -> float:
    """Convert CW-from-North bearing (deg) to CCW-from-East heading (rad)."""
    return math.radians(90.0 - bearing_deg)


# --------------------------------------------------------------------------
# RouteMatchResult
# --------------------------------------------------------------------------

@dataclass
class RouteMatchResult:
    """Result of a single route-matching evaluation.

    Attributes
    ----------
    segment_index : int
        Matched segment index in the route.
    progress_m : float
        Cumulative route distance to the closest projection point (metres).
    lateral_error_m : float
        Signed perpendicular distance from matched segment.
        Positive = left of travel direction, Negative = right.
    along_track_error_m : float
        Residual along-track component (not used by ESKF update; diagnostic only).
    current_bearing_deg : float
        Bearing of the matched segment, degrees CW from North.
    next_bearing_deg : float
        Bearing of the next segment (after the upcoming maneuver), or
        equal to ``current_bearing_deg`` if no maneuver is imminent.
    distance_to_next_maneuver_m : float
        Distance to the next route maneuver in metres.  0.0 if none.
    next_maneuver : RouteManeuver or None
        Reference to the upcoming maneuver (for heading-transition timing).
    confidence : float
        Scalar [0.0, 1.0] summarising how reliable this match is.
    """

    segment_index: int
    progress_m: float
    lateral_error_m: float
    along_track_error_m: float
    current_bearing_deg: float
    next_bearing_deg: float
    distance_to_next_maneuver_m: float
    next_maneuver: Optional[RouteManeuver]
    confidence: float


# --------------------------------------------------------------------------
# RouteProgressTracker
# --------------------------------------------------------------------------

@dataclass
class RouteProgressTrackerConfig:
    """Tuning parameters for the route progress tracker."""

    # Maximum lateral distance to consider a segment as a candidate (metres)
    lateral_gate_m: float = 30.0

    # Maximum heading difference for a valid candidate (degrees)
    heading_gate_deg: float = 60.0

    # Candidate scoring weights
    w_lateral: float = 0.45
    w_heading: float = 0.40
    w_progress: float = 0.15

    # How many segments ahead to search during GNSS outage
    # (limits backward jumps and impossible segment skips)
    forward_search_segments: int = 5

    # Minimum route_confidence below which no constraint update is issued
    min_confidence_for_update: float = 0.25

    # Transition window: within this distance of a maneuver, start
    # blending the outgoing bearing as the target
    maneuver_transition_m: float = 20.0

    # Speed threshold below which heading comparison is skipped (stationary)
    min_speed_for_heading_ms: float = 0.5


class RouteProgressTracker:
    """Tracks vehicle progress along a planned route.

    This class is stateful.  It maintains the current segment index and
    cumulative route progress, and ensures progress is monotonic except
    during verified U-turn / reversal events.

    Parameters
    ----------
    route : Route
        The planned navigation route.
    config : RouteProgressTrackerConfig, optional
        Tuning parameters.  Defaults are appropriate for urban driving.
    """

    def __init__(
        self,
        route: Route,
        config: Optional[RouteProgressTrackerConfig] = None,
    ) -> None:
        self.route = route
        self.config = config or RouteProgressTrackerConfig()

        # Current tracker state
        self._segment_index: int = 0
        self._progress_m: float = 0.0
        self._initialized: bool = False

        # Last confirmed result for GNSS-outage propagation
        self._last_result: Optional[RouteMatchResult] = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def is_initialized(self) -> bool:
        return self._initialized

    @property
    def current_segment_index(self) -> int:
        return self._segment_index

    @property
    def current_progress_m(self) -> float:
        return self._progress_m

    def update_with_position(
        self,
        east: float,
        north: float,
        heading_rad: float,
        speed_ms: float = 0.0,
    ) -> RouteMatchResult:
        """Update route progress from an ENU position estimate (GNSS or ESKF).

        This is the primary update path.  It is called with:
        - GNSS position during GNSS-available phases.
        - ESKF state position during GNSS-outage phases.

        Parameters
        ----------
        east, north : float
            2D position in local ENU metres.
        heading_rad : float
            Vehicle travel heading in radians (CCW from East).
        speed_ms : float
            Vehicle forward speed in m/s. Used to gate heading comparisons.

        Returns
        -------
        RouteMatchResult
        """
        cfg = self.config

        if self._initialized:
            # During normal tracking: bounded forward search only
            min_idx = self._segment_index
            max_idx = min(
                self._segment_index + cfg.forward_search_segments,
                self.route.num_segments - 1,
            )
        else:
            # Initialization: search the entire route
            min_idx = 0
            max_idx = self.route.num_segments - 1

        # Score all candidate segments in the window
        best_result = self._score_candidates(
            east, north, heading_rad, speed_ms, min_idx, max_idx
        )

        if best_result is None:
            # No candidate found in the forward window. Try a wider search.
            extended_max = min(
                self._segment_index + 2 * cfg.forward_search_segments,
                self.route.num_segments - 1,
            )
            best_result = self._score_candidates(
                east, north, heading_rad, speed_ms, min_idx, extended_max
            )

        if best_result is None:
            # Still no match — return last known result with significantly reduced confidence.
            # A large penalty (0.5) when *all* candidates are heading-excluded signals that
            # the vehicle heading is inconsistent with the route — e.g. reversed direction.
            if self._last_result is not None:
                degraded = RouteMatchResult(
                    **{k: v for k, v in self._last_result.__dict__.items()}
                )
                degraded.confidence = max(0.0, self._last_result.confidence - 0.5)
                return degraded
            # No last result either — create a zero-confidence fallback
            return self._fallback_result()

        # Enforce monotonic progress (only advance, never retreat)
        if self._initialized and best_result.progress_m < self._progress_m - 5.0:
            # Suspicious backward jump — reject unless U-turn confirmed
            # Detect U-turn: heading is roughly opposite to progress direction
            heading_deg = math.degrees(heading_rad) % 360.0
            reverse_bearing = (self._last_result.current_bearing_deg + 180.0) % 360.0
            heading_vs_reverse = abs(
                ((heading_deg - reverse_bearing + 180.0) % 360.0) - 180.0
            )
            if heading_vs_reverse > 30.0:
                # Not a U-turn — stick with current progress
                best_result.progress_m = self._progress_m
                best_result.segment_index = self._segment_index
                best_result.confidence *= 0.5

        # Update state
        if best_result.confidence > 0.1:
            self._segment_index = best_result.segment_index
            self._progress_m = max(self._progress_m, best_result.progress_m) \
                if self._initialized else best_result.progress_m
            best_result.progress_m = self._progress_m
            self._initialized = True
            self._last_result = best_result

        return best_result

    def propagate_with_speed(
        self,
        forward_speed_ms: float,
        dt: float,
    ) -> RouteMatchResult:
        """Propagate route progress using forward speed estimate alone.

        Used during GNSS outage when no position estimate is available to
        match against (fallback only).  Normally, update_with_position is
        called with the ESKF state position.

        Parameters
        ----------
        forward_speed_ms : float
            Forward vehicle speed (m/s).
        dt : float
            Time step in seconds.

        Returns
        -------
        RouteMatchResult
        """
        if not self._initialized:
            return self._fallback_result()

        # Advance progress along route arc-length
        delta_progress = forward_speed_ms * dt
        new_progress = self._progress_m + delta_progress

        # Advance to the correct segment
        seg_index = self._segment_index
        while seg_index < self.route.num_segments - 1:
            seg = self.route.segments[seg_index]
            if new_progress < seg.end.cumulative_dist_m:
                break
            seg_index += 1
        self._segment_index = seg_index
        self._progress_m = min(new_progress, self.route.total_distance_m)

        result = self._build_result_for_segment(seg_index, lateral_m=0.0)
        self._last_result = result
        return result

    def reset(self) -> None:
        """Reset tracker to uninitialized state."""
        self._segment_index = 0
        self._progress_m = 0.0
        self._initialized = False
        self._last_result = None

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _score_candidates(
        self,
        east: float,
        north: float,
        heading_rad: float,
        speed_ms: float,
        min_idx: int,
        max_idx: int,
    ) -> Optional[RouteMatchResult]:
        """Score all candidate segments in [min_idx, max_idx] and return best.

        Key design decisions:
        - ``along_m_raw`` is the raw (unclamped) dot product with the segment
          tangent.  For perpendicular / lateral distance we use the clamped
          version; for progress we use the raw version so that a vehicle that
          has passed the segment end is credited with progress beyond it.
        - When two candidates score within 1e-5 of each other, the one with
          greater forward progress wins.  This breaks ties that previously
          locked the tracker to the first (lowest-index) segment permanently.
        """
        cfg = self.config
        best_score = -1.0
        best_progress = -1.0
        best_result: Optional[RouteMatchResult] = None

        for seg in self.route.segments[min_idx : max_idx + 1]:
            # Raw (unclamped) projection along segment tangent and normal
            de = east - seg.start.east
            dn = north - seg.start.north
            along_m_raw = float(seg.unit_tangent[0] * de + seg.unit_tangent[1] * dn)
            lateral_m   = float(seg.unit_normal[0]   * de + seg.unit_normal[1]   * dn)

            # Clamped along-track for perpendicular-distance measurement only
            along_m_clamped = float(max(0.0, min(along_m_raw, seg.length_m)))

            # Euclidean distance to nearest point on the segment (not just lateral)
            perp_dist = abs(lateral_m)
            cp_de = along_m_clamped * seg.unit_tangent[0] - (along_m_raw - along_m_clamped) * 0.0
            cp_dn = along_m_clamped * seg.unit_tangent[1]
            dist_to_seg = math.hypot(de - along_m_clamped * seg.unit_tangent[0],
                                     dn - along_m_clamped * seg.unit_tangent[1])

            # Use 2-D Euclidean distance for the lateral gate so endpoint
            # proximity works even when the vehicle has passed the segment end.
            if dist_to_seg > cfg.lateral_gate_m:
                continue

            # 1. Heading consistency score
            if speed_ms >= cfg.min_speed_for_heading_ms:
                seg_heading_rad = _bearing_to_heading_rad(seg.bearing_deg)
                heading_diff_rad = abs(_wrap_to_pi(heading_rad - seg_heading_rad))
                heading_diff_deg = math.degrees(heading_diff_rad)
                if heading_diff_deg > cfg.heading_gate_deg:
                    continue
                heading_score = max(0.0, 1.0 - heading_diff_deg / cfg.heading_gate_deg)
            else:
                heading_score = 0.5  # neutral when stationary

            # 2. Lateral distance score (use Euclidean distance to segment)
            lateral_score = max(0.0, 1.0 - dist_to_seg / cfg.lateral_gate_m)

            # 3. Progress continuity score
            # Use the RAW along-track so the vehicle can advance beyond a
            # segment end and carry a progress value into the next segment.
            # When along_m_raw > seg.length_m the vehicle has passed this
            # segment; the raw progress is still meaningful for ordering.
            seg_progress_raw = seg.start.cumulative_dist_m + max(0.0, along_m_raw)

            if self._initialized:
                progress_delta = seg_progress_raw - self._progress_m
                if progress_delta < -2.0:
                    # Backward regression: penalise
                    progress_score = max(0.0, 1.0 + progress_delta / 20.0)
                elif progress_delta >= 0.0:
                    progress_score = 1.0
                else:
                    # Tiny backward slip (< 2 m): mild penalty
                    progress_score = 0.85
            else:
                progress_score = 1.0

            # Composite score
            score = (
                cfg.w_lateral   * lateral_score
                + cfg.w_heading * heading_score
                + cfg.w_progress * progress_score
            )

            # Choose this candidate if:
            #   (a) it beats the current best by more than tolerance, OR
            #   (b) it ties within tolerance AND has more forward progress
            #       (breaks the first-in-loop bias that prevented advancing).
            is_better = (
                score > best_score + 1e-5
                or (abs(score - best_score) <= 1e-5 and seg_progress_raw > best_progress)
            )
            if is_better:
                best_score    = score
                best_progress = seg_progress_raw
                result = self._build_result_for_segment(
                    seg.index,
                    lateral_m=lateral_m,
                    progress_m=seg_progress_raw,
                    confidence=score,
                )
                best_result = result

        return best_result


    def _build_result_for_segment(
        self,
        seg_index: int,
        lateral_m: float = 0.0,
        progress_m: Optional[float] = None,
        confidence: float = 0.5,
    ) -> RouteMatchResult:
        """Build a RouteMatchResult for a given segment index."""
        seg = self.route.segments[seg_index]
        if progress_m is None:
            progress_m = self._progress_m

        dist_to_man, next_man = self.route.distance_to_next_maneuver(progress_m)

        # Determine next bearing
        if next_man is not None:
            next_bearing = next_man.outgoing_bearing_deg
        elif seg_index + 1 < self.route.num_segments:
            next_bearing = self.route.segments[seg_index + 1].bearing_deg
        else:
            next_bearing = seg.bearing_deg

        # Within maneuver transition zone: blend toward outgoing bearing
        if next_man is not None and dist_to_man < self.config.maneuver_transition_m:
            blend = 1.0 - dist_to_man / self.config.maneuver_transition_m
            # Use bearing interpolation (wrapping-safe)
            diff = ((next_bearing - seg.bearing_deg + 180.0) % 360.0) - 180.0
            effective_bearing = (seg.bearing_deg + blend * diff) % 360.0
        else:
            effective_bearing = seg.bearing_deg

        return RouteMatchResult(
            segment_index=seg_index,
            progress_m=progress_m,
            lateral_error_m=lateral_m,
            along_track_error_m=0.0,
            current_bearing_deg=effective_bearing,
            next_bearing_deg=next_bearing,
            distance_to_next_maneuver_m=dist_to_man,
            next_maneuver=next_man,
            confidence=confidence,
        )

    def _fallback_result(self) -> RouteMatchResult:
        """Return a zero-confidence result for initialization phase."""
        return RouteMatchResult(
            segment_index=0,
            progress_m=0.0,
            lateral_error_m=0.0,
            along_track_error_m=0.0,
            current_bearing_deg=0.0,
            next_bearing_deg=0.0,
            distance_to_next_maneuver_m=0.0,
            next_maneuver=None,
            confidence=0.0,
        )
