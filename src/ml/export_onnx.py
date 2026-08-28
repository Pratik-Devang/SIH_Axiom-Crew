from __future__ import annotations

import sys
from pathlib import Path
import shutil

import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model

ARTIFACTS = ROOT / "artifacts"
ARTIFACTS_V2 = ROOT / "artifacts" / "v2"


def main() -> None:
    ckpt_path = ARTIFACTS / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ARTIFACTS_V2 / "tcn_best.pt"

    ckpt = torch.load(ckpt_path, map_location="cpu")
    config = ckpt["config"]
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    dummy = torch.randn(*config["deployment"]["input_shape"], dtype=torch.float32)
    output_names = ["speed_mean_log_variance"] if config["model"].get("predict_uncertainty", False) else ["speed_mps"]
    
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    ARTIFACTS_V2.mkdir(parents=True, exist_ok=True)
    
    onnx_path = ARTIFACTS / "tcn.onnx"
    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        input_names=["imu_window"],
        output_names=output_names,
        opset_version=17,
        dynamo=False,
        dynamic_axes={"imu_window": {0: "batch"}, output_names[0]: {0: "batch"}},
    )
    shutil.copy(onnx_path, ARTIFACTS_V2 / "tcn.onnx")
    
    print(f"saved: {onnx_path}")
    print(f"saved: {ARTIFACTS_V2 / 'tcn.onnx'}")
    print(f"input:  {tuple(dummy.shape)}")
    print(f"output: {output_names[0]}")


if __name__ == "__main__":
    main()
