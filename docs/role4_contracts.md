# Percorsa — Role 4: Maps, Vehicle Constraints & GNSS Integrity
### Project: SIH 2026, PS 26168 | Team Axiom Crew

> **What this module does:** Provides the "trust and context" layer for the
> Percorsa dead-reckoning navigation system.  It decides *when* to trust a GNSS
> fix, detects vehicle state (stopped, turning, moving straight), and matches the
> fused trajectory onto the real road network to correct dead-reckoning drift.

---

## Quick Start

```bash
# 1. Install dependencies
pip install osmnx networkx geopandas shapely pyproj scipy pandas pyarrow pyyaml pytest

# 2. Download Chembur test fixture
python scripts/bootstrap_chembur.py

# 3. Run tests
pytest tests/ -v
```

---

## Repository Layout

```
navigation/
  gnss_trust.py          # GNSS Trust Manager — score/accept/reject fixes
  stop_detector.py        # Detect stationary state → ZUPT events for Role 3
  vehicle_constraints.py  # NHC violation detection → NHC events for Role 3

maps/
  osm_loader.py           # Load/cache road network (osmnx); Android JSON export
  candidates.py            # Nearest-road candidates via scipy cKDTree
  hmm_matcher.py           # HMM/Viterbi map matching (MVP: nearest-snap stub)
  confidence.py            # Probabilistic match confidence scoring

coordinate_transform.py   # Shared WGS84 ↔ ENU utility (cross-role)

tests/
  conftest.py              # Shared fixtures (Chembur graph, temp log paths)
  test_gnss_trust.py
  test_stop_detector.py
  test_candidates.py
  test_hmm_matcher.py

data/
  chembur_1km.graphml      # Bootstrap test fixture
  chembur_1km_nodes.parquet
  chembur_1km_edges.parquet

configs/
  role4.yaml               # ALL thresholds (nothing hardcoded in logic files)

scripts/
  bootstrap_chembur.py     # Download Chembur OSM fixture

logs/
  role4_events.jsonl        # Structured event log (Role 6 input)
```

---

## Data Contracts

> ⚠️ **Critical:** The interfaces below define the exact boundaries between roles.
> A type or unit mismatch here causes **silent integration failures**.
> Read this section before writing any fusion-adjacent code.

---

### Contract with Role 3 — INS/ESKF Engineer (tightest coupling)

**Role 4 detects and decides. Role 3 implements the EKF update.**

Role 4 outputs structured `ConstraintEvent` objects.  Role 3 routes them to the
correct EKF measurement-update function based on `type`.

#### `ConstraintEvent` (defined in `navigation/stop_detector.py`)

| Field | Type | Unit | Notes |
|---|---|---|---|
| `timestamp` | `float` | POSIX seconds | Must match IMU sample timestamps |
| `type` | `str` | — | `"ZUPT"`, `"NHC"`, `"MAP_SNAP"` |
| `value` | `float` | m/s or m | ZUPT→0.0, NHC→lateral v (m/s), MAP_SNAP→residual (m) |
| `confidence` | `float` | [0.0, 1.0] | Role 3 uses this as measurement noise scale factor |
| `metadata` | `dict` | — | Extra fields (duration_s for ZUPT, edge_id for MAP_SNAP) |

#### Coordinate frame agreement

> ⚠️ **MUST AGREE BEFORE EITHER ROLE WRITES FUSION CODE:**
> All position/velocity values exchanged between Role 4 and Role 3 are in the
> **local East-North-Up (ENU) frame**, not raw WGS84 lat/lon.
>
> **Use `coordinate_transform.py` (repo root).** Do NOT re-implement this transform
> separately — use the shared module.
>
> - Frame: ENU, right-handed
> - Origin: `LatLonOrigin` — set once at trip start, passed to both roles
> - Units: metres (position), m/s (velocity)
>
> **Message to Role 3:** Please confirm you are using `coordinate_transform.latlon_to_enu()`
> and `enu_to_latlon()` in your ESKF state → GNSS residual computation.
> If you have a different transform, we need to resolve this before integration.
>
> **Message to Role 6:** Please use the same `coordinate_transform.py` when
> converting logged lat/lon back to ENU for drift metric computation.

---

### Contract with Role 1 — Data Engineer

Role 4 needs the following from Role 1's data pipeline:

| Need | Type | Notes |
|---|---|---|
| Trip bounding box | `(north, south, east, west)` floats | For `OSMLoader.load_from_bbox()` |
| GNSS outage window flag | Column or bool array | Marks simulated GNSS-loss windows in replay data so Role 4 can suppress GNSS trust without real jamming |
| Speed column | `float` m/s | Must be in SI. Used by `StopDetector` and `VehicleConstraintDetector` |
| Heading column | `float` degrees | Clockwise from North [0, 360) |

**Current test fixture:** Chembur, Mumbai (19.051°N, 72.894°E, 1 km radius).
This will be swapped for the real IO-VNBD trip bounding box once Role 1 delivers
the dataset.

---

### Contract with Role 5 — Android Engineer

The `networkx` MultiDiGraph is **not Android-consumable**.

Use `OSMLoader.export_lightweight_json(output_path)` to produce:

