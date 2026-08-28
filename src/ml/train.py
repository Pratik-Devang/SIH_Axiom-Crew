from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import (
    INPUT_COLUMNS,
    apply_normalization,
    fit_normalization,
    load_config,
    load_split_trips,
    load_standardized_trip,
    save_json,
    set_seed,
)
from src.ml.tcn import build_model, count_parameters


ARTIFACTS = ROOT / "artifacts"
ARTIFACTS_V2 = ROOT / "artifacts" / "v2"

# log_var is clamped tightly so the model cannot escape to high-variance collapse.
_LOG_VAR_MIN = -4.0  # std ≈ 0.14 m/s floor
_LOG_VAR_MAX = 2.0   # std ≈ 2.7 m/s ceiling (reasonable for urban driving)

# Variance regularisation: pulls log_var toward zero (unit variance in normalised
# space) during the warm-up phase.  Weight decays to zero after warm-up ends.
_VAR_REG_WEIGHT = 0.1


def regression_loss(
    pred: torch.Tensor,
    target: torch.Tensor,
    uncertainty: bool,
    mse_only: bool = False,
) -> torch.Tensor:
    """Compute training loss.

    mse_only=True is used during the warm-up phase so the mean head learns a
    good starting point before the log-variance head is activated.
    """
    if not uncertainty or mse_only:
        # Pure MSE on the mean prediction regardless of the second output head.
        mean = pred[:, 0] if uncertainty else pred
        return torch.mean((mean - target) ** 2)
    mean = pred[:, 0]
    log_var = torch.clamp(pred[:, 1], min=_LOG_VAR_MIN, max=_LOG_VAR_MAX)
    nll = torch.mean(0.5 * (log_var + (target - mean) ** 2 / torch.exp(log_var)))
    # Regularise: penalise log_var deviating far from 0 in normalised space.
    var_reg = _VAR_REG_WEIGHT * torch.mean(log_var ** 2)
    return nll + var_reg


def run_epoch(
    model,
    loader,
    optimizer=None,
    uncertainty: bool = False,
    mse_only: bool = False,
) -> float:
    training = optimizer is not None
    model.train(training)
    losses = []
    for x, y in loader:
        if training:
            optimizer.zero_grad(set_to_none=True)
        pred = model(x)
        loss = regression_loss(pred, y, uncertainty, mse_only=mse_only)
        if training:
            loss.backward()
            optimizer.step()
        losses.append(float(loss.detach().cpu()))
    return float(np.mean(losses))


def main() -> None:
    config = load_config()
    set_seed(config["training"]["seed"])
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    ARTIFACTS_V2.mkdir(parents=True, exist_ok=True)

    split_trips = load_split_trips(config)
    _, meta = load_standardized_trip(config)
    
    stats = fit_normalization(split_trips["train"], INPUT_COLUMNS)
    save_json(stats, ARTIFACTS / "normalization.json")
    save_json(stats, ARTIFACTS_V2 / "normalization.json")

    norm_trips = {
        name: [apply_normalization(frame, stats) for frame in frames]
        for name, frames in split_trips.items()
    }

    train_ds = SpeedWindowDataset(norm_trips["train"], config["data"]["window_samples"], config["data"]["stride"])
    val_ds = SpeedWindowDataset(norm_trips["validation"], config["data"]["window_samples"], config["data"]["stride"])
    test_ds = SpeedWindowDataset(norm_trips["test"], config["data"]["window_samples"], config["data"]["stride"])

    train_loader = DataLoader(train_ds, batch_size=config["training"]["batch_size"], shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=config["training"]["batch_size"], shuffle=False)

    model = build_model(config)
    uncertainty = config["model"].get("predict_uncertainty", False)
    total_epochs = config["training"]["epochs"]
    
    # Warm-up: first 25% of epochs train purely on MSE so the mean head converges
    # before the log-variance head is allowed to influence gradients.
    warmup_epochs = max(1, total_epochs // 4) if uncertainty else 0
    print(f"Training: {total_epochs} epochs  |  uncertainty={uncertainty}  |  MSE warm-up={warmup_epochs} epochs")
    print(f"Dataset split trips: train={len(split_trips['train'])}, val={len(split_trips['validation'])}, test={len(split_trips['test'])}")
    print(f"Dataset windows: train={len(train_ds)}, val={len(val_ds)}, test={len(test_ds)}")

    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=config["training"]["learning_rate"],
        weight_decay=config["training"]["weight_decay"],
    )
    # Cosine annealing brings LR smoothly to near-zero over the full run.
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=total_epochs, eta_min=config["training"]["learning_rate"] * 0.01
    )

    best_val = float("inf")
    for epoch in range(1, total_epochs + 1):
        mse_only = uncertainty and (epoch <= warmup_epochs)
        phase = "MSE-warmup" if mse_only else "NLL"
        train_loss = run_epoch(model, train_loader, optimizer, uncertainty, mse_only=mse_only)
        with torch.no_grad():
            val_loss = run_epoch(model, val_loader, None, uncertainty, mse_only=mse_only)
        lr_now = optimizer.param_groups[0]["lr"]
        print(f"epoch {epoch:02d}/{total_epochs} [{phase}] train={train_loss:.6f} val={val_loss:.6f} lr={lr_now:.2e}")
        scheduler.step()
        if val_loss < best_val:
            best_val = val_loss
            ckpt_dict = {
                "model_state_dict": model.state_dict(),
                "config": config,
                "normalization": stats,
                "metadata": meta,
                "best_validation_loss": best_val,
                "best_epoch": epoch,
            }
            torch.save(ckpt_dict, ARTIFACTS / "tcn_best.pt")
            torch.save(ckpt_dict, ARTIFACTS_V2 / "tcn_best.pt")

    total_train_rows = sum(len(f) for f in split_trips["train"])
    total_val_rows = sum(len(f) for f in split_trips["validation"])
    total_test_rows = sum(len(f) for f in split_trips["test"])

    info = {
        "model": "SpeedTCN",
        "version": "v2",
        "parameters": count_parameters(model),
        "input_shape": [None, config["model"]["input_channels"], config["data"]["window_samples"]],
        "output": "speed_mps" if not uncertainty else ["speed_mean_mps", "log_variance"],
        "train_trips": len(split_trips["train"]),
        "validation_trips": len(split_trips["validation"]),
        "test_trips": len(split_trips["test"]),
        "train_rows": total_train_rows,
        "validation_rows": total_val_rows,
        "test_rows": total_test_rows,
        "train_windows": len(train_ds),
        "validation_windows": len(val_ds),
        "test_windows": len(test_ds),
        "best_validation_loss": best_val,
        "warmup_epochs": warmup_epochs,
        "target_column": meta["target_source_column"],
        "target_unit": "m/s",
    }
    save_json(info, ARTIFACTS / "model_info.json")
    save_json(info, ARTIFACTS_V2 / "model_info.json")
    print(f"saved: {ARTIFACTS / 'tcn_best.pt'}")
    print(f"saved: {ARTIFACTS / 'normalization.json'}")
    print(f"saved: {ARTIFACTS / 'model_info.json'}")


if __name__ == "__main__":
    main()
