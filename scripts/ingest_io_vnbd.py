from pathlib import Path
import sys

import pandas as pd

# Allow Python to find the project root
PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from src.data.adapters.io_vnbd import (
    find_smartphone_files,
    read_smartphone_file,
)


RAW_ROOT = (
    PROJECT_ROOT
    / "data"
    / "raw"
    / "io_vnbd"
    / "Synchronised V abd S datasets"
)

OUTPUT_ROOT = (
    PROJECT_ROOT / "data" / "processed" / "io_vnbd"
)


def main():

    print("=" * 80)
    print("IO-VNBD INGESTION")
    print("=" * 80)

    files = find_smartphone_files(
        RAW_ROOT
    )

    print(
        f"\nFound {len(files)} smartphone files."
    )

    if not files:
        raise FileNotFoundError(
            "No smartphone CSV files found."
        )

    OUTPUT_ROOT.mkdir(
        parents=True,
        exist_ok=True
    )

    successful = 0
    failed = 0

    for file_path in files:

        try:

            print(
                f"\nProcessing: "
                f"{file_path.name}"
            )

            df = read_smartphone_file(
                file_path
            )

            # Create a unique output name
            relative_path = file_path.relative_to(
                RAW_ROOT
            )

            output_name = (
                "_".join(
                    relative_path.with_suffix("").parts
                )
                + ".parquet"
            )

            output_path = (
                OUTPUT_ROOT / output_name
            )

            # Save normalized data as Parquet
            df.to_parquet(
                output_path,
                index=False
            )

            print(
                f"Saved: {output_path}"
            )

            successful += 1

        except Exception as error:

        

            print("\n" + "!" * 80)
            print("FAILED FILE:")
            print(file_path)
            print("REASON:")
            print(error)
            print("!" * 80 + "\n")

    

            failed += 1

    print("\n" + "=" * 80)
    print("INGESTION SUMMARY")
    print("=" * 80)

    print(
        f"Successful: {successful}"
    )

    print(
        f"Failed: {failed}"
    )

    print(
        f"Total: {len(files)}"
    )

    print("\n" + "=" * 80)
    print("INGESTION COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()