```json
{
  "nodes": [{"id": 123456, "lat": 19.05, "lon": 72.89}],
  "edges": [
    {
      "u": 123456, "v": 234567,
      "length_m": 85.2,
      "heading_deg": 90.0,
      "name": "LBS Road",
      "highway": "primary",
      "oneway": false
    }
  ]
}
```

**Agree on this schema with Role 5 before building Android routing logic.**
The schema above is a proposal — if Role 5 needs additional fields (e.g. speed
limits, geometry polyline), they should be added to the exporter now, not
retrofitted.

**Current export command:**
```python
from maps.osm_loader import OSMLoader
loader = OSMLoader()
loader.load_from_graphml("data/chembur_1km.graphml")
loader.export_lightweight_json("data/chembur_android.json")
```

---

### Contract with Role 6 — Integration & Evaluation Lead

Every accept/reject decision emitted by Role 4 is written to a **JSONL event log**
at `logs/role4_events.jsonl` (configurable in `configs/role4.yaml`).

**One JSON object per line.** Parseable with `pandas.read_json(..., lines=True)`.

#### Event types and fields

**GNSS_ACCEPT / GNSS_REJECT:**
```json
{
  "event_type": "GNSS_ACCEPT",
  "timestamp": 1724792400.123,
  "accepted": true,
  "score": 0.872,
  "reason": "All checks passed: age=0.12s, sats=9, hdop=1.2, accuracy=5.0",
  "fix_lat": 19.051,
  "fix_lon": 72.894,
  "hdop": 1.2,
  "accuracy_m": 5.0,
  "num_satellites": 9
}
```

**STOP_START / STOP_END:**
```json
{
  "event_type": "STOP_START",
  "timestamp": 1724792450.0,
  "is_stopped": true,
  "confidence": 0.72,
  "duration_s": 0.5,
  "mean_speed_m_s": 0.05
}
```

**NHC_OK / NHC_VIOLATION:**
```json
{
  "event_type": "NHC_VIOLATION",
  "timestamp": 1724792510.0,
  "lateral_velocity_m_s": 0.45,
  "violation": true,
  "confidence": 0.81,
  "speed_m_s": 8.3,
  "heading_rate_deg_s": 12.5
}
```

**Role 6 quick-load:**
```python
import pandas as pd
events = pd.read_json("logs/role4_events.jsonl", lines=True)
gnss_events = events[events.event_type.isin(["GNSS_ACCEPT", "GNSS_REJECT"])]
```

---

## Configuration Reference (`configs/role4.yaml`)

All thresholds are in this file. Key sections:

| Section | Key parameter | Default | Meaning |
|---|---|---|---|
| `gnss_trust` | `max_hdop` | 4.0 | Reject fixes with HDOP above this |
| `gnss_trust` | `max_accuracy_m` | 20.0 | Reject fixes with accuracy above this (m) |
| `gnss_trust` | `max_fix_age_s` | 5.0 | Reject fixes older than this (s) |
| `gnss_trust` | `abs_max_jump_m` | 200.0 | Reject if position jumps > this (m) |
| `stop_detector` | `speed_threshold_m_s` | 0.5 | Speed below this → candidate stop (m/s) |
| `stop_detector` | `min_stationary_samples` | 5 | Consecutive samples needed to declare stop |
| `vehicle_constraints` | `nhc_lateral_threshold_m_s` | 0.3 | Flag NHC violation above this (m/s) |
| `map_matching` | `candidate_radius_m` | 50.0 | KD-tree search radius (m) |
| `map_matching` | `max_heading_diff_deg` | 45.0 | Heading filter tolerance (deg) |

---

## Open Cross-Role Issues

| # | Issue | Blocking | Owner |
|---|---|---|---|
| 1 | Confirm Role 3 uses `coordinate_transform.py` ENU frame | **YES** — must agree before ESKF integration | Role 3 + Role 4 |
| 2 | Role 5 to confirm Android JSON schema (`export_lightweight_json`) | YES — needed before APK build | Role 4 + Role 5 |
| 3 | Role 1 to provide: real bounding box + GNSS outage flag column name | YES — blocks loading real data | Role 1 → Role 4 |
| 4 | Role 6 to confirm JSONL field names before building evaluation plots | No | Role 4 + Role 6 |
| 5 | Full Viterbi HMM (stretch goal) — wire in once MVP demo is confirmed | No | Role 4 |

---

## MVP vs Stretch Goal Status

| Component | Status |
|---|---|
| `osm_loader.py` — cached + live load | ✅ Done |
| `candidates.py` — cKDTree + heading filter | ✅ Done |
| `gnss_trust.py` — rule-based trust scoring | ✅ Done |
| `stop_detector.py` — sliding window ZUPT | ✅ Done |
| `vehicle_constraints.py` — NHC flag | ✅ Done |
| Structured JSONL event logging | ✅ Done |
| `coordinate_transform.py` — shared ENU | ✅ Done |
| `hmm_matcher.py` — Viterbi decoding | 🔲 Stub (stretch goal) |
| `confidence.py` — probabilistic score | 🔲 Stub (stretch goal) |
| Android JSON export | ✅ Done (schema TBC with Role 5) |
