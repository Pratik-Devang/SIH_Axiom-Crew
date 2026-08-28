# Android trip ingestion contract

The dashboard and API accept the current Android IMU export today. When GNSS
logging is added, the same contract automatically enables route replay.

## Current sensor-only CSV

```text
timestamp_ns,ax,ay,az,gx,gy,gz,quality_flags
```

This is normalized into `time_since_start_s`, `accel_x/y/z` and `gyro_x/y/z`.
It can be inspected in the dashboard, but it is not labelled replay-ready.

## Future replay-ready additions

Add these fields to each sensor row, repeating the most recent GNSS fix between
location callbacks if necessary:

```text
latitude,longitude,gps_accuracy_m,gps_speed_mps,gps_bearing_deg,satellite_count
```

Latitude and longitude are the only extra fields required for route replay.
The remaining fields improve trust scoring, initialization and judge-facing
diagnostics.

## Authenticated API

The server exposes:

```text
GET  /health
GET  /api/v1/trips
POST /api/v1/trips/upload
POST /api/v1/trips/batches
GET  /api/v1/trips/{record_id}/csv
```

Every `/api/v1` request requires:

```text
X-Percorsa-Key: <secret>
```

The API rejects non-CSV files, uploads larger than 25 MB, unsafe trip names,
invalid timestamps, oversized batches and incomplete IMU schemas. Incoming
files are stored under `data/incoming/`, which is excluded from Git.

Generate a temporary key and start locally:

```powershell
$env:PERCORSA_API_KEY = python -c "import secrets; print(secrets.token_urlsafe(32))"
python scripts/run_api.py
```

Keep the default `127.0.0.1` binding while developing. When the Android client
is ready, bind to the LAN only for a controlled demonstration and add the
laptop address to `PERCORSA_ALLOWED_HOSTS`. Use HTTPS through a reverse proxy
before exposing the service beyond a trusted local network.

## Buffered JSON shape

```json
{
  "trip_id": "judge-drive-01",
  "samples": [
    {
      "timestamp_ns": 1010000000,
      "ax": 0.02,
      "ay": -0.01,
      "az": 9.79,
      "gx": 0.001,
      "gy": 0.002,
      "gz": -0.003,
      "latitude": 19.05,
      "longitude": 72.89,
      "gps_accuracy_m": 4.2
    }
  ]
}
```

Send batches rather than individual sensor samples. A batch every one to five
seconds reduces network overhead and allows the Android app to retain data if
the connection drops.
