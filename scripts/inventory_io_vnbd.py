from pathlib import Path
import pandas as pd


# Root folder containing the extracted IO-VNBD dataset
DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


def main():
    print("=" * 80)
    print("IO-VNBD DATASET INVENTORY")
    print("=" * 80)

    # Check that the dataset folder exists
    if not DATA_ROOT.exists():
        print("\nERROR: Dataset folder was not found.")
        print(f"Expected location:\n{DATA_ROOT}")
        return

    # Find all files
    files = [path for path in DATA_ROOT.rglob("*") if path.is_file()]

    print(f"\nDataset root:")
    print(DATA_ROOT)

    print(f"\nTotal files found: {len(files)}")

    # ---------------------------------------------------------
    # 1. Files grouped by extension
    # ---------------------------------------------------------

    print("\n" + "-" * 80)
    print("FILES BY EXTENSION")
    print("-" * 80)

    extensions = {}

    for file in files:
        extension = file.suffix.lower() or "[no extension]"
        extensions[extension] = extensions.get(extension, 0) + 1

    for extension, count in sorted(extensions.items()):
        print(f"{extension:15} {count}")

    # ---------------------------------------------------------
    # 2. CSV files
    # ---------------------------------------------------------

    csv_files = [file for file in files if file.suffix.lower() == ".csv"]

    print("\n" + "-" * 80)
    print("CSV FILES")
    print("-" * 80)

    print(f"Total CSV files: {len(csv_files)}")

    for file in sorted(csv_files):
        relative_path = file.relative_to(DATA_ROOT)
        size_mb = file.stat().st_size / (1024 * 1024)

        print(f"\n{relative_path}")
        print(f"  Size: {size_mb:.2f} MB")

    # ---------------------------------------------------------
    # 3. Smartphone vs vehicle CSV files
    # ---------------------------------------------------------

    smartphone_files = []
    vehicle_files = []
    other_csv_files = []

    for file in csv_files:
        filename = file.name.upper()

        if filename.startswith("S-"):
            smartphone_files.append(file)
        elif filename.startswith("V-"):
            vehicle_files.append(file)
        else:
            other_csv_files.append(file)

    print("\n" + "-" * 80)
    print("CSV FILE CATEGORIES")
    print("-" * 80)

    print(f"Smartphone (S-*) files : {len(smartphone_files)}")
    print(f"Vehicle (V-*) files   : {len(vehicle_files)}")
    print(f"Other CSV files       : {len(other_csv_files)}")

    # ---------------------------------------------------------
    # 4. Folder structure
    # ---------------------------------------------------------

    print("\n" + "-" * 80)
    print("TOP-LEVEL FOLDERS")
    print("-" * 80)

    top_level_folders = [
        path for path in DATA_ROOT.iterdir() if path.is_dir()
    ]

    for folder in sorted(top_level_folders):
        print(f"- {folder.name}")

    # ---------------------------------------------------------
    # 5. Smartphone files with paths
    # ---------------------------------------------------------

    print("\n" + "-" * 80)
    print("SMARTPHONE DATA FILES")
    print("-" * 80)

    for file in sorted(smartphone_files):
        print(file.relative_to(DATA_ROOT))

    # ---------------------------------------------------------
    # 6. Vehicle files with paths
    # ---------------------------------------------------------

    print("\n" + "-" * 80)
    print("VEHICLE DATA FILES")
    print("-" * 80)

    for file in sorted(vehicle_files):
        print(file.relative_to(DATA_ROOT))

    print("\n" + "=" * 80)
    print("INVENTORY COMPLETE")
    print("=" * 80)


if __name__ == "__main__":
    main()