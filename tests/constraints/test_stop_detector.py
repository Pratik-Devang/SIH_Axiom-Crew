"""
test_stop_detector.py
=====================
Tests for navigation.stop_detector.StopDetector.
"""

from __future__ import annotations

import pytest

from src.constraints.stop_detection import ConstraintEvent, StopDetector, StopEvent


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def feed_speeds(detector: StopDetector, speeds, start_t: float = 0.0, dt: float = 0.1):
    """Feed a list of speed samples to the detector and return all StopEvents."""
    events = []
    for i, s in enumerate(speeds):
        ev = detector.update(timestamp=start_t + i * dt, speed_m_s=s)
        events.append(ev)
    return events


# ---------------------------------------------------------------------------
# Stopped state
# ---------------------------------------------------------------------------

class TestStopDetectorStopped:
    def test_zero_speed_window_returns_stopped(self, stop_detector):
        """10 samples at 0 m/s → is_stopped=True after min_stationary_samples."""
        events = feed_speeds(stop_detector, [0.0] * 15)
        # At least the last event must report stopped
        assert events[-1].is_stopped is True

    def test_stopped_confidence_is_positive(self, stop_detector):
        events = feed_speeds(stop_detector, [0.0] * 15)
        assert events[-1].confidence > 0.0

    def test_stopped_confidence_bounded(self, stop_detector):
        events = feed_speeds(stop_detector, [0.0] * 100)
        for ev in events:
            assert 0.0 <= ev.confidence <= 1.0

    def test_very_low_speed_is_stopped(self, stop_detector):
        """Speed below threshold (0.5 m/s) → stopped."""
        events = feed_speeds(stop_detector, [0.1] * 15)
        assert events[-1].is_stopped is True

    def test_stop_event_duration_increases(self, stop_detector):
        """Duration increases as vehicle stays stopped."""
        events = feed_speeds(stop_detector, [0.0] * 30, dt=1.0)
        stopped_events = [e for e in events if e.is_stopped]
        if len(stopped_events) >= 2:
            durations = [e.duration_s for e in stopped_events]
            assert durations[-1] >= durations[0]


# ---------------------------------------------------------------------------
# Moving state
# ---------------------------------------------------------------------------

class TestStopDetectorMoving:
    def test_high_speed_not_stopped(self, stop_detector):
        """10 m/s → is_stopped=False."""
        events = feed_speeds(stop_detector, [10.0] * 15)
        assert events[-1].is_stopped is False

    def test_confidence_zero_when_moving(self, stop_detector):
        events = feed_speeds(stop_detector, [10.0] * 15)
        assert events[-1].confidence == 0.0

    def test_speed_just_above_threshold_not_stopped(self, stop_detector):
        """Speed at 0.6 m/s (above 0.5 threshold) → not stopped."""
        events = feed_speeds(stop_detector, [0.6] * 15)
        assert events[-1].is_stopped is False


# ---------------------------------------------------------------------------
# State transitions
# ---------------------------------------------------------------------------

class TestStopDetectorTransitions:
    def test_stop_then_move(self, stop_detector):
        """Sequence: stopped → moving → not stopped."""
        # Stopped phase
        feed_speeds(stop_detector, [0.0] * 15)
        # Moving phase
        events = feed_speeds(stop_detector, [10.0] * 15, start_t=2.0)
        assert events[-1].is_stopped is False

    def test_brief_high_speed_then_stop(self, stop_detector):
        """High speed then zero speed: detector correctly transitions."""
        feed_speeds(stop_detector, [20.0] * 5)
        events = feed_speeds(stop_detector, [0.0] * 20, start_t=1.0)
        assert events[-1].is_stopped is True


# ---------------------------------------------------------------------------
# ConstraintEvent output (Role 3 interface)
# ---------------------------------------------------------------------------

class TestStopDetectorConstraintEvent:
    def test_stopped_produces_zupt_event(self, stop_detector):
        """Stopped vehicle → ConstraintEvent(type='ZUPT')."""
        events = feed_speeds(stop_detector, [0.0] * 15)
        last = events[-1]
        constraint = stop_detector.to_constraint_event(last)
        assert constraint is not None
        assert isinstance(constraint, ConstraintEvent)
        assert constraint.type == "ZUPT"
        assert constraint.value == 0.0
        assert 0.0 <= constraint.confidence <= 1.0

    def test_moving_produces_no_zupt_event(self, stop_detector):
        """Moving vehicle → to_constraint_event returns None."""
        events = feed_speeds(stop_detector, [10.0] * 15)
        last = events[-1]
        constraint = stop_detector.to_constraint_event(last)
        assert constraint is None

    def test_zupt_event_has_duration_metadata(self, stop_detector):
        events = feed_speeds(stop_detector, [0.0] * 15)
        constraint = stop_detector.to_constraint_event(events[-1])
        assert "duration_s" in constraint.metadata
