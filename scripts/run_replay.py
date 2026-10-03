"""Run one processed trip through controlled outage evaluation."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from src.evaluation.replay import run_outage_replay  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trip", type=Path, help="Processed Percorsa trip CSV")
    parser.add_argument(
        "--start", type=float, default=60.0, help="Outage start in seconds"
    )
    parser.add_argument(
        "--duration", type=float, default=30.0, help="Outage duration in seconds"
    )
    parser.add_argument(
        "--output", type=Path, default=ROOT / "results" / "trajectories" / "replay.csv"
    )
    args = parser.parse_args()

    trip = pd.read_csv(args.trip)
    replay, metrics = run_outage_replay(trip, args.start, args.duration)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    replay.to_csv(args.output, index=False)
    print(json.dumps(metrics, indent=2))
    print(f"Replay written to {args.output}")


if __name__ == "__main__":
    main()
