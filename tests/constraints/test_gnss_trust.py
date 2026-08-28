"""
test_gnss_trust.py
==================
Tests for navigation.gnss_trust.GNSSTrustManager.

Tests cover all four rejection paths + the composite score calculation.
"""

from __future__ import annotations

import time

import pytest

from src.constraints.gnss_trust import GNSSFix, GNSSTrustManager, TrustDecision


def _fix(**kwargs) -> GNSSFix:
    """Create a GNSSFix with sensible defaults; override with kwargs."""
    defaults = dict(
        timestamp=time.time(),
        lat=19.051,
        lon=72.894,
        hdop=1.2,
        accuracy_m=5.0,
        num_satellites=8,
        speed_m_s=10.0,
    )
    defaults.update(kwargs)
    return GNSSFix(**defaults)


# ---------------------------------------------------------------------------
# Accept path
# ---------------------------------------------------------------------------

class TestGNSSTrustAccept:
    def test_good_fix_is_accepted(self, gnss_trust_manager):
        """A fix with good HDOP, accuracy, fresh timestamp → accepted."""
        fix = _fix()
        decision = gnss_trust_manager.evaluate(fix)
        assert isinstance(decision, TrustDecision)
        assert decision.accepted is True
        assert decision.score > 0.5

    def test_accepted_score_between_0_and_1(self, gnss_trust_manager):
        fix = _fix()
        decision = gnss_trust_manager.evaluate(fix)
        assert 0.0 <= decision.score <= 1.0

    def test_no_optional_fields_still_accepted(self, gnss_trust_manager):
        """Fix with no HDOP/accuracy/sats → neutral scores, still accepted."""
        fix = _fix(hdop=None, accuracy_m=None, num_satellites=None)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is True

    def test_decision_has_reason_string(self, gnss_trust_manager):
        fix = _fix()
        decision = gnss_trust_manager.evaluate(fix)
        assert isinstance(decision.reason, str)
        assert len(decision.reason) > 0


# ---------------------------------------------------------------------------
# Reject: HDOP
# ---------------------------------------------------------------------------

class TestGNSSTrustHDOP:
    def test_high_hdop_rejected(self, gnss_trust_manager):
        """HDOP above max_hdop (4.0) → rejected."""
        fix = _fix(hdop=8.0)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is False
        assert decision.score == 0.0
        assert "HDOP" in decision.reason or "hdop" in decision.reason.lower()

    def test_borderline_hdop_accepted(self, gnss_trust_manager):
        """HDOP just below max_hdop → accepted."""
        fix = _fix(hdop=3.9)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is True


# ---------------------------------------------------------------------------
# Reject: accuracy
# ---------------------------------------------------------------------------

class TestGNSSTrustAccuracy:
    def test_poor_accuracy_rejected(self, gnss_trust_manager):
        """Accuracy above max_accuracy_m (20m) → rejected."""
        fix = _fix(accuracy_m=50.0)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is False
        assert "ccuracy" in decision.reason  # "Accuracy" or "accuracy"

    def test_good_accuracy_accepted(self, gnss_trust_manager):
        fix = _fix(accuracy_m=3.0)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is True


# ---------------------------------------------------------------------------
# Reject: satellite count
# ---------------------------------------------------------------------------

class TestGNSSTrustSatellites:
    def test_too_few_satellites_rejected(self, gnss_trust_manager):
        """Fewer than min_satellites (4) → rejected."""
        fix = _fix(num_satellites=2)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is False
        assert "satellite" in decision.reason.lower()

    def test_exactly_min_satellites_accepted(self, gnss_trust_manager):
        fix = _fix(num_satellites=4)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is True


# ---------------------------------------------------------------------------
# Reject: jump distance
# ---------------------------------------------------------------------------

class TestGNSSTrustJump:
    def test_large_jump_rejected(self, gnss_trust_manager):
        """Two consecutive fixes 300 m apart in 1 s → jump rejected."""
        t = time.time()
        fix1 = _fix(timestamp=t, lat=19.051, lon=72.894)
        gnss_trust_manager.evaluate(fix1)  # prime the last-accepted cache

        # ~300 m north of fix1 (very roughly 0.0027 degrees lat ≈ 300 m)
        fix2 = _fix(timestamp=t + 1.0, lat=19.0537, lon=72.894)
        decision = gnss_trust_manager.evaluate(fix2)
        assert decision.accepted is False
        assert "Jump" in decision.reason or "jump" in decision.reason.lower()

    def test_normal_jump_accepted(self, gnss_trust_manager):
        """Two fixes ~50 m apart in 5 s (10 m/s) → accepted."""
        t = time.time()
        fix1 = _fix(timestamp=t, lat=19.051, lon=72.894)
        gnss_trust_manager.evaluate(fix1)
        # ~50 m east (very roughly 0.00045 degrees lon)
        fix2 = _fix(timestamp=t + 5.0, lat=19.051, lon=72.8945)
        decision = gnss_trust_manager.evaluate(fix2)
        assert decision.accepted is True


# ---------------------------------------------------------------------------
# Reject: stale fix
# ---------------------------------------------------------------------------

class TestGNSSTrustAge:
    def test_stale_fix_rejected(self, gnss_trust_manager):
        """Fix timestamped 10 seconds in the past → rejected."""
        stale_ts = time.time() - 10.0
        fix = _fix(timestamp=stale_ts)
        decision = gnss_trust_manager.evaluate(fix)
        assert decision.accepted is False
        assert "stale" in decision.reason.lower() or "age" in decision.reason.lower()


# ---------------------------------------------------------------------------
# JSONL event log
# ---------------------------------------------------------------------------

class TestGNSSTrustEventLog:
    def test_log_file_written_on_accept(self, tmp_path):
        """Accepted fix must write a JSONL record."""
        import json
        log_path = tmp_path / "gnss.jsonl"
        mgr = GNSSTrustManager(config_path="configs/role4.yaml",
                               event_log_path=log_path)
        mgr.evaluate(_fix())
        mgr.close()
        assert log_path.exists()
        lines = log_path.read_text().strip().splitlines()
        assert len(lines) >= 1
        record = json.loads(lines[0])
        assert "event_type" in record
        assert "timestamp" in record
        assert "accepted" in record

    def test_log_file_written_on_reject(self, tmp_path):
        """Rejected fix must also write a JSONL record."""
        import json
        log_path = tmp_path / "gnss.jsonl"
        mgr = GNSSTrustManager(config_path="configs/role4.yaml",
                               event_log_path=log_path)
        mgr.evaluate(_fix(hdop=99.0))
        mgr.close()
        lines = log_path.read_text().strip().splitlines()
        record = json.loads(lines[0])
        assert record["accepted"] is False
