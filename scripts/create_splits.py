from pathlib import Path
import json
import random


METADATA_FILE = Path("trip_metadata.json")

TRAIN_FILE = Path("train.txt")
VALIDATION_FILE = Path("validation.txt")
TEST_FILE = Path("test.txt")

SEED = 42


def main():

    with open(
        METADATA_FILE,
        "r",
        encoding="utf-8"
    ) as f:
        metadata = json.load(f)

    trips = metadata["trips"]

    # Use the processed Parquet path as the split identifier
    trip_files = [
        trip["file"]
        for trip in trips
    ]

    random.seed(SEED)
    random.shuffle(trip_files)

    total = len(trip_files)

    train_end = int(total * 0.70)
    validation_end = int(total * 0.85)

    train = trip_files[:train_end]
    validation = trip_files[train_end:validation_end]
    test = trip_files[validation_end:]

    def write_split(path, items):

        with open(
            path,
            "w",
            encoding="utf-8"
        ) as f:

            for item in items:
                f.write(item + "\n")

    write_split(TRAIN_FILE, train)
    write_split(VALIDATION_FILE, validation)
    write_split(TEST_FILE, test)

    print("=" * 80)
    print("DATASET SPLITS COMPLETE")
    print("=" * 80)

    print(f"Total trips: {total}")
    print(f"Training: {len(train)}")
    print(f"Validation: {len(validation)}")
    print(f"Test: {len(test)}")
    print(f"Random seed: {SEED}")


if __name__ == "__main__":
    main()