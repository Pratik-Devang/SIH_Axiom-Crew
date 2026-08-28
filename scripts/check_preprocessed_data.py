from pathlib import Path
import json
import pandas as pd


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PROCESSED_DIR = PROJECT_ROOT / "data" / "processed" / "io_vnbd"
REPORT_DIR = PROJECT_ROOT / "reports" / "io_vnbd"
REPORT_PATH = REPORT_DIR / "data_quality_report.json"


REQUIRED_COLUMNS = {
    "latitude_deg",
    "longitude_deg",
    "altitude_m",
    "speed_kmh",
    "gps_accuracy_m",
    "gps_orientation_deg",
    "gps_satellites",
    "time_since_start_ms",
    "timestamp",
    "accelerometer_x_ms2",
    "accelerometer_y_ms2",
    "accelerometer_z_ms2",
    "gravity_x_ms2",
    "gravity_y_ms2",
    "gravity_z_ms2",
    "magnetic_x_uT",
    "magnetic_y_uT",
    "magnetic_z_uT",
    "orientation_yaw_deg",
    "orientation_pitch_deg",
    "orientation_roll_deg",
    "source_file",
    "schema_variant",
}

def check_file(path):
    result = {
        "file": str(path.relative_to(PROJECT_ROOT)),
        "readable": False,
        "rows": 0,
        "missing_columns": [],
        "invalid_timestamps": 0,
        "backward_timestamp_jumps": 0,
        "invalid_coordinates": 0,
        "missing_required_values": 0,
        "status": "FAILED",
    }

    try:
        df = pd.read_parquet(path)
        result["readable"] = True
        result["rows"] = len(df)

        common_required = REQUIRED_COLUMNS - {
            "orientation_yaw_deg",
            "gyroscope_yaw_rads",
            "gyroscope_pitch_rads",
            "gyroscope_roll_rads",
        }

        missing = common_required - set(df.columns)

        has_ypr_gyro = {
            "gyroscope_yaw_rads",
            "gyroscope_pitch_rads",
            "gyroscope_roll_rads",
        }.issubset(df.columns)

        has_xyz_gyro = {
            "gyroscope_x_rads",
            "gyroscope_y_rads",
            "gyroscope_z_rads",
        }.issubset(df.columns)

        has_yaw_orientation = "orientation_yaw_deg" in df.columns
        has_azimuth_orientation = "orientation_azimuth_deg" in df.columns

        if not has_ypr_gyro and not has_xyz_gyro:
            missing.add("gyroscope_yaw/pitch/roll OR gyroscope_x/y/z")

        if not has_yaw_orientation and not has_azimuth_orientation:
            missing.add("orientation_yaw_deg OR orientation_azimuth_deg")

        result["missing_columns"] = sorted(missing)

        if "timestamp" in df.columns:
            timestamps = pd.to_datetime(
                df["timestamp"],
                errors="coerce"
            )

            result["invalid_timestamps"] = int(
                timestamps.isna().sum()
            )

            if len(timestamps) > 1:
                valid = timestamps.dropna()

                result["backward_timestamp_jumps"] = int(
                    (valid.diff().dropna() < pd.Timedelta(0)).sum()
                )

        if (
            "latitude_deg" in df.columns
            and "longitude_deg" in df.columns
        ):
            lat_invalid = ~df["latitude_deg"].between(-90, 90)
            lon_invalid = ~df["longitude_deg"].between(-180, 180)

            result["invalid_coordinates"] = int(
                (lat_invalid | lon_invalid).sum()
            )

        required_present = [
            c for c in REQUIRED_COLUMNS
            if c in df.columns
        ]

        if required_present:
            result["missing_required_values"] = int(
                df[required_present].isna().sum().sum()
            )

        if (
            result["readable"]
            and not result["missing_columns"]
            and result["invalid_timestamps"] == 0
            and result["backward_timestamp_jumps"] == 0
            and result["invalid_coordinates"] == 0
        ):
            result["status"] = "PASSED"

    except Exception as exc:
        result["error"] = str(exc)

    return result


def main():
    files = sorted(PROCESSED_DIR.glob("*.parquet"))

    results = [check_file(path) for path in files]

    report = {
        "dataset": "IO-VNBD",
        "processed_directory": str(
            PROCESSED_DIR.relative_to(PROJECT_ROOT)
        ),
        "files_checked": len(files),
        "files_passed": sum(
            r["status"] == "PASSED" for r in results
        ),
        "files_failed": sum(
            r["status"] == "FAILED" for r in results
        ),
        "total_rows": sum(r["rows"] for r in results),
        "checks": results,
    }

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(
        json.dumps(report, indent=2),
        encoding="utf-8",
    )

    print("=" * 70)
    print("POST-PREPROCESSING DATA QUALITY REPORT")
    print("=" * 70)
    print(f"Files checked : {report['files_checked']}")
    print(f"Files passed  : {report['files_passed']}")
    print(f"Files failed  : {report['files_failed']}")
    print(f"Total rows    : {report['total_rows']}")
    print(f"Report        : {REPORT_PATH}")
    print("=" * 70)


if __name__ == "__main__":
    main()