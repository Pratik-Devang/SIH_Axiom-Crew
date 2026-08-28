from pathlib import Path
import pandas as pd


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():
    print("=" * 80)
    print("IO-VNBD TIMESTAMP INSPECTION")
    print("=" * 80)

    files = sorted(DATA_ROOT.rglob("S-*.csv"))

    print(f"\nSmartphone files: {len(files)}")

    for file_path in files:
        try:
            df = pd.read_csv(
                file_path,
                encoding="latin1"
            )

            # Remove accidental whitespace from column names
            df.columns = df.columns.str.strip()

            time_col = "TIME SINCE START (ms)"
            date_col = "DATE (YYYY-MO-DD HH-MI-SS_SSS)"

            if time_col not in df.columns:
                print(f"\nMISSING TIME COLUMN: {file_path}")
                continue

            time = pd.to_numeric(
                df[time_col],
                errors="coerce"
            )

            valid_time = time.dropna()

            if len(valid_time) == 0:
                print(f"\nNO VALID TIMESTAMPS: {file_path}")
                continue

            differences = valid_time.diff().dropna()

            print("\n" + "-" * 80)
            print(file_path.relative_to(DATA_ROOT))
            print("-" * 80)

            print(f"Rows: {len(df)}")
            print(f"Valid time values: {len(valid_time)}")
            print(f"Missing time values: {time.isna().sum()}")

            print(
                f"Start time: {valid_time.iloc[0]:.0f} ms"
            )

            print(
                f"End time: {valid_time.iloc[-1]:.0f} ms"
            )

            print(
                f"Duration: "
                f"{(valid_time.iloc[-1] - valid_time.iloc[0]) / 1000:.2f} seconds"
            )

            if len(differences) > 0:
                print(
                    f"Median sampling interval: "
                    f"{differences.median():.2f} ms"
                )

                print(
                    f"Minimum interval: "
                    f"{differences.min():.2f} ms"
                )

                print(
                    f"Maximum interval: "
                    f"{differences.max():.2f} ms"
                )

            print(
                f"Duplicate timestamps: "
                f"{valid_time.duplicated().sum()}"
            )

            print(
                f"Monotonic increasing: "
                f"{valid_time.is_monotonic_increasing}"
            )

            # Check DATE column if present
            if date_col in df.columns:
                dates = pd.to_datetime(
                    df[date_col],
                    errors="coerce"
                )

                print(
                    f"Invalid DATE values: "
                    f"{dates.isna().sum()}"
                )

        except Exception as error:
            print(f"\nERROR: {file_path}")
            print(error)

    print("\n" + "=" * 80)
    print("TIMESTAMP INSPECTION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()