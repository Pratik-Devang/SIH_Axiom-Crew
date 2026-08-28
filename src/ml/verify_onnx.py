from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model

ARTIFACTS = ROOT / "artifacts"


def main() -> None:
    tolerance = 1e-4
    ckpt = torch.load(ARTIFACTS / "tcn_best.pt", map_location="cpu")
    config = ckpt["config"]
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    torch.manual_seed(config["training"]["seed"])
    x = torch.randn(4, config["model"]["input_channels"], config["data"]["window_samples"], dtype=torch.float32)
    with torch.no_grad():
        torch_out = model(x).cpu().numpy()

    session = ort.InferenceSession(str(ARTIFACTS / "tcn.onnx"), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    onnx_out = session.run(None, {input_name: x.cpu().numpy()})[0]

    diff = np.abs(torch_out - onnx_out)
    result = {
        "max_abs_diff": float(diff.max()),
        "mean_abs_diff": float(diff.mean()),
        "tolerance": tolerance,
        "passed": bool(diff.max() <= tolerance),
    }
    with (ARTIFACTS / "onnx_parity.json").open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(result)
    print(f"saved: {ARTIFACTS / 'onnx_parity.json'}")


if __name__ == "__main__":
    main()
