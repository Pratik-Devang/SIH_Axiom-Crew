"""Train and evaluate a baseline PyTorch speed model."""

from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import mean_absolute_error, mean_squared_error
from torch import nn
from torch.utils.data import DataLoader, TensorDataset


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = PROJECT_ROOT / "data" / "processed" / "io_vnbd" / "trips"
MODEL_DIR = PROJECT_ROOT / "models"
MODEL_PATH = MODEL_DIR / "speed_model.pt"

FEATURES = [
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
]

TARGET = "vehicle_speed"

WINDOW = 20
EPOCHS = 10
BATCH_SIZE = 256
LEARNING_RATE = 1e-3
SEED = 42


class SpeedMLP(nn.Module):
    """Small baseline neural network for vehicle-speed prediction."""

    def __init__(self, input_size: int):
        super().__init__()

        self.network = nn.Sequential(
            nn.Linear(input_size, 64),
            nn.ReLU(),
            nn.Linear(64, 32),
            nn.ReLU(),
            nn.Linear(32, 1),
        )

    def forward(self, x):
        return self.network(x)


def load_trips():
    """Load all successfully prepared trips."""

    files = sorted(DATA_DIR.glob("*.csv"))

    if not files:
        raise FileNotFoundError(
            f"No processed trip CSV files found in {DATA_DIR}"
        )

    frames = []

    for path in files:
        df = pd.read_csv(path)

        missing = [
            column
            for column in FEATURES + [TARGET]
            if column not in df.columns
        ]

        if missing:
            print(f"SKIP: {path.name}: missing {missing}")
            continue

        df = df[FEATURES + [TARGET]].dropna()

        if len(df) > 0:
            frames.append(df)

    if not frames:
        raise RuntimeError("No usable processed trips found.")

    return pd.concat(frames, ignore_index=True)


def main():
    torch.manual_seed(SEED)
    np.random.seed(SEED)

    print("=" * 60)
    print("PERCORSA SPEED MODEL")
    print("=" * 60)

    print(f"Loading data from: {DATA_DIR}")

    df = load_trips()

    print(f"Rows: {len(df):,}")
    print(f"Features: {FEATURES}")
    print(f"Target: {TARGET}")

    X = df[FEATURES].to_numpy(dtype=np.float32)
    y = df[TARGET].to_numpy(dtype=np.float32).reshape(-1, 1)

    # Split chronologically: first 80% for training, last 20% for testing.
    split = int(len(X) * 0.8)

    X_train = X[:split]
    y_train = y[:split]

    X_test = X[split:]
    y_test = y[split:]

    # Normalize using training data only.
    mean = X_train.mean(axis=0)
    std = X_train.std(axis=0)
    std[std < 1e-8] = 1.0

    X_train = (X_train - mean) / std
    X_test = (X_test - mean) / std

    train_dataset = TensorDataset(
        torch.from_numpy(X_train),
        torch.from_numpy(y_train),
    )

    train_loader = DataLoader(
        train_dataset,
        batch_size=BATCH_SIZE,
        shuffle=True,
    )

    model = SpeedMLP(len(FEATURES))

    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=LEARNING_RATE,
    )

    loss_function = nn.MSELoss()

    print()
    print(f"Training rows: {len(X_train):,}")
    print(f"Testing rows:  {len(X_test):,}")
    print()

    for epoch in range(1, EPOCHS + 1):
        model.train()

        total_loss = 0.0

        for batch_x, batch_y in train_loader:
            optimizer.zero_grad()

            prediction = model(batch_x)

            loss = loss_function(
                prediction,
                batch_y,
            )

            loss.backward()
            optimizer.step()

            total_loss += loss.item() * len(batch_x)

        epoch_loss = total_loss / len(train_dataset)

        print(
            f"Epoch {epoch:02d}/{EPOCHS} "
            f"- train MSE: {epoch_loss:.4f}"
        )

    model.eval()

    with torch.no_grad():
        predictions = model(
            torch.from_numpy(X_test)
        ).numpy()

    mae = mean_absolute_error(
        y_test,
        predictions,
    )

    rmse = np.sqrt(
        mean_squared_error(
            y_test,
            predictions,
        )
    )

    print()
    print("=" * 60)
    print("EVALUATION")
    print("=" * 60)
    print(f"MAE:  {mae:.4f} km/h")
    print(f"RMSE: {rmse:.4f} km/h")

    MODEL_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    torch.save(
        {
            "model_state_dict": model.state_dict(),
            "features": FEATURES,
            "target": TARGET,
            "mean": mean,
            "std": std,
        },
        MODEL_PATH,
    )

    print()
    print(f"Model saved to: {MODEL_PATH}")
    print("Done.")


if __name__ == "__main__":
    main()
