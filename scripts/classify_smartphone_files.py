from pathlib import Path
import pandas as pd


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():
    print("=" * 80)
    print("IO-VNBD SMARTPHONE SCHEMA VARIANTS")
    print("=" * 80)

    files = sorted(DATA_ROOT.rglob("S-*.csv"))

    variant_a = []
    variant_b = []
    other = []

    for file_path in files:
        try:
            df = pd.read_csv(
                file_path,
                nrows=1,
                encoding="latin1"
            )

            columns = {
                str(c).strip().lower()
                for c in df.columns
            }

            has_xyz_gyro = (
                "gyroscope x (rad/s)" in columns
                and "gyroscope y (rad/s)" in columns
                and "gyroscope z (rad/s)" in columns
            )

            has_ypr_gyro = (
                "gyroscope yaw (rad/s)" in columns
                and "gyroscope pitch (rad/s)" in columns
                and "gyroscope roll (rad/s)" in columns
            )

            has_azimuth = "orientation (azimuth) (â°)" in columns
            has_yaw = "orientation (yaw) (â°)" in columns

            if has_xyz_gyro and has_azimuth:
                variant_b.append(file_path)

            elif has_ypr_gyro and has_yaw:
                variant_a.append(file_path)

            else:
                other.append(file_path)

        except Exception as error:
            print(f"\nERROR: {file_path}")
            print(error)

    print("\n" + "-" * 80)
    print("SUMMARY")
    print("-" * 80)

    print(f"\nVariant A - Yaw/Pitch/Roll: {len(variant_a)} files")
    print(f"Variant B - X/Y/Z + Azimuth: {len(variant_b)} files")
    print(f"Other/unknown: {len(other)} files")

    print("\n" + "-" * 80)
    print("VARIANT A FILES")
    print("-" * 80)

    for file_path in variant_a:
        print(file_path.relative_to(DATA_ROOT))

    print("\n" + "-" * 80)
    print("VARIANT B FILES")
    print("-" * 80)

    for file_path in variant_b:
        print(file_path.relative_to(DATA_ROOT))

    if other:
        print("\n" + "-" * 80)
        print("OTHER / UNKNOWN FILES")
        print("-" * 80)

        for file_path in other:
            print(file_path.relative_to(DATA_ROOT))

    print("\n" + "=" * 80)
    print("CLASSIFICATION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()