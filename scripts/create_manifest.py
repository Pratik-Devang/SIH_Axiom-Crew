from pathlib import Path
import json


PROJECT_ROOT = Path(".")
PARQUET_ROOT = Path("data/processed/io_vnbd")
QUALITY_REPORT = PARQUET_ROOT / "data_quality_report.json"

OUTPUT_FILE = PROJECT_ROOT / "dataset_manifest.json"


def main():

    print("=" * 80)
    print("CREATING IO-VNBD DATASET MANIFEST")
    print("=" * 80)

    parquet_files = sorted(
        PARQUET_ROOT.glob("*.parquet")
    )

    # Read the validation report if it exists
    if QUALITY_REPORT.exists():

        with open(
            QUALITY_REPORT,
            "r",
            encoding="utf-8"
        ) as f:
            quality = json.load(f)

    else:
        quality = {}

    manifest = {
        "dataset": "IO-VNBD",

        "source": {
            "name": "IO-VNBD",
            "description": (
                "In-Vehicle and smartphone sensor dataset"
            ),
            "source_data": (
                "Synchronised V and S datasets"
            ),
        },

        "processing": {
            "format": "Parquet",
            "adapter": "data_adapters/io_vnbd.py",
            "ingestion_script": "scripts/ingest_io_vnbd.py",
            "validation_script": "preprocessing/validation.py",
        },

        "files": {
            "source_smartphone_files": 144,
            "processed_parquet_files": len(parquet_files),
        },

        "validation": {
            "files_validated": quality.get(
                "files_validated"
            ),
            "total_rows": quality.get(
                "total_rows"
            ),
            "invalid_timestamps": quality.get(
                "total_invalid_timestamps"
            ),
            "duplicate_timestamps": quality.get(
                "total_duplicate_timestamps"
            ),
            "backward_timestamp_jumps": quality.get(
                "total_backward_timestamp_jumps"
            ),
            "invalid_coordinates": quality.get(
                "total_invalid_coordinates"
            ),
        },

        "schema_variants": {
            "variant_A": (
                "Gyroscope Yaw/Pitch/Roll"
            ),
            "variant_B": (
                "Gyroscope X/Y/Z"
            ),
        },

        "data_handling": {
            "raw_data_modified": False,
            "source_timestamp_modified": False,
            "notes": [
                (
                    "Two source files contained a malformed "
                    "DATE column header. The adapter handles "
                    "the alternate header without modifying "
                    "the raw files."
                ),
                (
                    "Duplicate timestamps are reported by "
                    "validation and are not automatically removed."
                ),
            ],
        },

        "dataset_scope": {
            "synchronised_data_processed": True,
            "unsynchronised_data_processed": False,
            "unsynchronised_data_note": (
                "Requires separate inspection and handling."
            ),
        },

        "status": {
            "io_vnbd_adapter": "complete",
            "ingestion": "complete",
            "validation": "complete",
            "trip_metadata": "complete",
            "train_validation_test_splits": "pending",
            "unsynchronised_data": "pending",
            "ppc_adapter": "pending",
            "urbannav_adapter": "pending",
        },
    }

    with open(
        OUTPUT_FILE,
        "w",
        encoding="utf-8"
    ) as f:

        json.dump(
            manifest,
            f,
            indent=2
        )

    print("\n" + "=" * 80)
    print("MANIFEST COMPLETE")
    print("=" * 80)

    print(
        f"Parquet files: {len(parquet_files)}"
    )

    print(
        f"Output: {OUTPUT_FILE}"
    )


if __name__ == "__main__":
    main()