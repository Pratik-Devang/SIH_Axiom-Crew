from __future__ import annotations

import json
import statistics
import sys
import time
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model, count_parameters

ARTIFACTS = ROOT / "artifacts"
ARTIFACTS_V2 = ROOT / "artifacts" / "v2"


def percentile(values: list[float], pct: float) -> float:
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((pct / 100.0) * (len(ordered) - 1))))
    return ordered[idx]


def main() -> None:
    ckpt_file = ARTIFACTS / "tcn_best.pt"
    if not ckpt_file.exists():
        ckpt_file = ARTIFACTS_V2 / "tcn_best.pt"

    ckpt = torch.load(ckpt_file, map_location="cpu")
    config = ckpt["config"]
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()
    x = torch.randn(*config["deployment"]["input_shape"], dtype=torch.float32)

    with torch.no_grad():
        for _ in range(50):
            _ = model(x)
        latencies_ms = []
        for _ in range(300):
            start = time.perf_counter()
            _ = model(x)
            latencies_ms.append((time.perf_counter() - start) * 1000.0)

    pt_file = ARTIFACTS / "tcn_best.pt"
    onnx_file = ARTIFACTS / "tcn.onnx"
    result = {
        "device": "development_machine_cpu",
        "batch_size": 1,
        "mean_latency_ms": statistics.mean(latencies_ms),
        "p50_latency_ms": percentile(latencies_ms, 50),
        "p95_latency_ms": percentile(latencies_ms, 95),
        "p99_latency_ms": percentile(latencies_ms, 99),
        "parameters": count_parameters(model),
        "pytorch_file_size_bytes": pt_file.stat().st_size if pt_file.exists() else None,
        "onnx_file_size_bytes": onnx_file.stat().st_size if onnx_file.exists() else None,
    }
    
    with (ARTIFACTS / "latency.json").open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    ARTIFACTS_V2.mkdir(parents=True, exist_ok=True)
    with (ARTIFACTS_V2 / "latency.json").open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
        
    print(result)
    print(f"saved: {ARTIFACTS / 'latency.json'}")


if __name__ == "__main__":
    main()
