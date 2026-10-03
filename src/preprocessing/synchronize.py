"""Timestamp alignment and 10 Hz resampling utilities."""

from __future__ import annotations

import numpy as np
import pandas as pd


TARGET_HZ = 10.0
TARGET_PERIOD_S = 1.0 / TARGET_HZ


def validate_time_column(
    df: pd.DataFrame,
    time_column: str,
) -> None:
    """Validate that a trip's time column is numeric and ordered."""

    if time_column not in df.columns:
        raise KeyError(f"Missing time column: {time_column}")

    time = pd.to_numeric(df[time_column], errors="coerce")

    if time.isna().any():
        raise ValueError(
            f"{time_column} contains missing/non-numeric values."
        )

    if time.duplicated().any():
        raise ValueError(
            f"{time_column} contains duplicate timestamps."
        )

    if not time.is_monotonic_increasing:
        raise ValueError(
            f"{time_column} is not monotonically increasing."
        )


def resample_to_10hz(
    df: pd.DataFrame,
    time_column: str = "time_since_start_s",
) -> pd.DataFrame:
    """
    Resample one already-synchronized trip to exactly 10 Hz.

    The input must contain a numeric time column in seconds.
    Numeric sensor columns are linearly interpolated onto the
    100 ms grid. Non-numeric metadata columns use the nearest
    available sample.

    No additional synchronization offset is applied.
    """

    validate_time_column(df, time_column)

    result = df.copy()

    result[time_column] = pd.to_numeric(
        result[time_column],
        errors="coerce",
    )

    start = float(result[time_column].iloc[0])
    end = float(result[time_column].iloc[-1])

    target_time = np.arange(
        start,
        end + TARGET_PERIOD_S / 2.0,
        TARGET_PERIOD_S,
    )

    source_time = result[time_column].to_numpy()

    resampled = pd.DataFrame(
        {time_column: target_time}
    )

    numeric_columns = result.select_dtypes(
        include=[np.number]
    ).columns.tolist()

    numeric_columns = [
        column
        for column in numeric_columns
        if column != time_column
    ]

    for column in numeric_columns:
        values = pd.to_numeric(
            result[column],
            errors="coerce",
        )

        valid = values.notna() & np.isfinite(values)

        if valid.sum() < 2:
            resampled[column] = np.nan
            continue

        resampled[column] = np.interp(
            target_time,
            source_time[valid.to_numpy()],
            values[valid].to_numpy(),
        )

    metadata_columns = [
        column
        for column in result.columns
        if column not in numeric_columns
        and column != time_column
    ]

    source_index = np.searchsorted(
        source_time,
        target_time,
        side="left",
    )

    source_index = np.clip(
        source_index,
        0,
        len(result) - 1,
    )

    for column in metadata_columns:
        resampled[column] = result.iloc[
            source_index
        ][column].to_numpy()

    return resampled.reset_index(drop=True)

