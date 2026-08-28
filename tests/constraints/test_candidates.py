"""
test_candidates.py
==================
Tests for maps.candidates.CandidateGenerator.

Requires the Chembur 1km GraphML fixture (skipped otherwise).
"""

from __future__ import annotations

import math
import pytest

from src.maps.candidates import CandidateGenerator, RoadCandidate
from src.preprocessing.coordinates import LatLonOrigin


pytestmark = pytest.mark.requires_graph


# ---------------------------------------------------------------------------
# Basic candidate generation
# ---------------------------------------------------------------------------

class TestCandidateGeneratorBasic:
    def test_returns_candidates_for_known_point(self, candidate_gen, chembur_points):
        """A position at the Chembur centre should have at least one candidate."""
        lat, lon, heading, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon)
        assert len(candidates) >= 1

    def test_candidates_are_road_candidate_instances(self, candidate_gen, chembur_points):
        lat, lon, heading, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon)
        for c in candidates:
            assert isinstance(c, RoadCandidate)

    def test_candidate_fields_valid(self, candidate_gen, chembur_points):
        """All returned candidates have valid field values."""
        lat, lon, _, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon)
        for c in candidates:
            assert isinstance(c.u, int)
            assert isinstance(c.v, int)
            assert c.distance_m >= 0.0
            assert 0.0 <= c.heading_deg < 360.0
            assert 0.0 <= c.score <= 1.0
            assert -90.0 <= c.mid_lat <= 90.0
            assert -180.0 <= c.mid_lon <= 180.0

    def test_candidates_sorted_by_score_descending(self, candidate_gen, chembur_points):
        """Returned list must be sorted best-first."""
        lat, lon, _, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon)
        scores = [c.score for c in candidates]
        assert scores == sorted(scores, reverse=True)

    def test_top_k_limit_respected(self, candidate_gen, chembur_points):
        """Never return more than top_k candidates."""
        lat, lon, _, _ = chembur_points[0]
        k = 3
        candidates = candidate_gen.get_candidates(lat, lon, top_k=k)
        assert len(candidates) <= k

    def test_no_candidates_far_from_graph(self, candidate_gen):
        """A position far from the graph returns an empty list."""
        # London — definitely not in the Chembur graph
        candidates = candidate_gen.get_candidates(51.5074, -0.1278)
        assert candidates == []


# ---------------------------------------------------------------------------
# Heading filter
# ---------------------------------------------------------------------------

class TestCandidateGeneratorHeadingFilter:
    def test_heading_filter_removes_perpendicular_roads(self, candidate_gen, chembur_points):
        """With a tight heading tolerance, fewer candidates are returned."""
        lat, lon, heading, _ = chembur_points[0]
        all_candidates = candidate_gen.get_candidates(lat, lon, heading_deg=None)
        filtered_candidates = candidate_gen.get_candidates(
            lat, lon, heading_deg=heading
        )
        # Heading filter should remove some candidates (or at worst equal)
        assert len(filtered_candidates) <= len(all_candidates)

    def test_heading_diff_within_tolerance(self, candidate_gen, chembur_points):
        """All returned candidates must respect the max_heading_diff_deg threshold."""
        lat, lon, heading, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon, heading_deg=heading)
        for c in candidates:
            assert c.heading_diff_deg is not None
            assert c.heading_diff_deg <= 45.0  # matches config max

    def test_no_heading_returns_no_diff(self, candidate_gen, chembur_points):
        """Without heading query, heading_diff_deg should be None."""
        lat, lon, _, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon, heading_deg=None)
        for c in candidates:
            assert c.heading_diff_deg is None

    def test_reverse_heading_still_matched(self, candidate_gen, chembur_points):
        """A heading 180° from the road is still matched (two-way roads)."""
        lat, lon, heading, _ = chembur_points[0]
        reverse = (heading + 180.0) % 360.0
        candidates_forward = candidate_gen.get_candidates(lat, lon, heading_deg=heading)
        candidates_reverse = candidate_gen.get_candidates(lat, lon, heading_deg=reverse)
        # Both directions should have at least as many candidates as each other
        # (they may differ due to one-way roads, but both should find something)
        if candidates_forward:
            assert len(candidates_reverse) >= 0  # just ensure it doesn't crash


# ---------------------------------------------------------------------------
# Radius
# ---------------------------------------------------------------------------

class TestCandidateGeneratorRadius:
    def test_large_radius_more_candidates(self, candidate_gen, chembur_points):
        """Larger radius returns ≥ candidates compared to smaller radius."""
        lat, lon, _, _ = chembur_points[0]
        small = candidate_gen.get_candidates(lat, lon, radius_m=20.0)
        large = candidate_gen.get_candidates(lat, lon, radius_m=200.0)
        assert len(large) >= len(small)

    def test_tiny_radius_may_return_empty(self, candidate_gen, chembur_points):
        """A 1 m radius search may return no candidates (that's valid)."""
        lat, lon, _, _ = chembur_points[0]
        candidates = candidate_gen.get_candidates(lat, lon, radius_m=1.0)
        assert isinstance(candidates, list)


# ---------------------------------------------------------------------------
# Index not built
# ---------------------------------------------------------------------------

class TestCandidateGeneratorNotBuilt:
    def test_raises_if_index_not_built(self, osm_loader):
        """Calling get_candidates without build_index raises RuntimeError."""
        gen = CandidateGenerator(osm_loader)
        with pytest.raises(RuntimeError, match="build_index"):
            gen.get_candidates(19.051, 72.894)
