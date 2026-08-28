"""Geographic coordinate transformations for Percorsa."""

import numpy as np
import pandas as pd


EARTH_RADIUS_M = 6_378_137.0


def add_local_coordinates(
    df: pd.DataFrame,
    latitude_column: str = "latitude",
    longitude_column: str = "longitude",
) -> pd.DataFrame:
    """
    Add local East/North coordinates in metres.

    The first valid GPS position in the dataframe is used as the
    local origin. Longitude is converted to East and latitude to
    North using a local tangent-plane approximation.

    This function does not modify the original latitude/longitude
    columns.
    """
    result = df.copy()

    if latitude_column not in result.columns:
        raise KeyError(f"Missing column: {latitude_column}")

    if longitude_column not in result.columns:
        raise KeyError(f"Missing column: {longitude_column}")

    latitude = pd.to_numeric(
        result[latitude_column],
        errors="coerce",
    )

    longitude = pd.to_numeric(
        result[longitude_column],
        errors="coerce",
    )

    valid = latitude.notna() & longitude.notna()

    if not valid.any():
        raise ValueError("No valid GPS coordinates found.")

    origin_lat = np.deg2rad(latitude.loc[valid].iloc[0])
    origin_lon = np.deg2rad(longitude.loc[valid].iloc[0])

    lat_rad = np.deg2rad(latitude)
    lon_rad = np.deg2rad(longitude)

    result["east"] = (
        (lon_rad - origin_lon)
        * np.cos(origin_lat)
        * EARTH_RADIUS_M
    )

    result["north"] = (
        (lat_rad - origin_lat)
        * EARTH_RADIUS_M
    )

    return result

