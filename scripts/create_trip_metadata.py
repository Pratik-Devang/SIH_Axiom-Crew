from pathlib import Path
import json
import pandas as pd


PARQUET_ROOT = Path("data/processed/io_vnbd")
OUTPUT_FILE = Path("trip_metadata.json")


def main():

    files = sorted(PARQUET_ROOT.glob("*.parquet"))

    trips = []

    for file_path in files:

        df = pd.read_parquet(
            file_path,
            columns=[
                "timestamp",
                "source_file",
                "schema_variant",
            ],
        )

        timestamps = pd.to_datetime(
            df["timestamp"],
            errors="coerce",
        )

        valid_timestamps = timestamps.dropna()

        trip = {
            "file": str(file_path),
            "source_file": (
                str(df["source_file"].iloc[0])
                if len(df) > 0
                else None
            ),
            "schema_variant": (
                str(df["schema_variant"].iloc[0])
                if len(df) > 0
                else None
            ),
            "rows": int(len(df)),
            "start_time": (
                valid_timestamps.min().isoformat()
                if len(valid_timestamps) > 0
                else None
            ),
            "end_time": (
                valid_timestamps.max().isoformat()
                if len(valid_timestamps) > 0
                else None
            ),
        }

        trips.append(trip)

    metadata = {
        "dataset": "IO-VNBD",
        "num_trips": len(trips),
        "trips": trips,
    }

    with open(
        OUTPUT_FILE,
        "w",
        encoding="utf-8",
    ) as f:

        json.dump(
            metadata,
            f,
            indent=2,
        )

    print("=" * 80)
    print("TRIP METADATA COMPLETE")
    print("=" * 80)
    print(f"Trips/files: {len(trips)}")
    print(f"Output: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()