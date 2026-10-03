"""Short CUDA throughput profile; does not evaluate or load the frozen test split."""
from __future__ import annotations

import time
from pathlib import Path
import sys

import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, fit_normalization, load_config, load_split_trips
from src.ml.tcn import build_model


def main() -> None:
    cfg = load_config()
    splits = load_split_trips(cfg)
    stats = fit_normalization(splits["train"])
    train = [apply_normalization(f, stats) for f in splits["train"]]
    val = [apply_normalization(f, stats) for f in splits["validation"]]
    t0 = time.perf_counter()
    ds = SpeedWindowDataset(train, cfg["data"]["window_samples"], cfg["data"]["stride"])
    init_s = time.perf_counter() - t0
    loader = DataLoader(ds, batch_size=cfg["training"]["batch_size"], shuffle=True, num_workers=0,
                        pin_memory=True)
    device = torch.device("cuda")
    model = build_model(cfg).to(device).train()
    opt = torch.optim.Adam(model.parameters(), lr=cfg["training"]["learning_rate"],
                           weight_decay=cfg["training"]["weight_decay"])
    it = iter(loader)
    t0 = time.perf_counter(); x, y = next(it); first_batch_s = time.perf_counter() - t0
    load_times = []; copy_times = []; forward_times = []; backward_times = []; step_times = []
    for _ in range(100):
        t = time.perf_counter(); x, y = next(it); load_times.append(time.perf_counter() - t)
        t = time.perf_counter(); x = x.to(device, non_blocking=True); y = y.to(device, non_blocking=True); torch.cuda.synchronize(); copy_times.append(time.perf_counter() - t)
        t = time.perf_counter(); pred = model(x); torch.cuda.synchronize(); forward_times.append(time.perf_counter() - t)
        t = time.perf_counter(); loss = torch.mean((pred-y)**2); loss.backward(); torch.cuda.synchronize(); backward_times.append(time.perf_counter() - t)
        t = time.perf_counter(); opt.step(); opt.zero_grad(set_to_none=True); torch.cuda.synchronize(); step_times.append(time.perf_counter() - t)
    total = sum(load_times)+sum(copy_times)+sum(forward_times)+sum(backward_times)+sum(step_times)
    print({"dataset_init_s": init_s, "first_batch_s": first_batch_s, "batches": 100,
           "batch_size": cfg["training"]["batch_size"], "samples_per_s": 100*cfg["training"]["batch_size"]/total,
           "batches_per_s": 100/total, "avg_load_s": sum(load_times)/100,
           "avg_copy_s": sum(copy_times)/100, "avg_forward_s": sum(forward_times)/100,
           "avg_backward_s": sum(backward_times)/100, "avg_optimizer_s": sum(step_times)/100,
           "device": str(device), "gpu_memory_mb": torch.cuda.memory_allocated()/1024**2,
           "val_trips": len(val)})


if __name__ == "__main__":
    main()
