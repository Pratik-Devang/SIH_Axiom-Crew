"""Non-destructive investigation of original IO-VNBD S/V source pairs.

Only train and validation trips are opened. Locked test trips are not read,
evaluated, or used to form any estimate.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw" / "io_vnbd" / "Synchronised V abd S datasets"
MANIFEST = ROOT / "data" / "splits" / "io_vnbd_splits.yaml"
OUT = ROOT / "artifacts" / "evaluation"
LOCKED = {"Vta1a", "Vta1b", "Y1"}


def find_files() -> tuple[dict[str, Path], dict[str, Path]]:
    files = list(RAW.rglob("*.csv"))
    phones = {p.stem[2:].lower(): p for p in files if p.stem.lower().startswith("s-")}
    vehicles = {p.stem[2:].lower(): p for p in files if p.stem.lower().startswith("v-")}
    return phones, vehicles


def inspect_pair(name: str, phone_path: Path, vehicle_path: Path) -> dict:
    phone = pd.read_csv(phone_path, encoding="latin1")
    vehicle = pd.read_csv(vehicle_path, encoding="latin1")
    phone.columns = phone.columns.astype(str).str.strip()
    vehicle.columns = vehicle.columns.astype(str).str.strip()
    st = pd.to_numeric(phone["TIME SINCE START (ms)"], errors="coerce").to_numpy(float) / 1000.0
    vt = pd.to_numeric(vehicle["Time Since Start of Day (seconds)"], errors="coerce").to_numpy(float)
    phone_dt = pd.to_datetime(phone["DATE (YYYY-MO-DD HH-MI-SS_SSS)"], format="%Y-%m-%d %H:%M:%S:%f", errors="coerce")
    sdiff, vdiff = np.diff(st), np.diff(vt)
    return {
        "trip": name,
        "smartphone_file": str(phone_path.relative_to(ROOT)),
        "vehicle_file": str(vehicle_path.relative_to(ROOT)),
        "smartphone_rows": int(len(phone)),
        "vehicle_rows": int(len(vehicle)),
        "smartphone_schema": "A_yaw_pitch_roll" if any("GYROSCOPE Yaw" in c for c in phone.columns) else "B_x_y_z",
        "smartphone_columns": list(phone.columns),
        "vehicle_columns": list(vehicle.columns),
        "smartphone_time": {"field": "TIME SINCE START (ms)", "unit": "ms", "start": float(st[0]), "end": float(st[-1]), "median_dt_s": float(np.nanmedian(sdiff)), "nonpositive_deltas": int(np.sum(sdiff <= 0))},
        "smartphone_datetime": {"field": "DATE (YYYY-MO-DD HH-MI-SS_SSS)", "valid_rows": int(phone_dt.notna().sum()), "start": str(phone_dt.iloc[0]) if phone_dt.notna().any() else None, "end": str(phone_dt.iloc[-1]) if phone_dt.notna().any() else None},
        "vehicle_time": {"field": "Time Since Start of Day (seconds)", "unit": "s", "start": float(vt[0]), "end": float(vt[-1]), "median_dt_s": float(np.nanmedian(vdiff)), "nonpositive_deltas": int(np.sum(vdiff <= 0))},
        "vehicle_sample_period_s": {"median": float(pd.to_numeric(vehicle["Sample period (seconds)"], errors="coerce").median()), "unique_first_20": sorted(pd.to_numeric(vehicle["Sample period (seconds)"], errors="coerce").dropna().unique()[:20].tolist())},
        "duration_comparison": {"smartphone_elapsed_s": float(st[-1] - st[0]), "smartphone_datetime_s": float((phone_dt.iloc[-1] - phone_dt.iloc[0]).total_seconds()) if phone_dt.notna().all() else None, "vehicle_elapsed_s": float(vt[-1] - vt[0]), "duration_difference_s": float((st[-1] - st[0]) - (vt[-1] - vt[0]))},
        "row_index_pairing_diagnostic": "not accepted when duration difference, resets, or row counts indicate dropped/reset samples",
    }


def main() -> None:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    analyzed = list(manifest["train"]) + list(manifest["validation"])
    phones, vehicles = find_files()
    pairs = []
    missing = []
    for name in analyzed:
        p, v = phones.get(name.lower()), vehicles.get(name.lower())
        if p is None or v is None:
            missing.append({"trip": name, "smartphone_present": p is not None, "vehicle_present": v is not None})
        else:
            pairs.append(inspect_pair(name, p, v))

    report = {
        "verdict": "B",
        "verdict_text": "Source data supports a reconstruction, but additional assumptions and validation are required before implementation or regeneration.",
        "scope": {"source_inventory": {"smartphone_files": 144, "vehicle_files": 144, "matched_pairs_expected": 144}, "analyzed_trips": analyzed, "analyzed_pair_count": len(pairs), "locked_test_excluded": sorted(LOCKED), "locked_test_opened": False, "model_training_or_evaluation": False},
        "source_files_inspected": ["scripts/prepare_io_vnbd.py", "scripts/test_io_vnbd_adapter.py", "scripts/ingest_io_vnbd.py", "src/data/adapters/io_vnbd.py", "src/preprocessing/synchronize.py", "src/preprocessing/sensor_filter.py", "src/ml/preprocessing.py", "data/README.md", "data/manifests/io_vnbd_dataset.json", "data/manifests/trip_metadata.json", "data/splits/io_vnbd_splits.yaml", "git history through 7927779 and current preparation history"],
        "published_source_evidence": [{"source": "IO-VNBD Data in Brief article", "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC7907232/", "supports": ["vehicle ECU data at 10 Hz", "smartphone data at 10 Hz with GPS at 1 Hz", "phone holder setup", "smartphone accelerometer/gyro/magnetometer/orientation/GPS fields", "vehicle fields including GPS velocity, wheel speeds, yaw rate, indicated vehicle speed, indicated longitudinal/lateral acceleration"]}, {"source": "IO-VNBD repository", "url": "https://github.com/onyekpeu/IO-VNBD", "supports": ["original synchronized and unsynchronized data locations"]}],
        "raw_schema": {"smartphone": {"accelerometer": ["ACCELEROMETER X (m/s²)", "ACCELEROMETER Y (m/s²)", "ACCELEROMETER Z (m/s²)"], "gravity": ["GRAVITY X (m/s²)", "GRAVITY Y (m/s²)", "GRAVITY Z (m/s²)"], "gyro_variants": {"A": ["GYROSCOPE Yaw (rad/s)", "GYROSCOPE Pitch (rad/s)", "GYROSCOPE Roll (rad/s)"], "B": ["GYROSCOPE X (rad/s)", "GYROSCOPE Y (rad/s)", "GYROSCOPE Z (rad/s)" ]}, "orientation": ["ORIENTATION (Yaw) (°)", "ORIENTATION (Pitch) (°)", "ORIENTATION (Roll ) (°)"], "gps": ["GPS LATITUDE (degrees)", "GPS LONGITUDE (degrees)", "GPS SPEED (Kmh)", "GPS ACCURACY (m)", "GPS ORIENTATION (°)"], "time": ["TIME SINCE START (ms)", "DATE (YYYY-MO-DD HH-MI-SS_SSS)" ]}, "vehicle": {"clock": "Time Since Start of Day (seconds)", "sample_period": "Sample period (seconds)", "speed_candidates": ["Velocity (km/hr)", "Indicated Vehicle Speed (km/hr)", "Wheel Speed Front Left/Right/Rear Left/Rear Right (rad/sec)"], "reference_dynamics": ["Indicated Longitudinal Acceleration (g)", "Indicated Lateral Acceleration (g)", "Yaw Rate (deg/sec)"], "navigation": ["Latitude (degrees)", "Longitude (degrees)", "Heading (degrees)", "Vertical velocity (km/hr)"]}},
        "frame_investigation": {"classification": "B_physically_estimable_but_not_explicitly_proven", "explicit_metadata": False, "numeric_transform_present_in_source": False, "mounting_evidence": "published phone-holder setup and axis-alignment figures; no per-trip rotation matrix or mount-angle record", "usable_sources_for_estimation": ["smartphone gravity vector for Up/tilt", "smartphone orientation fields as a candidate attitude signal", "vehicle indicated longitudinal/lateral acceleration and yaw rate for label-independent axis correspondence", "stationary segments for gravity/bias checks"], "limitations": ["vehicle frame signs and exact phone-holder orientation are not encoded numerically", "Variant A yaw/pitch/roll naming is not an XYZ physical-axis guarantee", "vehicle acceleration reference itself has sensor/lever-arm/noise differences", "a transform estimated from dynamic signals must be validated on held-out train/validation trips without speed labels"], "forbidden_shortcuts": ["do not assume processed accel_x/y/z are vehicle axes", "do not invent a fixed permutation/sign matrix", "do not fit transform using indicated speed or its derivative", "do not use locked trips"]},
        "synchronization_investigation": {"published_status": "source folder is manually synchronized where possible; unsynchronized data also exists", "current_method": "truncate to min(S,V) and pair by row index", "nominal_rates": {"smartphone": "10 Hz / 0.1 s", "vehicle": "10 Hz / 0.1 s"}, "clock_domains": {"smartphone": "elapsed milliseconds plus absolute local datetime", "vehicle": "time since start of day seconds"}, "diagnosis": "row pairing is not physically valid for every trip", "evidence": {"examples": {"S1": {"S_duration_s": 5174.499, "V_duration_s": 5174.5, "row_counts": [51746, 51746]}, "S2": {"S_duration_s": 9201.099, "V_duration_s": 9387.5, "S_nonpositive_deltas": 1}, "S3b": {"S_duration_s": -2026.412, "V_duration_s": 681.2, "S_nonpositive_deltas": 1}, "S4": {"S_duration_s": 354.78, "V_duration_s": 9459.9, "S_nonpositive_deltas": 2}, "M": {"S_duration_s": 6171.748, "V_duration_s": 10597.3, "S_nonpositive_deltas": 1}}, "well_behaved_examples": "many Vw trips have matching ~0.1 s clocks and durations within ~0.1 s"}, "required_method": "use source timestamps, split at timestamp resets, establish monotonic elapsed clocks, estimate only a constant offset or piecewise affine clock map per valid segment, then interpolate labels onto the phone clock; reject segments without identifiable overlap", "offset_estimation": "not a single global offset; phone absolute datetime versus vehicle time-of-day has variable offsets and DST/time-zone ambiguity, so per-trip/segment estimation is required"},
        "label_investigation": {"authoritative_training_label": "Indicated Vehicle Speed (km/hr)", "conversion": "divide by 3.6 to m/s", "meaning": "vehicle ECU/CAN indicated speed; not independently certified ground truth", "independent_candidates": ["Velocity (km/hr) from vehicle GPS/VBOX", "four wheel-speed channels converted from rad/s using wheel parameters if available", "smartphone GPS SPEED (Kmh), low-rate and noisy"], "selection_rule": "retain Indicated Vehicle Speed as primary target only after time alignment; compare against vehicle GPS Velocity and wheel-speed-derived speed on train/validation; do not change labels silently", "reference_status": "independent vehicle reference fields exist, but their agreement and exact provenance must be quantified before declaring one ground truth"},
        "android_contract": {"source": "android/app/src/main/java/com/percorsa/sensorlogger/CanonicalImuSample.kt", "construction": "CanonicalImuSample.toFeatureArray()", "verified_indices": {"0": "vehicleAccelForward", "1": "vehicleAccelLeft", "2": "vehicleAccelUp", "3": "vehicleGyroForward", "4": "vehicleGyroLeft", "5": "vehicleGyroUp"}, "training_order": ["forward", "lateral", "up", "gyro_forward", "gyro_left", "gyro_up"], "status": "mapping is correct; declaration order of gyro fields is misleading but array indices are explicit", "existing_protection": "android/app/src/test/java/com/percorsa/sensorlogger/TcnInputBufferTest.kt::calibratedFeaturesMatchIoVnbdForwardLateralUpContract"},
        "pairs_train_validation": pairs,
        "missing_pairs_train_validation": missing,
        "replacement_preprocessing_design": {"inputs": "raw S/V matched pair from synchronized source folder", "frame": "estimate per-trip rotation using gravity for Up plus label-independent correspondence between phone dynamic acceleration/gyro and vehicle longitudinal/lateral/yaw signals; record matrix, method, quality score, and sign convention", "sync": "timestamp-reset segmentation followed by per-segment clock alignment; no blind row truncation", "gravity": "retain gravity-inclusive forward/lateral/up accelerometer channels to match Android contract; use smartphone GRAVITY fields only for transform/bias diagnostics unless explicitly part of input contract", "gyro": "map physical vehicle angular axes into forward/left/up order; resolve Variant A semantics before use", "resampling": "construct a 10 Hz monotonic phone-time grid over verified overlap; interpolate continuous IMU and target only within valid source intervals; no extrapolation", "label": "Indicated Vehicle Speed (km/hr) / 3.6, with vehicle GPS velocity and wheel-speed references reported for QA", "missing_samples": "reject gaps/resets or split segments; never interpolate across discontinuities", "windows": "50 samples, stride 1, only within verified segments", "split": "existing complete-trip train/validation/test manifest; test remains untouched", "normalization": "fit six-channel statistics on train trips only", "leakage": "transform and synchronization use no speed labels, future samples, validation statistics, or locked-test data; reference comparison is QA only"},
        "missing_information": ["numeric per-trip phone-to-vehicle rotation/calibration matrix", "authoritative definition and calibration provenance of each vehicle speed field", "documented S/V clock offset and dropped-sample map for every pair", "wheel radii/gear/odometry calibration if wheel speed is used as an independent reference", "unambiguous physical meaning of Variant A gyro yaw/pitch/roll axes"],
        "recommendation": "Do not regenerate yet. Implement a separate non-destructive source alignment/provenance prototype and validate its transform and clock map on train/validation only. Regeneration is justified only after those QA gates pass; Android and the locked test remain unchanged.",
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "io_vnbd_source_reconstruction_audit.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = "# IO-VNBD source reconstruction audit\n\n"
    md += f"## Verdict\n\n**{report['verdict']} — {report['verdict_text']}**\n\n"
    md += "Only train and validation trips were opened. Vta1a, Vta1b, and Y1 were not opened, evaluated, or used.\n\n"
    md += "## Answers\n\n"
    md += "1. **Phone-to-vehicle frame:** no explicit numeric transform exists, but source gravity, orientation, vehicle acceleration, and yaw signals support a label-independent per-trip estimation with validation.\n"
    md += "2. **Synchronization:** nominally 10 Hz and manually synchronized where possible, but row-index pairing fails on several analyzed trips because source clocks reset or durations diverge.\n"
    md += "3. **Authoritative label:** `Indicated Vehicle Speed (km/hr)` from the vehicle CSV, converted to m/s.\n"
    md += "4. **Independent reference:** vehicle GPS `Velocity (km/hr)` and wheel-speed fields exist; agreement is not yet established.\n"
    md += "5. **Android contract:** `CanonicalImuSample.toFeatureArray()` is correct by index: forward, left, up, gyro-forward, gyro-left, gyro-up. The existing Android unit test protects this mapping; no new test was needed.\n"
    md += "6. **Replacement preprocessing:** timestamp-segmented clock alignment, per-trip transform estimation, verified-overlap resampling, explicit QA, then 10 Hz/50-sample windows.\n"
    md += "7. **Missing:** numeric frame calibration, per-trip clock/drop metadata, and definitive speed-reference provenance.\n"
    md += "8. **Regeneration:** not yet; first implement and validate the source alignment prototype.\n\n"
    md += "## Important evidence\n\n"
    md += "Examples of source duration disagreement: S2 is 9,201.099 s versus V2 9,387.5 s; S3b has a negative elapsed-time span due to a reset; S4 is 354.780 s versus 9,459.9 s; M is 6,171.748 s versus 10,597.3 s.\n\n"
    md += "The published IO-VNBD documentation describes 10 Hz vehicle and smartphone acquisition, a phone holder, manual synchronization where possible, and vehicle longitudinal/lateral acceleration plus multiple speed fields.\n\n"
    md += "## Next step\n\n" + report["recommendation"] + "\n"
    (OUT / "io_vnbd_source_reconstruction_audit.md").write_text(md, encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "analyzed_pairs": len(pairs), "missing_pairs": missing, "locked_test_opened": False, "reports": [str(OUT / "io_vnbd_source_reconstruction_audit.json"), str(OUT / "io_vnbd_source_reconstruction_audit.md")]}, indent=2))


if __name__ == "__main__":
    main()
