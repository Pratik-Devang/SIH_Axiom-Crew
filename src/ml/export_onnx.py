from __future__ import annotations

import sys
from pathlib import Path
import shutil
import hashlib

import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model
from src.ml.preprocessing import save_json

ARTIFACTS = ROOT / "artifacts"
ARTIFACTS_V2 = ROOT / "artifacts" / "v2"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    ckpt_path = ARTIFACTS / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ARTIFACTS_V2 / "tcn_best.pt"

    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
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

    normalization_path = ARTIFACTS / "normalization.json"
    if not normalization_path.is_file():
        raise FileNotFoundError("Missing normalization.json from the same training run")
    artifact_manifest = {
        "run_id": ckpt.get("run_id", "legacy-untracked"),
        "input_shape": config["deployment"]["input_shape"],
        "feature_order": ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"],
        "target_unit": "m/s",
        "files": {
            "checkpoint": {"path": ckpt_path.name, "sha256": sha256_file(ckpt_path)},
            "normalization": {"path": normalization_path.name, "sha256": sha256_file(normalization_path)},
            "onnx": {"path": onnx_path.name, "sha256": sha256_file(onnx_path)},
        },
    }
    save_json(artifact_manifest, ARTIFACTS / "artifact_manifest.json")
    save_json(artifact_manifest, ARTIFACTS_V2 / "artifact_manifest.json")
    
    print(f"saved: {onnx_path}")
    print(f"saved: {ARTIFACTS_V2 / 'tcn.onnx'}")
    print(f"input:  {tuple(dummy.shape)}")
    print(f"output: {output_names[0]}")
    print(f"run id: {artifact_manifest['run_id']}")


if __name__ == "__main__":
    main()
