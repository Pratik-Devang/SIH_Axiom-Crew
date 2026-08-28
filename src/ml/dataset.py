"""Causal window generation for the Percorsa TCN.

A window is 20 consecutive IMU samples. The label is the vehicle speed at the
final sample, so the model never sees sensor readings from after the target
time.

Strict multi-trip safety: windows are built per-trip so no window ever crosses
a trip boundary.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset

from src.ml.preprocessing import INPUT_COLUMNS, TARGET_COLUMN, standardize_trip_dataframe


class SpeedWindowDataset(Dataset):
    """Return tensors shaped [6, 20] and one scalar speed target.
    
    Supports a single DataFrame or a list of trip DataFrames without cross-trip leakage.
    """

    def __init__(
        self,
        frames: pd.DataFrame | list[pd.DataFrame],
        window_samples: int,
        stride: int = 1,
        input_columns: list[str] | None = None,
        target_column: str = TARGET_COLUMN,
    ) -> None:
        self.window_samples = int(window_samples)
        self.stride = int(stride)
        self.input_columns = input_columns or INPUT_COLUMNS
        self.target_column = target_column

        if self.window_samples < 1:
            raise ValueError("window_samples must be positive")
        if self.stride < 1:
            raise ValueError("stride must be positive")

        if isinstance(frames, pd.DataFrame):
            trip_list = [frames]
        else:
            trip_list = list(frames)

        self.samples: list[tuple[np.ndarray, float]] = []

        for trip in trip_list:
            df = standardize_trip_dataframe(trip).reset_index(drop=True)
            inputs = df[self.input_columns].to_numpy(dtype=np.float32)
            targets = df[self.target_column].to_numpy(dtype=np.float32)

            n_rows = len(df)
            if n_rows < self.window_samples:
                continue

            starts = range(0, n_rows - self.window_samples + 1, self.stride)
            for start in starts:
                end = start + self.window_samples
                # PyTorch Conv1D expects [channels, time]: [6, 20]
                x = inputs[start:end].T.copy()
                y = float(targets[end - 1])
                self.samples.append((x, y))

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int):
        x_np, y_val = self.samples[index]
        return torch.from_numpy(x_np), torch.tensor(y_val, dtype=torch.float32)


def make_window_arrays(frames: pd.DataFrame | list[pd.DataFrame], window_samples: int, stride: int = 1):
    dataset = SpeedWindowDataset(frames, window_samples=window_samples, stride=stride)
    xs, ys = [], []
    for i in range(len(dataset)):
        x, y = dataset[i]
        xs.append(x.numpy())
        ys.append(float(y))
    if not xs:
        return np.empty((0, 6, window_samples), dtype=np.float32), np.empty((0,), dtype=np.float32)
    return np.stack(xs), np.asarray(ys, dtype=np.float32)
