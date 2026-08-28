from pathlib import Path
import pandas as pd


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def normalize_column_name(column):
    """Normalize formatting so we can compare column names."""
    return (
        str(column)
        .strip()
        .replace("  ", " ")
        .lower()
    )


def main():
    print("=" * 80)
    print("IO-VNBD SMARTPHONE SCHEMA INSPECTION")
    print("=" * 80)

    smartphone_files = sorted(
        DATA_ROOT.rglob("S-*.csv")
    )

    print(f"\nSmartphone CSV files found: {len(smartphone_files)}")

    if not smartphone_files:
        print("No smartphone CSV files found.")
        return

    reference_columns = None
    reference_normalized = None

    for file_path in smartphone_files:

        print("\n" + "-" * 80)
        print(f"FILE: {file_path.relative_to(DATA_ROOT)}")
        print("-" * 80)

        try:
            df = pd.read_csv(
                file_path,
                nrows=5,
                encoding="latin1"
            )

            columns = list(df.columns)
            normalized_columns = [
                normalize_column_name(c)
                for c in columns
            ]

            print(f"Columns: {len(columns)}")

            if reference_columns is None:
                reference_columns = columns
                reference_normalized = normalized_columns

                print("\nThis file is the reference schema.")

            else:
                if normalized_columns == reference_normalized:
                    print(
                        "\nSchema: SAME after normalization"
                    )

                else:
                    print(
                        "\nSchema: DIFFERENT after normalization"
                    )

                    missing = [
                        c for c in reference_normalized
                        if c not in normalized_columns
                    ]

                    additional = [
                        c for c in normalized_columns
                        if c not in reference_normalized
                    ]

                    if missing:
                        print("\nMissing columns:")
                        for c in missing:
                            print(f"  - {c}")

                    if additional:
                        print("\nAdditional columns:")
                        for c in additional:
                            print(f"  + {c}")

        except Exception as error:
            print(f"ERROR reading file: {error}")

    print("\n" + "=" * 80)
    print("SMARTPHONE SCHEMA INSPECTION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()