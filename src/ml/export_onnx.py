from __future__ import annotations

import sys
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model

ARTIFACTS = ROOT / "artifacts"


def main() -> None:
    ckpt = torch.load(ARTIFACTS / "tcn_best.pt", map_location="cpu")
    config = ckpt["config"]
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    dummy = torch.randn(*config["deployment"]["input_shape"], dtype=torch.float32)
    output_names = ["speed_mean_log_variance"] if config["model"].get("predict_uncertainty", False) else ["speed_mps"]
    torch.onnx.export(
        model,
        dummy,
        ARTIFACTS / "tcn.onnx",
        input_names=["imu_window"],
        output_names=output_names,
        opset_version=17,
        dynamo=False,
        dynamic_axes={"imu_window": {0: "batch"}, output_names[0]: {0: "batch"}},
    )
    print(f"saved: {ARTIFACTS / 'tcn.onnx'}")
    print(f"input:  {tuple(dummy.shape)}")
    print(f"output: {output_names[0]}")


if __name__ == "__main__":
    main()
