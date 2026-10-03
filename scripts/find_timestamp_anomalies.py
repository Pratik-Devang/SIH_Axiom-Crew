from pathlib import Path
import pandas as pd


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():
    print("=" * 80)
    print("IO-VNBD TIMESTAMP ANOMALY DETECTION")
    print("=" * 80)

    # Find all smartphone CSV files
    files = sorted(DATA_ROOT.rglob("S-*.csv"))

    print(f"\nSmartphone files found: {len(files)}")

    total_backward = 0
    total_duplicates = 0

    for file_path in files:

        try:
            # Read the complete CSV
            df = pd.read_csv(
                file_path,
                encoding="latin1"
            )

            # Remove leading/trailing spaces from column names
            df.columns = df.columns.str.strip()

            time_col = "TIME SINCE START (ms)"

            # Skip files without the expected timestamp column
            if time_col not in df.columns:
                print(
                    f"\nMISSING TIME COLUMN: "
                    f"{file_path.relative_to(DATA_ROOT)}"
                )
                continue

            # Convert timestamp values to numbers
            time = pd.to_numeric(
                df[time_col],
                errors="coerce"
            )

            # Calculate difference between consecutive timestamps
            differences = time.diff()

            # Find timestamps that move backward
            backward = df.loc[
                differences < 0,
                [time_col]
            ].copy()

            # Find duplicate timestamps
            duplicates = df.loc[
                time.duplicated(keep=False),
                [time_col]
            ].copy()

            # Only print files containing anomalies
            if not backward.empty or not duplicates.empty:

                print("\n" + "-" * 80)
                print(
                    f"FILE: "
                    f"{file_path.relative_to(DATA_ROOT)}"
                )
                print("-" * 80)

                # -----------------------------------------
                # Backward timestamp jumps
                # -----------------------------------------

                if not backward.empty:

                    print(
                        f"\nBackward timestamp jumps: "
                        f"{len(backward)}"
                    )

                    print(
                        "\nRows where time goes backward:"
                    )

                    for index in backward.index:

                        previous_time = time.iloc[index - 1]
                        current_time = time.iloc[index]

                        print(
                            f"Row {index}: "
                            f"{previous_time} ms -> "
                            f"{current_time} ms "
                            f"(difference: "
                            f"{current_time - previous_time} ms)"
                        )

                    total_backward += len(backward)

                # -----------------------------------------
                # Duplicate timestamps
                # -----------------------------------------

                if not duplicates.empty:

                    print(
                        f"\nDuplicate timestamp rows: "
                        f"{len(duplicates)}"
                    )

                    print(
                        duplicates.to_string()
                    )

                    total_duplicates += len(duplicates)

        except Exception as error:

            print(
                f"\nERROR reading "
                f"{file_path.relative_to(DATA_ROOT)}"
            )

            print(error)

    # ---------------------------------------------
    # Final summary
    # ---------------------------------------------

    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)

    print(
        f"\nTotal backward timestamp jumps: "
        f"{total_backward}"
    )

    print(
        f"Total duplicate timestamp rows: "
        f"{total_duplicates}"
    )

    print("\n" + "=" * 80)
    print("ANOMALY DETECTION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()