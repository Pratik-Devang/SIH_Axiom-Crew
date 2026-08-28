from pathlib import Path
import json

import pandas as pd


PARQUET_ROOT = Path(
    "data/processed/io_vnbd"
)

REPORT_PATH = Path(
    "data/processed/io_vnbd/data_quality_report.json"
)


REQUIRED_COLUMNS = [
    "timestamp",
    "time_since_start_ms",
    "latitude_deg",
    "longitude_deg",
    "altitude_m",
    "speed_kmh",
    "gps_accuracy_m",
    "gps_satellites",
]


def validate_file(file_path):

    df = pd.read_parquet(file_path)

    report = {
        "file": str(file_path),
        "rows": len(df),
        "columns": len(df.columns),
        "missing_values": {},
        "invalid_timestamps": 0,
        "duplicate_timestamps": 0,
        "backward_timestamp_jumps": 0,
        "invalid_coordinates": 0,
        "missing_required_columns": [],
    }

    # Check required columns
    for column in REQUIRED_COLUMNS:

        if column not in df.columns:
            report["missing_required_columns"].append(
                column
            )

    # Missing values
    missing = df.isna().sum()

    for column, count in missing.items():

        if count > 0:
            report["missing_values"][column] = int(
                count
            )

    # Timestamp checks
    if "timestamp" in df.columns:

        timestamps = pd.to_datetime(
            df["timestamp"],
            errors="coerce"
        )

        report["invalid_timestamps"] = int(
            timestamps.isna().sum()
        )

        report["duplicate_timestamps"] = int(
            timestamps.duplicated().sum()
        )

        differences = timestamps.diff()

        report["backward_timestamp_jumps"] = int(
            (differences < pd.Timedelta(0)).sum()
        )

    # Coordinate checks
    if (
        "latitude_deg" in df.columns
        and "longitude_deg" in df.columns
    ):

        invalid_latitude = (
            (df["latitude_deg"] < -90)
            | (df["latitude_deg"] > 90)
        )

        invalid_longitude = (
            (df["longitude_deg"] < -180)
            | (df["longitude_deg"] > 180)
        )

        report["invalid_coordinates"] = int(
            (
                invalid_latitude
                | invalid_longitude
            ).sum()
        )

    return report


def main():

    print("=" * 80)
    print("IO-VNBD DATA VALIDATION")
    print("=" * 80)

    files = sorted(
        PARQUET_ROOT.glob("*.parquet")
    )

    print(
        f"\nFound {len(files)} Parquet files."
    )

    all_reports = []

    for file_path in files:

        print(
            f"Validating: {file_path.name}"
        )

        try:

            report = validate_file(
                file_path
            )

            all_reports.append(report)

        except Exception as error:

            print(
                f"ERROR: {file_path}"
            )

            print(
                f"Reason: {error}"
            )

    # Overall summary
    total_rows = sum(
        r["rows"]
        for r in all_reports
    )

    total_invalid_timestamps = sum(
        r["invalid_timestamps"]
        for r in all_reports
    )

    total_duplicate_timestamps = sum(
        r["duplicate_timestamps"]
        for r in all_reports
    )

    total_backward_jumps = sum(
        r["backward_timestamp_jumps"]
        for r in all_reports
    )

    total_invalid_coordinates = sum(
        r["invalid_coordinates"]
        for r in all_reports
    )

    final_report = {
        "dataset": "IO-VNBD",
        "files_validated": len(all_reports),
        "total_rows": total_rows,
        "total_invalid_timestamps":
            total_invalid_timestamps,
        "total_duplicate_timestamps":
            total_duplicate_timestamps,
        "total_backward_timestamp_jumps":
            total_backward_jumps,
        "total_invalid_coordinates":
            total_invalid_coordinates,
        "file_reports": all_reports,
    }

    REPORT_PATH.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    with open(
        REPORT_PATH,
        "w",
        encoding="utf-8"
    ) as f:

        json.dump(
            final_report,
            f,
            indent=2
        )

    print("\n" + "=" * 80)
    print("VALIDATION COMPLETE")
    print("=" * 80)

    print(
        f"Files validated: "
        f"{len(all_reports)}"
    )

    print(
        f"Total rows: "
        f"{total_rows}"
    )

    print(
        f"Invalid timestamps: "
        f"{total_invalid_timestamps}"
    )

    print(
        f"Duplicate timestamps: "
        f"{total_duplicate_timestamps}"
    )

    print(
        f"Backward timestamp jumps: "
        f"{total_backward_jumps}"
    )

    print(
        f"Invalid coordinates: "
        f"{total_invalid_coordinates}"
    )

    print(
        f"\nReport saved to:"
        f"\n{REPORT_PATH}"
    )


if __name__ == "__main__":
    main()