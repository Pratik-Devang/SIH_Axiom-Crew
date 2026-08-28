"""Prepare all IO-VNBD trips in the canonical Percorsa format."""

from pathlib import Path
import sys

import pandas as pd

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from src.preprocessing.synchronize import resample_to_10hz
from src.data.adapters.io_vnbd import (
    read_smartphone_file,
)

DATA_ROOT = (
    PROJECT_ROOT
    / "data"
    / "raw"
    / "io_vnbd"
    / "Synchronised V abd S datasets"
)

OUTPUT_ROOT = (
    PROJECT_ROOT
    / "data"
    / "processed"
    / "io_vnbd"
    / "trips"
)


def read_vehicle_file(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path, encoding="latin1")
    df.columns = df.columns.astype(str).str.strip()
    return df


def find_trip_ids():
    smartphone_ids = {
        p.stem[2:]
        for p in DATA_ROOT.rglob("S-*.csv")
    }

    vehicle_ids = {
        p.stem[2:]
        for p in DATA_ROOT.rglob("V-*.csv")
    }

    return sorted(smartphone_ids & vehicle_ids)


def find_file(prefix: str, trip_id: str) -> Path:
    matches = list(
        DATA_ROOT.rglob(f"{prefix}-{trip_id}.csv")
    )

    if not matches:
        raise FileNotFoundError(
            f"Could not find {prefix}-{trip_id}.csv"
        )

    return matches[0]


def prepare_trip(trip_id: str) -> pd.DataFrame:

    smartphone_path = find_file("S", trip_id)
    vehicle_path = find_file("V", trip_id)

    smartphone = read_smartphone_file(
        smartphone_path
    )

    vehicle = read_vehicle_file(
        vehicle_path
    )

    # The adapter has already normalized smartphone columns.
    required_smartphone = [
        "time_since_start_ms",
        "accelerometer_x_ms2",
        "accelerometer_y_ms2",
        "accelerometer_z_ms2",
        "latitude_deg",
        "longitude_deg",
    ]

    missing = [
        c for c in required_smartphone
        if c not in smartphone.columns
    ]

    if missing:
        raise ValueError(
            f"{trip_id}: missing normalized smartphone "
            f"columns: {missing}"
        )

    if "gyroscope_x_rads" not in smartphone.columns:
        raise ValueError(
            f"{trip_id}: smartphone schema does not "
            "provide X/Y/Z gyroscope columns."
        )

    required_vehicle = [
        "Indicated Vehicle Speed (km/hr)"
    ]

    missing_vehicle = [
        c for c in required_vehicle
        if c not in vehicle.columns
    ]

    if missing_vehicle:
        raise ValueError(
            f"{trip_id}: missing vehicle columns: "
            f"{missing_vehicle}"
        )

    # --------------------------------------------------------------
    # Align synchronized V/S data.
    #
    # Some IO-VNBD files differ by only 1-10 samples.
    # Use the common length rather than rejecting the entire trip.
    # --------------------------------------------------------------

    n = min(
        len(smartphone),
        len(vehicle),
    )

    smartphone = smartphone.iloc[:n].reset_index(drop=True)
    vehicle = vehicle.iloc[:n].reset_index(drop=True)

    result = pd.DataFrame(
        {
            "trip_id": trip_id,

            "time_since_start_s": (
                pd.to_numeric(
                    smartphone[
                        "time_since_start_ms"
                    ],
                    errors="coerce",
                ) / 1000.0
            ),

            "accel_x": pd.to_numeric(
                smartphone[
                    "accelerometer_x_ms2"
                ],
                errors="coerce",
            ),

            "accel_y": pd.to_numeric(
                smartphone[
                    "accelerometer_y_ms2"
                ],
                errors="coerce",
            ),

            "accel_z": pd.to_numeric(
                smartphone[
                    "accelerometer_z_ms2"
                ],
                errors="coerce",
            ),

            "gyro_x": pd.to_numeric(
                smartphone[
                    "gyroscope_x_rads"
                ],
                errors="coerce",
            ),

            "gyro_y": pd.to_numeric(
                smartphone[
                    "gyroscope_y_rads"
                ],
                errors="coerce",
            ),

            "gyro_z": pd.to_numeric(
                smartphone[
                    "gyroscope_z_rads"
                ],
                errors="coerce",
            ),

            "latitude": pd.to_numeric(
                smartphone[
                    "latitude_deg"
                ],
                errors="coerce",
            ),

            "longitude": pd.to_numeric(
                smartphone[
                    "longitude_deg"
                ],
                errors="coerce",
            ),

            "vehicle_speed": pd.to_numeric(
                vehicle[
                    "Indicated Vehicle Speed (km/hr)"
                ],
                errors="coerce",
            ),
        }
    )

    result = result.dropna(
        subset=["time_since_start_s"]
    ).reset_index(drop=True)

    # Remove duplicate timestamps instead of failing.
    result = (
        result
        .drop_duplicates(
            subset=["time_since_start_s"],
            keep="first",
        )
        .reset_index(drop=True)
    )

    result = resample_to_10hz(
        result,
        time_column="time_since_start_s",
    )

    result["trip_id"] = trip_id

    return result


def main():

    OUTPUT_ROOT.mkdir(
        parents=True,
        exist_ok=True,
    )

    trip_ids = find_trip_ids()

    print(
        f"Found {len(trip_ids)} synchronized trips."
    )
    print()

    successful = []
    failed = []

    for trip_id in trip_ids:

        try:

            trip = prepare_trip(trip_id)

            output_path = (
                OUTPUT_ROOT
                / f"{trip_id}.csv"
            )

            trip.to_csv(
                output_path,
                index=False,
            )

            duration = (
                trip["time_since_start_s"].iloc[-1]
                - trip["time_since_start_s"].iloc[0]
            )

            print(
                f"OK: {trip_id} | "
                f"rows={len(trip)} | "
                f"duration={duration:.2f}s"
            )

            successful.append(trip_id)

        except Exception as exc:

            print(
                f"FAILED: {trip_id} | {exc}"
            )

            failed.append(
                (trip_id, str(exc))
            )

    print()
    print("=" * 60)
    print("IO-VNBD PREPARATION SUMMARY")
    print("=" * 60)

    print(
        f"Successful trips: {len(successful)}"
    )

    print(
        f"Failed trips:     {len(failed)}"
    )

    print(
        f"Output directory: {OUTPUT_ROOT}"
    )

    if failed:

        print()
        print("Failed trips:")

        for trip_id, error in failed:
            print(
                f"  {trip_id}: {error}"
            )

    else:

        print()
        print(
            "ALL IO-VNBD TRIPS PREPARED SUCCESSFULLY."
        )


if __name__ == "__main__":
    main()