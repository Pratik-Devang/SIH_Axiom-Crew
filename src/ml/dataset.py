"""Causal window generation for the Percorsa TCN.

A window is 20 consecutive IMU samples. The label is the vehicle speed at the
final sample, so the model never sees sensor readings from after the target
time.
"""

from __future__ import annotations

import numpy as np
import torch
from torch.utils.data import Dataset

from src.ml.preprocessing import INPUT_COLUMNS, TARGET_COLUMN


class SpeedWindowDataset(Dataset):
    """Return tensors shaped [6, 20] and one scalar speed target."""

    def __init__(
        self,
        frame,
        window_samples: int,
        stride: int = 1,
        input_columns: list[str] | None = None,
        target_column: str = TARGET_COLUMN,
    ) -> None:
        self.frame = frame.reset_index(drop=True)
        self.window_samples = int(window_samples)
        self.stride = int(stride)
        self.input_columns = input_columns or INPUT_COLUMNS
        self.target_column = target_column
        self.inputs = self.frame[self.input_columns].to_numpy(dtype=np.float32)
        self.targets = self.frame[self.target_column].to_numpy(dtype=np.float32)
        if self.window_samples < 1:
            raise ValueError("window_samples must be positive")
        if self.stride < 1:
            raise ValueError("stride must be positive")
        self.starts = list(range(0, len(self.frame) - self.window_samples + 1, self.stride))

    def __len__(self) -> int:
        return len(self.starts)

    def __getitem__(self, index: int):
        start = self.starts[index]
        end = start + self.window_samples

        # PyTorch Conv1D expects [channels, time]. Here channels are the 6 IMU
        # signals and time is the 20-sample history.
        x = self.inputs[start:end].T.copy()
        y = self.targets[end - 1]
        return torch.from_numpy(x), torch.tensor(y)


def make_window_arrays(frame, window_samples: int, stride: int = 1):
    dataset = SpeedWindowDataset(frame, window_samples=window_samples, stride=stride)
    xs, ys = [], []
    for i in range(len(dataset)):
        x, y = dataset[i]
        xs.append(x.numpy())
        ys.append(float(y))
    return np.stack(xs), np.asarray(ys, dtype=np.float32)
