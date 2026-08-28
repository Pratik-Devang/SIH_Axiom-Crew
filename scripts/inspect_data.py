from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import make_window_arrays
from src.ml.preprocessing import (
    INPUT_COLUMNS,
    chronological_split,
    find_io_vnbd_pair,
    load_config,
    load_standardized_trip,
)


def main() -> None:
    config = load_config()
    smartphone, vehicle = find_io_vnbd_pair(config)
    trip, meta = load_standardized_trip(config)

    print("IO-VNBD prototype subset")
    print(f"smartphone_file: {smartphone}")
    print(f"vehicle_file:    {vehicle}")
    print(f"rows: {len(trip)}")
    print()
    print("Input channels used by TCN:")
    for name, source in meta["input_source_columns"].items():
        print(f"  {name}: {source}")
    print(f"Target: {meta['target_source_column']} -> speed_mps ({meta['target_conversion']})")
    print()

    dt = trip["timestamp_s"].diff().dropna()
    print(f"smartphone dt seconds min/median/mean/max: {dt.min():.3f}, {dt.median():.3f}, {dt.mean():.3f}, {dt.max():.3f}")
    print(f"vehicle sample period unique: {sorted(trip['sample_period_s'].unique())[:5]}")
    print("alignment: row counts match; synchronized prototype uses row-by-row alignment")
    print()

    splits = chronological_split(trip, config["data"]["train_fraction"], config["data"]["validation_fraction"])
    print(f"chronological split rows: train={len(splits['train'])}, validation={len(splits['validation'])}, test={len(splits['test'])}")

    x, y = make_window_arrays(
        splits["train"],
        window_samples=config["data"]["window_samples"],
        stride=config["data"]["stride"],
    )
    print(f"train window check X.shape={x.shape}, y.shape={y.shape}")
    print(f"expected single model input: [batch, {len(INPUT_COLUMNS)}, {config['data']['window_samples']}]")
    print(f"target speed range in train windows: {np.min(y):.3f} to {np.max(y):.3f} m/s")


if __name__ == "__main__":
    main()
