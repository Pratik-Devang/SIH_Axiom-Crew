"""Map-matching constraints and candidate generation interface."""

from __future__ import annotations

from src.maps.candidates import CandidateGenerator, RoadCandidate
from src.maps.confidence import score_candidate, gaussian_emission_score
from src.maps.hmm_matcher import HMMMapMatcher, MatchResult
from src.maps.osm_loader import OSMLoader

__all__ = [
    "OSMLoader",
    "CandidateGenerator",
    "RoadCandidate",
    "HMMMapMatcher",
    "MatchResult",
    "score_candidate",
    "gaussian_emission_score",
]
