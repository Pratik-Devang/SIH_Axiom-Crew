"""Run the four validation-only TCN candidates; never loads test trips."""
from __future__ import annotations
import json, subprocess, sys
from pathlib import Path
import torch
from src.ml.tcn import build_model, count_parameters
from src.ml.preprocessing import load_config

ROOT = Path(__file__).resolve().parents[1]
CANDIDATES = (
    ("base_mse", "base", "mse"),
    ("base_huber", "base", "huber"),
    ("large_mse", "large", "mse"),
    ("large_huber", "large", "huber"),
)

def candidate_parameters(size: str) -> int:
    config = load_config()
    if size == "large":
        config["model"]["channels"] = [208, 208, 208, 208]
    return count_parameters(build_model(config))

def main() -> None:
    print(json.dumps({"candidates": [{"name": n, "model_size": s, "loss": l, "parameters": candidate_parameters(s)} for n, s, l in CANDIDATES]}, indent=2))
    for name, size, loss in CANDIDATES:
        print(f"Run {name}: python -m src.ml.train --device cuda --model-size {size} --loss {loss} --max-epochs 100 --patience 15")
        subprocess.run([sys.executable, "-m", "src.ml.train", "--device", "cuda", "--model-size", size, "--loss", loss, "--max-epochs", "100", "--patience", "15"], cwd=ROOT, check=True)

if __name__ == "__main__":
    main()
