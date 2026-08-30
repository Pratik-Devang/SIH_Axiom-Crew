"""
candidates.py
=============
Nearest-road candidate generation for map matching.

Given a query position (lat, lon) and optional heading, returns the top-K
nearest road segments with heading consistency filtering.

Uses ``scipy.spatial.cKDTree`` (not ``rtree``) for KD-tree lookups — avoids
``rtree``/``libspatialindex`` install friction in hackathon environments.

Data contract (with hmm_matcher.py)
-------------------------------------
Output is a list of :class:`RoadCandidate` dataclasses.  The HMM matcher
consumes these lists (one per timestep) to run Viterbi decoding.

Role 5 note
-----------
This module runs server-side (Python).  On Android, the equivalent
nearest-edge lookup should be done against the lightweight JSON exported by
``OSMLoader.export_lightweight_json()``.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np
from scipy.spatial import cKDTree
import yaml

try:
    from src.preprocessing.coordinates import (
        LatLonOrigin,
        latlon_to_enu,
        angle_diff_deg,
    )
    from src.maps.osm_loader import OSMLoader
except ImportError:
    from coordinate_transform import (
        LatLonOrigin,
        latlon_to_enu,
        angle_diff_deg,
    )
    from maps.osm_loader import OSMLoader

logger = logging.getLogger(__name__)


def _load_config(config_path: str = "configs/role4.yaml") -> dict:
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


# ---------------------------------------------------------------------------
# RoadCandidate dataclass
# ---------------------------------------------------------------------------

@dataclass
class RoadCandidate:
    """A road segment candidate for a given query position.

    Parameters
    ----------
    u : int
        OSM node ID of the edge start.
    v : int
        OSM node ID of the edge end.
    distance_m : float
        Euclidean distance from query point to edge midpoint (metres).
    heading_deg : float
        Approximate road heading at the candidate segment (degrees, CW from N).
    heading_diff_deg : float
        Absolute angular difference between query heading and road heading.
        ``None`` if no query heading was supplied.
    mid_lat : float
        Latitude of the edge midpoint.
    mid_lon : float
        Longitude of the edge midpoint.
    score : float
        Combined distance + heading score in [0.0, 1.0].  Higher is better.
    """

    u: int
    v: int
    distance_m: float
    heading_deg: float
    heading_diff_deg: Optional[float]
    mid_lat: float
    mid_lon: float
    score: float


# ---------------------------------------------------------------------------
# Candidate Generator
# ---------------------------------------------------------------------------

class CandidateGenerator:
    """Generate nearest-road candidates using a cKDTree over edge midpoints.

    Must be initialised with a loaded :class:`~maps.osm_loader.OSMLoader`.
    Call :meth:`build_index` once after loading the graph; subsequent
    :meth:`get_candidates` calls are fast in-memory lookups.

    Parameters
    ----------
    loader : OSMLoader
        A loader with a graph already loaded.
    config_path : str or Path
        Path to ``configs/role4.yaml``.

    Examples
    --------
    >>> loader = OSMLoader()
    >>> loader.load_from_graphml("data/chembur_1km.graphml")
    >>> gen = CandidateGenerator(loader)
    >>> gen.build_index()
    >>> candidates = gen.get_candidates(19.051, 72.894, heading_deg=90.0)
    """

    def __init__(
        self,
        loader: OSMLoader,
        config_path: str = "configs/role4.yaml",
    ) -> None:
        cfg = _load_config(config_path)
        self._mm_cfg = cfg["map_matching"]
        self._loader = loader

        # Index will be built in build_index()
        self._tree: Optional[cKDTree] = None
        self._midpoints: List[Tuple] = []   # (u, v, mid_lat, mid_lon, heading)
        self._origin: Optional[LatLonOrigin] = None

        logger.debug("CandidateGenerator initialised")

    def build_index(self, origin: Optional[LatLonOrigin] = None) -> None:
        """Build the cKDTree index over edge midpoints.

        Must be called once before :meth:`get_candidates`.

        Parameters
        ----------
        origin : LatLonOrigin, optional
            ENU frame origin.  If ``None``, uses the centroid of the graph
            bounding box as the origin.
        """
        midpoints = self._loader.get_edge_midpoints()
        if not midpoints:
            raise ValueError("Graph has no edges — cannot build KD-tree.")

        # Determine ENU origin
        if origin is None:
            all_lats = [m[2] for m in midpoints]
            all_lons = [m[3] for m in midpoints]
            origin = LatLonOrigin(
                lat=sum(all_lats) / len(all_lats),
                lon=sum(all_lons) / len(all_lons),
            )
        self._origin = origin

        # Project midpoints to ENU
        enu_coords = []
        for u, v, mid_lat, mid_lon, heading in midpoints:
            east, north = latlon_to_enu(mid_lat, mid_lon, origin)
            enu_coords.append((east, north))

        self._midpoints = midpoints
        self._tree = cKDTree(np.array(enu_coords, dtype=np.float64))
        logger.info(
            "CandidateGenerator: built KD-tree over %d edge midpoints "
            "(origin lat=%.4f lon=%.4f)",
            len(midpoints), origin.lat, origin.lon,
        )

    def get_candidates(
        self,
        lat: float,
        lon: float,
        heading_deg: Optional[float] = None,
        top_k: Optional[int] = None,
        radius_m: Optional[float] = None,
    ) -> List[RoadCandidate]:
        """Return the top-K nearest road candidates for a query position.

        Applies a heading consistency filter: candidates whose road heading
        differs from the query heading by more than ``max_heading_diff_deg``
        are removed before ranking.

        Parameters
        ----------
        lat : float
            Query latitude, decimal degrees.
        lon : float
            Query longitude, decimal degrees.
        heading_deg : float, optional
            Query heading in degrees (clockwise from North).  If ``None``,
            heading filter is disabled.
        top_k : int, optional
            Max candidates to return.  Defaults to config value.
        radius_m : float, optional
            Search radius override.  Defaults to config value.

        Returns
        -------
        list of RoadCandidate
            Sorted by descending ``score`` (best match first).

        Raises
        ------
        RuntimeError
            If :meth:`build_index` has not been called.
        """
        if self._tree is None:
            raise RuntimeError("Call build_index() before get_candidates().")

        top_k = top_k or self._mm_cfg["top_k_candidates"]
        radius_m = radius_m or self._mm_cfg["candidate_radius_m"]
        max_heading_diff = self._mm_cfg["max_heading_diff_deg"]
        dist_weight = self._mm_cfg["distance_weight"]
        head_weight = self._mm_cfg["heading_weight"]

        east, north = latlon_to_enu(lat, lon, self._origin)
        query_enu = np.array([east, north])

        # Query cKDTree: all points within radius_m
        indices = self._tree.query_ball_point(query_enu, r=radius_m)
        if not indices:
            logger.debug(
                "No candidates within %.0fm of (%.5f, %.5f)", radius_m, lat, lon
            )
            return []

        candidates: List[RoadCandidate] = []
        for idx in indices:
            u, v, mid_lat, mid_lon, road_heading = self._midpoints[idx]
            e_mid, n_mid = latlon_to_enu(mid_lat, mid_lon, self._origin)
            distance_m = math.hypot(east - e_mid, north - n_mid)

            # Heading filter
            if heading_deg is not None:
                hdiff = abs(angle_diff_deg(heading_deg, road_heading))
                # Also consider the reverse direction
                hdiff = min(hdiff, abs(180.0 - hdiff))
                if hdiff > max_heading_diff:
                    continue
            else:
                hdiff = None

            # Score: higher = better
            dist_score = max(0.0, 1.0 - distance_m / radius_m)
            if hdiff is not None:
                head_score = max(0.0, 1.0 - hdiff / max_heading_diff)
                score = dist_weight * dist_score + head_weight * head_score
            else:
                score = dist_score

            candidates.append(RoadCandidate(
                u=int(u),
                v=int(v),
                distance_m=distance_m,
                heading_deg=road_heading,
                heading_diff_deg=hdiff,
                mid_lat=mid_lat,
                mid_lon=mid_lon,
                score=score,
            ))

        candidates.sort(key=lambda c: c.score, reverse=True)
        result = candidates[:top_k]

        logger.debug(
            "get_candidates(%.5f, %.5f, hdg=%s): %d within radius, "
            "%d after heading filter, returning top %d",
            lat, lon,
            f"{heading_deg:.1f}°" if heading_deg is not None else "None",
            len(indices), len(candidates), len(result),
        )
        return result
