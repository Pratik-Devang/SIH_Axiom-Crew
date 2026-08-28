"""Vehicle, stop, map and GNSS-integrity constraints (Role 4)."""

from src.constraints.gnss_trust import GNSSFix, GNSSTrustManager, TrustDecision
from src.constraints.stop_detection import ConstraintEvent, StopDetector, StopEvent
from src.constraints.vehicle import NHCState, VehicleConstraintDetector
from src.constraints.map_matching import (
    CandidateGenerator,
    HMMMapMatcher,
    MatchResult,
    OSMLoader,
    RoadCandidate,
    gaussian_emission_score,
    score_candidate,
)

__all__ = [
    "GNSSFix",
    "GNSSTrustManager",
    "TrustDecision",
    "ConstraintEvent",
    "StopDetector",
    "StopEvent",
    "NHCState",
    "VehicleConstraintDetector",
    "OSMLoader",
    "CandidateGenerator",
    "RoadCandidate",
    "HMMMapMatcher",
    "MatchResult",
    "score_candidate",
    "gaussian_emission_score",
]
