"""
coordinate_transform.py
=======================
Shared WGS84 lat/lon ↔ local East-North-Up (ENU) metric frame utilities.

Cross-role dependency
---------------------
This module is the **agreed coordinate frame boundary** between:
  - Role 4  (Maps, Vehicle Constraints & GNSS Integrity) — uses ENU for
             candidate distance calculations and jump-distance checks.
  - Role 3  (INS/ESKF Engineer) — runs its filter in local ENU; must
             project GNSS fixes through this same transform before fusion.
  - Role 6  (Integration/Eval Lead) — converts logged lat/lon back to ENU
             when computing drift metrics.

Contract
--------
- Frame: East-North-Up (ENU), right-handed.
- Origin: caller-supplied reference point (lat0, lon0, alt0=0).
- Units: metres.
- No altitude modelling in MVP (alt0 = 0, planar approximation is fine for
  urban-scale dead-reckoning over distances < 10 km).

Usage
-----
>>> origin = LatLonOrigin(lat=19.051, lon=72.894)
>>> e, n = latlon_to_enu(19.0515, 72.8945, origin)
>>> lat, lon = enu_to_latlon(e, n, origin)
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Tuple

# WGS84 constants
_WGS84_A: float = 6_378_137.0          # semi-major axis, metres
_WGS84_F: float = 1.0 / 298.257223563  # flattening
_WGS84_E2: float = 2.0 * _WGS84_F - _WGS84_F ** 2  # eccentricity squared


@dataclass(frozen=True)
class LatLonOrigin:
    """Immutable local ENU frame origin.

    Parameters
    ----------
    lat : float
        Reference latitude in decimal degrees (WGS84).
    lon : float
        Reference longitude in decimal degrees (WGS84).
    alt : float
        Reference altitude in metres above WGS84 ellipsoid. Default 0.
    """

    lat: float
    lon: float
    alt: float = field(default=0.0)

    def __post_init__(self) -> None:
        if not (-90.0 <= self.lat <= 90.0):
            raise ValueError(f"Latitude {self.lat} out of range [-90, 90].")
        if not (-180.0 <= self.lon <= 180.0):
            raise ValueError(f"Longitude {self.lon} out of range [-180, 180].")


def _radius_of_curvature(lat_rad: float) -> Tuple[float, float]:
    """Return (M, N) — meridional and prime-vertical radii (metres)."""
    sin_lat = math.sin(lat_rad)
    denom = math.sqrt(1.0 - _WGS84_E2 * sin_lat ** 2)
    M = _WGS84_A * (1.0 - _WGS84_E2) / denom ** 3  # meridional
    N = _WGS84_A / denom                              # prime vertical
    return M, N


def latlon_to_enu(
    lat: float,
    lon: float,
    origin: LatLonOrigin,
) -> Tuple[float, float]:
    """Convert WGS84 lat/lon to local ENU (East, North) in metres.

    This is a flat-Earth (planar) approximation valid to < 1 m error
    within ~5 km of the origin — adequate for urban dead-reckoning.

    Parameters
    ----------
    lat : float
        Target latitude, decimal degrees.
    lon : float
        Target longitude, decimal degrees.
    origin : LatLonOrigin
        Local frame origin.

    Returns
    -------
    east_m : float
        East displacement in metres.
    north_m : float
        North displacement in metres.
    """
    lat0_rad = math.radians(origin.lat)
    M, N = _radius_of_curvature(lat0_rad)

    dlat = math.radians(lat - origin.lat)
    dlon = math.radians(lon - origin.lon)

    north_m = M * dlat
    east_m = N * math.cos(lat0_rad) * dlon
    return east_m, north_m


def enu_to_latlon(
    east_m: float,
    north_m: float,
    origin: LatLonOrigin,
) -> Tuple[float, float]:
    """Convert local ENU (East, North) in metres back to WGS84 lat/lon.

    Inverse of :func:`latlon_to_enu`.

    Parameters
    ----------
    east_m : float
        East displacement in metres.
    north_m : float
        North displacement in metres.
    origin : LatLonOrigin
        Local frame origin (same one used for the forward transform).

    Returns
    -------
    lat : float
        Latitude in decimal degrees.
    lon : float
        Longitude in decimal degrees.
    """
    lat0_rad = math.radians(origin.lat)
    M, N = _radius_of_curvature(lat0_rad)

    dlat_rad = north_m / M
    dlon_rad = east_m / (N * math.cos(lat0_rad))

    lat = origin.lat + math.degrees(dlat_rad)
    lon = origin.lon + math.degrees(dlon_rad)
    return lat, lon


def haversine_distance(
    lat1: float, lon1: float,
    lat2: float, lon2: float,
) -> float:
    """Great-circle distance between two WGS84 points (metres).

    Parameters
    ----------
    lat1, lon1 : float
        First point in decimal degrees.
    lat2, lon2 : float
        Second point in decimal degrees.

    Returns
    -------
    float
        Distance in metres.
    """
    R = _WGS84_A  # use semi-major axis as Earth radius approximation
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2.0) ** 2 + \
        math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    return R * 2.0 * math.asin(math.sqrt(a))


def bearing_deg(
    lat1: float, lon1: float,
    lat2: float, lon2: float,
) -> float:
    """Initial bearing from point 1 to point 2, in degrees [0, 360).

    Parameters
    ----------
    lat1, lon1 : float
        Start point, decimal degrees.
    lat2, lon2 : float
        End point, decimal degrees.

    Returns
    -------
    float
        Bearing in degrees clockwise from North.
    """
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dlambda = math.radians(lon2 - lon1)
    x = math.sin(dlambda) * math.cos(phi2)
    y = math.cos(phi1) * math.sin(phi2) - \
        math.sin(phi1) * math.cos(phi2) * math.cos(dlambda)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def angle_diff_deg(a: float, b: float) -> float:
    """Smallest signed difference between two headings in degrees.

    Returns a value in (-180, 180].

    Parameters
    ----------
    a, b : float
        Angles in degrees.

    Returns
    -------
    float
        ``a - b`` wrapped to (-180, 180].
    """
    diff = (a - b + 180.0) % 360.0 - 180.0
    return diff
