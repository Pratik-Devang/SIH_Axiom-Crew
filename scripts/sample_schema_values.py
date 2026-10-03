from pathlib import Path
import pandas as pd


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():

    print("=" * 80)
    print("IO-VNBD SAMPLE DATA INSPECTION")
    print("=" * 80)

    files = sorted(DATA_ROOT.rglob("S-*.csv"))

    shown_a = False
    shown_b = False

    for file_path in files:

        try:
            df = pd.read_csv(
                file_path,
                nrows=3,
                encoding="latin1"
            )

            df.columns = df.columns.str.strip()

            columns = {
                str(c).strip().lower()
                for c in df.columns
            }

            has_xyz = (
                "gyroscope x (rad/s)" in columns
            )

            has_ypr = (
                "gyroscope yaw (rad/s)" in columns
            )

            if has_xyz and not shown_b:

                print("\n" + "-" * 80)
                print("VARIANT B — X/Y/Z")
                print("-" * 80)

                print(f"File: {file_path}")

                print("\nFirst 3 rows:")
                print(df.to_string(index=False))

                shown_b = True

            elif has_ypr and not shown_a:

                print("\n" + "-" * 80)
                print("VARIANT A — YAW/PITCH/ROLL")
                print("-" * 80)

                print(f"File: {file_path}")

                print("\nFirst 3 rows:")
                print(df.to_string(index=False))

                shown_a = True

            if shown_a and shown_b:
                break

        except Exception as error:

            print(
                f"ERROR reading {file_path}: {error}"
            )

    print("\n" + "=" * 80)
    print("SAMPLE INSPECTION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()