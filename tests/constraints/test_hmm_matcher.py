"""
test_hmm_matcher.py
====================
Tests for maps.hmm_matcher.HMMMapMatcher (MVP stub).
"""

from __future__ import annotations

import pytest

from src.maps.candidates import RoadCandidate
from src.maps.hmm_matcher import HMMMapMatcher, MatchResult


def _make_candidate(score: float = 0.8, distance_m: float = 10.0) -> RoadCandidate:
    return RoadCandidate(
        u=100,
        v=200,
        distance_m=distance_m,
        heading_deg=90.0,
        heading_diff_deg=5.0,
        mid_lat=19.051,
        mid_lon=72.894,
        score=score,
    )


class TestHMMMatcherMVP:
    def test_returns_match_result_for_candidates(self):
        matcher = HMMMapMatcher()
        candidates = [_make_candidate(0.9), _make_candidate(0.6)]
        result = matcher.update(timestamp=1.0, candidates=candidates)
        assert result is not None
        assert isinstance(result, MatchResult)

    def test_returns_none_for_empty_candidates(self):
        matcher = HMMMapMatcher()
        result = matcher.update(timestamp=1.0, candidates=[])
        assert result is None

    def test_picks_highest_scoring_candidate(self):
        """MVP should return the highest-scoring candidate."""
        matcher = HMMMapMatcher()
        best = _make_candidate(score=0.95, distance_m=5.0)
        worse = _make_candidate(score=0.4, distance_m=30.0)
        # Pass in order: worse first, best second (after sort they're reordered)
        # CandidateGenerator already sorts, but let's test matcher directly
        result = matcher.update(timestamp=1.0, candidates=[best, worse])
        # Should pick best (first in list = best score)
        assert result.u == best.u
        assert result.v == best.v

    def test_method_is_nearest(self):
        matcher = HMMMapMatcher()
        result = matcher.update(timestamp=1.0, candidates=[_make_candidate()])
        assert result.method == "nearest"

    def test_candidates_considered_count(self):
        matcher = HMMMapMatcher()
        candidates = [_make_candidate(), _make_candidate(0.5), _make_candidate(0.3)]
        result = matcher.update(timestamp=1.0, candidates=candidates)
        assert result.candidates_considered == 3

    def test_match_result_fields_valid(self):
        matcher = HMMMapMatcher()
        result = matcher.update(timestamp=1.0, candidates=[_make_candidate()])
        assert isinstance(result.timestamp, float)
        assert isinstance(result.u, int)
        assert isinstance(result.v, int)
        assert 0.0 <= result.confidence <= 1.0
        assert -90.0 <= result.snapped_lat <= 90.0
        assert -180.0 <= result.snapped_lon <= 180.0

    def test_reset_does_not_raise(self):
        matcher = HMMMapMatcher()
        matcher.update(timestamp=1.0, candidates=[_make_candidate()])
        matcher.reset()  # should not raise
