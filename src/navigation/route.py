"""
route.py
========
Route geometry representation for route-aware GNSS-denied navigation.

All positions are stored in local East-North-Up (ENU) metric coordinates
in metres, using the same LatLonOrigin as the ESKF nominal state. This
avoids mixing WGS-84 degree values with ESKF metre-based position states.

Usage
-----
>>> from src.preprocessing.coordinates import LatLonOrigin
>>> origin = LatLonOrigin(lat=19.051, lon=72.894)
>>> route = Route.from_latlon_polyline(
...     waypoints=[(19.051, 72.894), (19.052, 72.894), (19.053, 72.895)],
...     origin=origin,
... )
>>> route.num_segments
2
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional, Tuple

import numpy as np

try:
    from src.preprocessing.coordinates import (
        LatLonOrigin,
        latlon_to_enu,
        bearing_deg,
    )
except ImportError:
    from coordinate_transform import (  # type: ignore[import]
        LatLonOrigin,
        latlon_to_enu,
        bearing_deg,
    )


class ManeuverType(Enum):
    """Types of route maneuvers."""
    STRAIGHT = "straight"
    TURN_SLIGHT_LEFT = "turn_slight_left"
    TURN_LEFT = "turn_left"
    TURN_SHARP_LEFT = "turn_sharp_left"
    TURN_SLIGHT_RIGHT = "turn_slight_right"
    TURN_RIGHT = "turn_right"
    TURN_SHARP_RIGHT = "turn_sharp_right"
    U_TURN = "u_turn"
    MERGE = "merge"
    ROUNDABOUT = "roundabout"
    DESTINATION = "destination"
    DEPART = "depart"
    UNKNOWN = "unknown"

    @staticmethod
    def from_osrm_modifier(modifier: str) -> "ManeuverType":
        """Convert OSRM/Mapbox step maneuver modifier string to ManeuverType."""
        _MAP = {
            "straight": ManeuverType.STRAIGHT,
            "slight left": ManeuverType.TURN_SLIGHT_LEFT,
            "left": ManeuverType.TURN_LEFT,
            "sharp left": ManeuverType.TURN_SHARP_LEFT,
            "slight right": ManeuverType.TURN_SLIGHT_RIGHT,
            "right": ManeuverType.TURN_RIGHT,
            "sharp right": ManeuverType.TURN_SHARP_RIGHT,
            "uturn": ManeuverType.U_TURN,
        }
        return _MAP.get(modifier.lower(), ManeuverType.UNKNOWN)


@dataclass
class RoutePoint:
    """A single point on the route polyline.

    Parameters
    ----------
    lat : float
        WGS-84 latitude in degrees.
    lon : float
        WGS-84 longitude in degrees.
    east : float
        Local ENU East coordinate in metres.
    north : float
        Local ENU North coordinate in metres.
    cumulative_dist_m : float
        Cumulative arc-length distance from route start to this point (metres).
    segment_bearing_deg : float
        Bearing from this point to the next point, degrees clockwise from North.
        0.0 for the last point (no outgoing segment).
    """

    lat: float
    lon: float
    east: float
    north: float
    cumulative_dist_m: float
    segment_bearing_deg: float = 0.0

    @property
    def enu_2d(self) -> np.ndarray:
        """ENU 2D position vector [east, north]."""
        return np.array([self.east, self.north], dtype=np.float64)


@dataclass
class RouteSegment:
    """A directed line segment connecting two adjacent route points.

    The segment lies in the 2-D ENU plane (no altitude modelling for urban
    dead-reckoning purposes). All bearing/normal/tangent fields are derived
    purely from ENU east/north displacements.

    Parameters
    ----------
    index : int
        Zero-based index of this segment in the route.
    start : RoutePoint
        Segment start point.
    end : RoutePoint
        Segment end point.
    """

    index: int
    start: RoutePoint
    end: RoutePoint

    def __post_init__(self) -> None:
        # Precompute derived geometry
        de = self.end.east - self.start.east
        dn = self.end.north - self.start.north
        self.length_m: float = math.hypot(de, dn)

        if self.length_m > 1e-9:
            self.unit_tangent: np.ndarray = np.array(
                [de / self.length_m, dn / self.length_m], dtype=np.float64
            )
        else:
            self.unit_tangent = np.array([1.0, 0.0], dtype=np.float64)

        # Normal is 90° CCW from tangent (points "left" of travel direction)
        self.unit_normal: np.ndarray = np.array(
            [-self.unit_tangent[1], self.unit_tangent[0]], dtype=np.float64
        )

        # Bearing: clockwise from North.  atan2(east, north) gives CW-from-N.
        self.bearing_deg: float = (
            math.degrees(math.atan2(de, dn)) % 360.0
        )

        # ENU bearing to numpy heading angle (counter-clockwise from East)
        # heading_rad is used for dot-product comparisons with velocity vectors
        self.heading_rad: float = math.atan2(dn, de)

    def project_point(self, east: float, north: float) -> Tuple[float, float, float]:
        """Project a 2D ENU point onto this segment.

        Parameters
        ----------
        east, north : float
            Query point in local ENU metres.

        Returns
        -------
        along_m : float
            Signed along-track distance from segment start (metres).
            Clamped to [0, length_m].
        lateral_m : float
            Signed lateral (perpendicular) distance from the segment.
            Positive = left of travel direction, Negative = right.
        frac : float
            Fractional position along segment [0, 1].
        """
        vec = np.array([east - self.start.east, north - self.start.north], dtype=np.float64)
        along_m = float(np.dot(vec, self.unit_tangent))
        lateral_m = float(np.dot(vec, self.unit_normal))
        frac = float(np.clip(along_m / max(self.length_m, 1e-9), 0.0, 1.0))
        along_m_clamped = float(np.clip(along_m, 0.0, self.length_m))
        return along_m_clamped, lateral_m, frac

    def closest_point(self, east: float, north: float) -> Tuple[float, float]:
        """Return the closest point on the segment to query (east, north)."""
        along_m, _lat, frac = self.project_point(east, north)
        cp_east = self.start.east + frac * (self.end.east - self.start.east)
        cp_north = self.start.north + frac * (self.end.north - self.start.north)
        return cp_east, cp_north


@dataclass
class RouteManeuver:
    """A route maneuver (turn, merge, destination) with positional and bearing info.

    Parameters
    ----------
    maneuver_type : ManeuverType
        Semantic maneuver classification.
    east : float
        Local ENU east coordinate of the maneuver point (metres).
    north : float
        Local ENU north coordinate of the maneuver point (metres).
    incoming_bearing_deg : float
        Incoming approach heading at maneuver, degrees CW from North.
    outgoing_bearing_deg : float
        Departure heading after maneuver, degrees CW from North.
    cumulative_dist_m : float
        Cumulative route distance to this maneuver from route start (metres).
    instruction : str
        Human-readable turn instruction.
    segment_index : int
        Index of the route segment just BEFORE this maneuver.
    """

    maneuver_type: ManeuverType
    east: float
    north: float
    incoming_bearing_deg: float
    outgoing_bearing_deg: float
    cumulative_dist_m: float
    instruction: str = ""
    segment_index: int = 0

    @property
    def enu_2d(self) -> np.ndarray:
        return np.array([self.east, self.north], dtype=np.float64)

    @property
    def is_turn(self) -> bool:
        return self.maneuver_type not in (
            ManeuverType.STRAIGHT,
            ManeuverType.DEPART,
            ManeuverType.DESTINATION,
            ManeuverType.UNKNOWN,
        )

    @property
    def heading_change_deg(self) -> float:
        """Signed heading change at the maneuver, degrees in (-180, 180]."""
        diff = (self.outgoing_bearing_deg - self.incoming_bearing_deg + 180.0) % 360.0 - 180.0
        return diff


@dataclass
class Route:
    """Complete route geometry in local ENU metric coordinates.

    Created via :meth:`from_latlon_polyline` from a list of (lat, lon) waypoints
    and an explicit ENU origin that must match the ESKF local frame.

    Attributes
    ----------
    origin : LatLonOrigin
        ENU frame origin used for ALL coordinate conversions in this route.
    points : List[RoutePoint]
        Ordered route points with ENU coordinates and cumulative distances.
    segments : List[RouteSegment]
        Ordered directed segments between consecutive route points.
    maneuvers : List[RouteManeuver]
        Ordered maneuver events along the route.
    total_distance_m : float
        Total route arc-length in metres.
    """

    origin: LatLonOrigin
    points: List[RoutePoint]
    segments: List[RouteSegment]
    maneuvers: List[RouteManeuver] = field(default_factory=list)
    total_distance_m: float = 0.0

    @property
    def num_points(self) -> int:
        return len(self.points)

    @property
    def num_segments(self) -> int:
        return len(self.segments)

    def latlon_to_enu(self, lat: float, lon: float) -> Tuple[float, float]:
        """Convert a lat/lon point to ENU using this route's origin."""
        return latlon_to_enu(lat, lon, self.origin)

    def segment_bearing_at_progress(self, progress_m: float) -> float:
        """Return route bearing in degrees for a given cumulative route distance.

        Parameters
        ----------
        progress_m : float
            Cumulative route distance in metres.

        Returns
        -------
        float
            Bearing in degrees CW from North.
        """
        if not self.segments:
            return 0.0
        # Find the segment that contains progress_m
        for seg in self.segments:
            if seg.start.cumulative_dist_m <= progress_m <= seg.end.cumulative_dist_m:
                return seg.bearing_deg
        # Clamp to last segment bearing
        return self.segments[-1].bearing_deg

    def nearest_segment(
        self,
        east: float,
        north: float,
        search_window_m: float = 50.0,
        min_index: int = 0,
        max_index: Optional[int] = None,
    ) -> Optional[RouteSegment]:
        """Find nearest segment within a bounded window around current position.

        Does NOT search the entire route — only segments within
        ``search_window_m`` of (east, north) and within the specified index
        window. This prevents backward jumps during route progress tracking.

        Parameters
        ----------
        east, north : float
            Query position in ENU metres.
        search_window_m : float
            Maximum perpendicular distance for a candidate segment.
        min_index : int
            Minimum segment index to consider (prevents backward jumps).
        max_index : int, optional
            Maximum segment index to consider.

        Returns
        -------
        RouteSegment or None
            Nearest qualifying segment, or None if no segment qualifies.
        """
        if max_index is None:
            max_index = len(self.segments) - 1
        max_index = min(max_index, len(self.segments) - 1)

        best_seg: Optional[RouteSegment] = None
        best_dist = float("inf")

        for seg in self.segments[min_index : max_index + 1]:
            _along, lateral, _frac = seg.project_point(east, north)
            perp_dist = abs(lateral)
            if perp_dist < search_window_m and perp_dist < best_dist:
                best_dist = perp_dist
                best_seg = seg

        return best_seg

    def distance_to_next_maneuver(self, progress_m: float) -> Tuple[float, Optional[RouteManeuver]]:
        """Return distance to the next upcoming maneuver from current progress.

        Parameters
        ----------
        progress_m : float
            Current cumulative route distance in metres.

        Returns
        -------
        dist_m : float
            Distance to next maneuver in metres. Returns 0.0 if no maneuvers remain.
        maneuver : RouteManeuver or None
            The next upcoming maneuver, or None.
        """
        for man in self.maneuvers:
            if man.cumulative_dist_m > progress_m + 1.0:
                return man.cumulative_dist_m - progress_m, man
        return 0.0, None

    @classmethod
    def from_latlon_polyline(
        cls,
        waypoints: List[Tuple[float, float]],
        origin: LatLonOrigin,
        maneuver_data: Optional[List[dict]] = None,
    ) -> "Route":
        """Build a Route from a list of (lat, lon) waypoints.

        Parameters
        ----------
        waypoints : list of (lat, lon) tuples
            Route polyline in geographic coordinates. Minimum 2 points.
        origin : LatLonOrigin
            Local ENU frame origin. Must match the ESKF origin.
        maneuver_data : list of dict, optional
            Optional list of maneuver descriptors. Each dict may contain:
            {
                "lat": float, "lon": float,
                "type": str (ManeuverType name or OSRM modifier),
                "incoming_bearing_deg": float,
                "outgoing_bearing_deg": float,
                "instruction": str,
            }

        Returns
        -------
        Route
            Complete route with ENU-projected points, segments, and maneuvers.

        Raises
        ------
        ValueError
            If fewer than 2 waypoints are provided.
        """
        if len(waypoints) < 2:
            raise ValueError("Route requires at least 2 waypoints.")

        # Project all waypoints to ENU
        points: List[RoutePoint] = []
        cumulative = 0.0
        enu_coords = [latlon_to_enu(lat, lon, origin) for lat, lon in waypoints]

        for i, (lat, lon) in enumerate(waypoints):
            east, north = enu_coords[i]
            if i > 0:
                prev_e, prev_n = enu_coords[i - 1]
                cumulative += math.hypot(east - prev_e, north - prev_n)

            # bearing to next point (0.0 for last point)
            if i < len(waypoints) - 1:
                next_e, next_n = enu_coords[i + 1]
                de = next_e - east
                dn = next_n - north
                seg_bearing = (math.degrees(math.atan2(de, dn)) % 360.0)
            else:
                seg_bearing = 0.0

            points.append(RoutePoint(
                lat=lat,
                lon=lon,
                east=east,
                north=north,
                cumulative_dist_m=cumulative,
                segment_bearing_deg=seg_bearing,
            ))

        total_distance = cumulative

        # Build segments between consecutive points
        segments: List[RouteSegment] = []
        for i in range(len(points) - 1):
            segments.append(RouteSegment(index=i, start=points[i], end=points[i + 1]))

        # Build maneuvers
        maneuvers: List[RouteManeuver] = []
        if maneuver_data:
            for md in maneuver_data:
                m_lat = float(md.get("lat", 0.0))
                m_lon = float(md.get("lon", 0.0))
                m_east, m_north = latlon_to_enu(m_lat, m_lon, origin)

                # Determine segment index
                seg_idx = 0
                for i, seg in enumerate(segments):
                    if seg.start.cumulative_dist_m <= (
                        _approx_progress_at(m_east, m_north, segments)
                    ):
                        seg_idx = i

                # Resolve maneuver type
                type_str = str(md.get("type", "unknown"))
                try:
                    mtype = ManeuverType[type_str.upper()]
                except KeyError:
                    mtype = ManeuverType.from_osrm_modifier(type_str)

                # Cumulative distance to maneuver
                cum_dist = _approx_progress_at(m_east, m_north, segments)

                maneuvers.append(RouteManeuver(
                    maneuver_type=mtype,
                    east=m_east,
                    north=m_north,
                    incoming_bearing_deg=float(md.get("incoming_bearing_deg", 0.0)),
                    outgoing_bearing_deg=float(md.get("outgoing_bearing_deg", 0.0)),
                    cumulative_dist_m=cum_dist,
                    instruction=str(md.get("instruction", "")),
                    segment_index=seg_idx,
                ))
            # Sort maneuvers by cumulative distance
            maneuvers.sort(key=lambda m: m.cumulative_dist_m)

        return cls(
            origin=origin,
            points=points,
            segments=segments,
            maneuvers=maneuvers,
            total_distance_m=total_distance,
        )


def _approx_progress_at(east: float, north: float, segments: List[RouteSegment]) -> float:
    """Estimate cumulative route distance for an ENU point by closest segment projection."""
    best_along = 0.0
    best_dist = float("inf")
    for seg in segments:
        along, lateral, _frac = seg.project_point(east, north)
        dist = abs(lateral)
        if dist < best_dist:
            best_dist = dist
            best_along = seg.start.cumulative_dist_m + along
    return best_along
