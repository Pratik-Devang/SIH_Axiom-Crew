"""Lightweight causal TCN for IMU-to-speed prediction."""

from __future__ import annotations

import sys
from pathlib import Path

import torch
from torch import nn
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


class CausalConv1d(nn.Module):
    """Conv1D that cannot look into the future.

    Input shape is [batch, channels, time]. A channel is one signal, such as
    accel_x. The time dimension is the 50-sample history. Dilation skips over
    time steps to let the model see farther back without becoming large.
    """

    def __init__(self, in_channels: int, out_channels: int, kernel_size: int, dilation: int) -> None:
        super().__init__()
        self.left_padding = (kernel_size - 1) * dilation
        self.conv = nn.Conv1d(in_channels, out_channels, kernel_size, dilation=dilation)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = F.pad(x, (self.left_padding, 0))
        return self.conv(x)


class ResidualBlock(nn.Module):
    """Two causal convolutions plus a shortcut connection.

    The shortcut helps training because the block can learn a small correction
    instead of rebuilding the whole signal from scratch.
    """

    def __init__(self, in_channels: int, out_channels: int, kernel_size: int, dilation: int, dropout: float) -> None:
        super().__init__()
        self.net = nn.Sequential(
            CausalConv1d(in_channels, out_channels, kernel_size, dilation),
            nn.ReLU(),
            nn.Dropout(dropout),
            CausalConv1d(out_channels, out_channels, kernel_size, dilation),
            nn.ReLU(),
            nn.Dropout(dropout),
        )
        self.shortcut = nn.Identity()
        if in_channels != out_channels:
            self.shortcut = nn.Conv1d(in_channels, out_channels, kernel_size=1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x) + self.shortcut(x)


class SpeedTCN(nn.Module):
    """Predict vehicle forward speed from a 2 second IMU window."""

    def __init__(
        self,
        input_channels: int = 6,
        channels: list[int] | tuple[int, ...] = (32, 32, 32, 32),
        kernel_size: int = 3,
        dilations: list[int] | tuple[int, ...] = (1, 2, 4, 8),
        dropout: float = 0.1,
        predict_uncertainty: bool = False,
    ) -> None:
        super().__init__()
        if len(channels) != len(dilations):
            raise ValueError("channels and dilations must have the same length")
        self.predict_uncertainty = predict_uncertainty

        blocks = []
        in_ch = input_channels
        for out_ch, dilation in zip(channels, dilations):
            blocks.append(ResidualBlock(in_ch, out_ch, kernel_size, dilation, dropout))
            in_ch = out_ch
        self.tcn = nn.Sequential(*blocks)
        output_dim = 2 if predict_uncertainty else 1
        self.head = nn.Linear(in_ch, output_dim)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        features = self.tcn(x)
        last_time_features = features[:, :, -1]
        out = self.head(last_time_features)
        if self.predict_uncertainty:
            return out
        return out.squeeze(-1)


def build_model(config: dict) -> SpeedTCN:
    model_cfg = config["model"]
    return SpeedTCN(
        input_channels=model_cfg["input_channels"],
        channels=model_cfg["channels"],
        kernel_size=model_cfg["kernel_size"],
        dilations=model_cfg["dilations"],
        dropout=model_cfg["dropout"],
        predict_uncertainty=model_cfg.get("predict_uncertainty", False),
    )


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


if __name__ == "__main__":
    from src.ml.preprocessing import load_config

    cfg = load_config()
    model = build_model(cfg)
    x = torch.randn(4, cfg["model"]["input_channels"], cfg["data"]["window_samples"])
    y = model(x)
    print(f"input shape:  {tuple(x.shape)}")
    print(f"output shape: {tuple(y.shape)}")
    print(f"parameters:   {count_parameters(model)}")
