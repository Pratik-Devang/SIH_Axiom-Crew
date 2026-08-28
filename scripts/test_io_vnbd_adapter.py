import sys
from pathlib import Path
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from data_adapters.io_vnbd import (
    find_smartphone_files,
    read_smartphone_file,
)


DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():

    files = find_smartphone_files(
        DATA_ROOT
    )

    print("=" * 80)
    print("IO-VNBD ADAPTER TEST")
    print("=" * 80)

    print(
        f"\nFound {len(files)} smartphone files."
    )

    # Test only the first file
    test_file = files[0]

    print(
        f"\nTesting file:\n{test_file}"
    )

    raw_df = pd.read_csv(
    test_file,
    encoding="latin1",
    nrows=3
)

    raw_df.columns = raw_df.columns.str.strip()

    print("\nRAW DATE VALUES:")
    print(
    raw_df[
        "DATE (YYYY-MO-DD HH-MI-SS_SSS)"
    ].to_list())


    df = read_smartphone_file(
        test_file)

        
    

    print("\nSchema variant:")
    print(
        df["schema_variant"].iloc[0]
    )

    print("\nColumns:")
    for column in df.columns:
        print(f"  - {column}")

    print("\nRaw DATE values:")
    print(df["timestamp"].head(3).to_list())

    print("\nFirst 3 rows:")
    print(
        df.head(3).to_string(
            index=False
        )
    )

    print("\n" + "=" * 80)
    print("ADAPTER TEST COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()