"""
hmm_matcher.py
==============
HMM/Viterbi map matcher (stub implementation — stretch goal).

MVP status
----------
This module is a **stub** for the MVP.  The nearest-road snapping in
``candidates.py`` is the primary matching mechanism for the hackathon demo.

This stub defines the complete interface (dataclasses + method signatures)
so that Role 3 and Role 6 can integrate against it without waiting for the
full Viterbi implementation.

Full implementation plan (post-MVP)
-------------------------------------
1. Emission probability: Gaussian(distance_m, sigma=hmm_emission_sigma_m)
2. Transition probability: penalise large gaps between consecutive candidates
   using ``hmm_transition_penalty`` per metre.
3. Viterbi decoding over a rolling buffer of candidate lists.
4. Output: best-path edge sequence + snapped position at each step.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import List, Optional

try:
    from src.maps.candidates import RoadCandidate
except ImportError:
    from maps.candidates import RoadCandidate

logger = logging.getLogger(__name__)


@dataclass
class MatchResult:
    """Output of the HMM map matcher for a single timestep.

    This is the object consumed by:
    - Role 3: snapped position can be used as a MAP_SNAP constraint event.
    - Role 6: logged for drift evaluation.

    Parameters
    ----------
    timestamp : float
        POSIX timestamp.
    u : int
        OSM start node of the matched edge.
    v : int
        OSM end node of the matched edge.
    snapped_lat : float
        Latitude of the snapped position on the matched edge.
    snapped_lon : float
        Longitude of the snapped position on the matched edge.
    confidence : float
        Match confidence in [0.0, 1.0].
    method : str
        ``"viterbi"`` or ``"nearest"`` (nearest-snap fallback).
    candidates_considered : int
        Number of candidates evaluated at this step.
    """

    timestamp: float
    u: int
    v: int
    snapped_lat: float
    snapped_lon: float
    confidence: float
    method: str = "nearest"
    candidates_considered: int = 0


class HMMMapMatcher:
    """HMM/Viterbi map matcher over candidate sequences.

    In MVP mode, falls back to nearest-candidate snapping.  The full Viterbi
    path decoding will be wired in as a stretch goal.

    Parameters
    ----------
    config_path : str
        Path to ``configs/role4.yaml``.

    Examples
    --------
    >>> matcher = HMMMapMatcher()
    >>> result = matcher.update(timestamp=1.0, candidates=candidates)
    >>> result.method
    'nearest'
    """

    def __init__(self, config_path: str = "configs/role4.yaml") -> None:
        import yaml
        with open(config_path) as f:
            cfg = yaml.safe_load(f)
        self._cfg = cfg["map_matching"]
        logger.debug("HMMMapMatcher initialised (MVP: nearest-snap fallback)")

    def update(
        self,
        timestamp: float,
        candidates: List[RoadCandidate],
    ) -> Optional[MatchResult]:
        """Process a new list of candidates and return a match result.

        MVP behaviour: returns the highest-scoring candidate (nearest + heading
        consistent) with ``method="nearest"``.

        Full Viterbi behaviour (TODO): maintains a rolling path buffer and
        runs forward Viterbi to find the globally most likely road sequence.

        Parameters
        ----------
        timestamp : float
            POSIX timestamp.
        candidates : list of RoadCandidate
            Output of :meth:`~maps.candidates.CandidateGenerator.get_candidates`.

        Returns
        -------
        MatchResult or None
            None if no candidates are available.
        """
        if not candidates:
            logger.debug("HMMMapMatcher: no candidates at t=%.2f", timestamp)
            return None

        # MVP: take the best-scored candidate (already sorted by CandidateGenerator)
        best = candidates[0]
        result = MatchResult(
            timestamp=timestamp,
            u=best.u,
            v=best.v,
            snapped_lat=best.mid_lat,
            snapped_lon=best.mid_lon,
            confidence=best.score,
            method="nearest",
            candidates_considered=len(candidates),
        )

        logger.debug(
            "HMMMapMatcher: matched to edge (%d→%d) conf=%.3f at t=%.2f",
            result.u, result.v, result.confidence, timestamp,
        )
        return result

    def reset(self) -> None:
        """Reset the matcher state (clear path buffer).

        Call this when the GNSS fix is re-acquired after an outage so that
        the Viterbi path does not carry over stale hypotheses.
        """
        logger.info("HMMMapMatcher: state reset")
