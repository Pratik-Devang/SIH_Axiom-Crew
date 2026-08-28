"""ONNX speed inference adapter for canonical Percorsa trip frames."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd

CANONICAL_TO_MODEL = {
    "accel_x": "accel_x",
    "accel_y": "accel_y",
    "accel_z": "accel_z",
    "gyro_yaw": "gyro_x",
    "gyro_pitch": "gyro_y",
    "gyro_roll": "gyro_z",
}


class OnnxSpeedPredictor:
    """Run the committed 2 second TCN over standardized 10 Hz data."""

    def __init__(self, model_path: str | Path, normalization_path: str | Path):
        self.session = ort.InferenceSession(
            str(model_path), providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name
        with Path(normalization_path).open(encoding="utf-8") as handle:
            self.normalization = json.load(handle)
        shape = self.session.get_inputs()[0].shape
        self.window_samples = int(shape[-1])

    def predict(self, frame: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
        """Return speed mean and variance aligned to every input row."""
        signals = []
        missing = []
        for model_name in self.normalization["columns"]:
            canonical = CANONICAL_TO_MODEL.get(model_name, model_name)
            source = model_name if model_name in frame else canonical
            if source not in frame:
                missing.append(source)
                continue
            values = pd.to_numeric(frame[source], errors="coerce")
            values = values.interpolate(limit_direction="both").fillna(0.0)
            mean = self.normalization["mean"][model_name]
            std = self.normalization["std"][model_name]
            signals.append(((values.to_numpy(float) - mean) / std).astype(np.float32))
        if missing:
            raise ValueError(f"TCN input columns are missing: {', '.join(missing)}")
        if len(frame) < self.window_samples:
            raise ValueError(
                f"TCN needs at least {self.window_samples} samples, got {len(frame)}"
            )

        channels = np.stack(signals, axis=1)
        windows = np.lib.stride_tricks.sliding_window_view(
            channels, self.window_samples, axis=0
        ).astype(np.float32)
        output = self.session.run(None, {self.input_name: windows})[0]
        mean = output[:, 0] if output.ndim == 2 else output.reshape(-1)
        variance = (
            np.exp(np.clip(output[:, 1], -4.0, 2.0))
            if output.ndim == 2 and output.shape[1] > 1
            else np.ones_like(mean)
        )
        prefix = self.window_samples - 1
        return (
            np.concatenate([np.full(prefix, mean[0]), mean]),
            np.concatenate([np.full(prefix, variance[0]), variance]),
        )
