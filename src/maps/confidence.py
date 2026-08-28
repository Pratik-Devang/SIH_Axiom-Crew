"""
confidence.py
=============
Match confidence scoring for map-matched positions (stub).

MVP status
----------
In the MVP, confidence is computed inline in :class:`~maps.candidates.RoadCandidate`
using a simple distance + heading weighted score.  This module will house
the probabilistic scoring (Gaussian emission model) as the stretch goal.

Full implementation plan (post-MVP)
-------------------------------------
- Gaussian emission model: P(obs | edge) ∝ exp(-dist² / (2σ²))
- Incorporate road type prior (motorway > residential)
- Incorporate speed-limit consistency check
"""

from __future__ import annotations

import math
import logging
from typing import Optional

import yaml

try:
    from src.maps.candidates import RoadCandidate
except ImportError:
    from maps.candidates import RoadCandidate

logger = logging.getLogger(__name__)


def _load_config(config_path: str = "configs/role4.yaml") -> dict:
    with open(config_path) as f:
        return yaml.safe_load(f)


def gaussian_emission_score(
    distance_m: float,
    sigma_m: Optional[float] = None,
    config_path: str = "configs/role4.yaml",
) -> float:
    """Compute Gaussian emission probability for a candidate.

    P(observation | candidate) ∝ exp(-d² / (2σ²))
    Normalised to [0.0, 1.0] (max at d=0).

    Parameters
    ----------
    distance_m : float
        Observed distance from query point to candidate edge midpoint.
    sigma_m : float, optional
        Emission standard deviation in metres.  Defaults to config value.
    config_path : str
        Path to YAML config.

    Returns
    -------
    float
        Emission score in [0.0, 1.0].
    """
    if sigma_m is None:
        cfg = _load_config(config_path)
        sigma_m = cfg["map_matching"]["hmm_emission_sigma_m"]
    score = math.exp(-(distance_m ** 2) / (2.0 * sigma_m ** 2))
    return float(score)


def score_candidate(
    candidate: RoadCandidate,
    config_path: str = "configs/role4.yaml",
) -> float:
    """Compute a combined probabilistic score for a single candidate.

    Currently combines Gaussian emission with heading consistency.
    Will be extended with road-type priors in the stretch goal.

    Parameters
    ----------
    candidate : RoadCandidate
        The candidate to score.
    config_path : str
        Path to YAML config.

    Returns
    -------
    float
        Score in [0.0, 1.0].
    """
    cfg = _load_config(config_path)["map_matching"]
    sigma_m = cfg["hmm_emission_sigma_m"]

    emission = gaussian_emission_score(candidate.distance_m, sigma_m)

    if candidate.heading_diff_deg is not None:
        # Cosine-based heading score
        heading_score = math.cos(math.radians(candidate.heading_diff_deg)) ** 2
    else:
        heading_score = 1.0

    dist_w = cfg["distance_weight"]
    head_w = cfg["heading_weight"]
    score = dist_w * emission + head_w * heading_score
    return max(0.0, min(1.0, score))
