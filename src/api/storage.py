"""Local, path-safe storage for uploaded trip records."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from uuid import uuid4

import pandas as pd

from src.data.live_trip import TripValidation, safe_trip_id


class TripStore:
    def __init__(self, root: str | Path):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def save(
        self, frame: pd.DataFrame, validation: TripValidation
    ) -> dict[str, object]:
        record_id = f"{safe_trip_id(validation.trip_id)}-{uuid4().hex[:10]}"
        csv_path = self.root / f"{record_id}.csv"
        metadata_path = self.root / f"{record_id}.json"
        metadata = {"record_id": record_id, **validation.as_dict()}

        csv_handle = tempfile.NamedTemporaryFile(
            mode="w", suffix=".csv.tmp", dir=self.root, delete=False, newline=""
        )
        metadata_handle = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json.tmp", dir=self.root, delete=False
        )
        try:
            with csv_handle:
                frame.to_csv(csv_handle, index=False)
            with metadata_handle:
                json.dump(metadata, metadata_handle, indent=2)
            os.replace(csv_handle.name, csv_path)
            os.replace(metadata_handle.name, metadata_path)
        finally:
            Path(csv_handle.name).unlink(missing_ok=True)
            Path(metadata_handle.name).unlink(missing_ok=True)
        return metadata

    def list(self) -> list[dict[str, object]]:
        records = []
        for path in sorted(self.root.glob("*.json"), reverse=True):
            try:
                records.append(json.loads(path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                continue
        return records

    def csv_path(self, record_id: str) -> Path:
        safe_id = safe_trip_id(record_id)
        if safe_id != record_id:
            raise FileNotFoundError(record_id)
        path = (self.root / f"{safe_id}.csv").resolve()
        if path.parent != self.root or not path.is_file():
            raise FileNotFoundError(record_id)
        return path
