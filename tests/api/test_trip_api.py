from pathlib import Path

import pandas as pd
from fastapi.testclient import TestClient

from src.api.app import ApiSettings, create_app
from src.api.storage import TripStore

API_KEY = "test-key-with-more-than-thirty-two-characters"
AUTH = {"X-Percorsa-Key": API_KEY}


def android_csv(with_gnss: bool = False) -> bytes:
    frame = pd.DataFrame(
        {
            "timestamp_ns": [1_000_000_000, 1_100_000_000, 1_200_000_000],
            "ax": [0.0, 0.1, 0.2],
            "ay": [0.0, 0.0, 0.0],
            "az": [9.81, 9.81, 9.81],
            "gx": [0.0, 0.0, 0.0],
            "gy": [0.0, 0.0, 0.0],
            "gz": [0.0, 0.01, 0.01],
        }
    )
    if with_gnss:
        frame["latitude"] = [19.05, 19.05001, 19.05002]
        frame["longitude"] = [72.89, 72.89001, 72.89002]
    return frame.to_csv(index=False).encode()


def client(tmp_path: Path) -> TestClient:
    settings = ApiSettings(api_key=API_KEY, storage_root=tmp_path)
    return TestClient(create_app(settings, TripStore(tmp_path)))


def test_health_is_public_and_does_not_expose_key(tmp_path):
    response = client(tmp_path).get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "authentication_configured": True,
    }


def test_trip_routes_reject_missing_or_incorrect_key(tmp_path):
    api = client(tmp_path)
    assert api.get("/api/v1/trips").status_code == 401
    assert (
        api.get("/api/v1/trips", headers={"X-Percorsa-Key": "wrong"}).status_code == 401
    )


def test_short_configured_key_keeps_ingestion_disabled(tmp_path):
    settings = ApiSettings(api_key="too-short", storage_root=tmp_path)
    api = TestClient(create_app(settings, TripStore(tmp_path)))
    response = api.get("/api/v1/trips", headers={"X-Percorsa-Key": "too-short"})
    assert response.status_code == 503


def test_current_android_csv_is_accepted_as_sensor_only(tmp_path):
    response = client(tmp_path).post(
        "/api/v1/trips/upload",
        headers=AUTH,
        files={"file": ("drive.csv", android_csv(), "text/csv")},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["accepted"] is True
    assert payload["has_imu"] is True
    assert payload["replay_ready"] is False
    assert (tmp_path / f"{payload['record_id']}.csv").is_file()


def test_future_android_csv_with_gnss_is_replay_ready(tmp_path):
    response = client(tmp_path).post(
        "/api/v1/trips/upload",
        headers=AUTH,
        files={"file": ("../unsafe name.csv", android_csv(True), "text/csv")},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["replay_ready"] is True
    assert "/" not in payload["record_id"]
    assert "\\" not in payload["record_id"]


def test_non_csv_upload_is_rejected(tmp_path):
    response = client(tmp_path).post(
        "/api/v1/trips/upload",
        headers=AUTH,
        files={"file": ("trip.exe", b"not a csv", "application/octet-stream")},
    )
    assert response.status_code == 415
