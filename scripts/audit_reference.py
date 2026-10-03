"""Audit a recording's reference provenance and sample quality."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

# Allow direct execution from the repository root, matching the other scripts.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from src.evaluation.ground_truth import audit_reference_frame


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path, help="CSV recording to audit")
    parser.add_argument(
        "--provenance",
        default="unknown",
        choices=("unknown", "gnss_receiver", "surveyed", "rtk", "external_reference"),
        help="Explicit provenance; GNSS is never promoted to truth automatically",
    )
    args = parser.parse_args()
    frame = pd.read_csv(args.recording)
    print(json.dumps(audit_reference_frame(frame, provenance=args.provenance).as_dict(), indent=2))


if __name__ == "__main__":
    main()
