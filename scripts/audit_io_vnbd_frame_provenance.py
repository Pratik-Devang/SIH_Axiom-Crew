"""Train/validation-only IO-VNBD frame and label provenance audit.

This audit is intentionally read-only with respect to the corpus and never
loads a model or reads the locked test trips for quantitative analysis.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
PROCESSED = ROOT / "data" / "processed" / "io_vnbd" / "trips"
RAW = ROOT / "data" / "raw" / "io_vnbd" / "Synchronised V abd S datasets"
MANIFEST = ROOT / "data" / "splits" / "io_vnbd_splits.yaml"
OUT = ROOT / "artifacts" / "evaluation"
LOCKED = {"Vta1a", "Vta1b", "Y1"}
ACC = ["accel_x", "accel_y", "accel_z"]
GYRO = ["gyro_x", "gyro_y", "gyro_z"]
CHANNELS = ACC + GYRO


def finite_stats(x: np.ndarray) -> dict[str, float | int]:
    x = x[np.isfinite(x)]
    if not len(x):
        return {"count": 0}
    return {"count": int(len(x)), "median": float(np.median(x)),
            "p01": float(np.percentile(x, 1)), "p99": float(np.percentile(x, 99)),
            "mean": float(np.mean(x)), "std": float(np.std(x))}


def trip_metrics(df: pd.DataFrame) -> dict:
    values = df[CHANNELS].to_numpy(float)
    speed = df["vehicle_speed"].to_numpy(float)
    t = df["time_since_start_s"].to_numpy(float)
    accel_norm = np.linalg.norm(values[:, :3], axis=1)
    gyro_norm = np.linalg.norm(values[:, 3:], axis=1)
    stationary = np.isfinite(speed) & (speed < 0.5)
    dt = np.diff(t)
    speed_mps = speed / 3.6
    dsdt = np.gradient(speed_mps, t) if len(t) > 2 else np.full(len(t), np.nan)
    # The processed corpus has no official turn labels. Use high yaw-rate
    # proxy only to describe conditioning, never for model selection.
    turn_proxy = np.abs(values[:, 5]) > 0.15
    bump_proxy = np.abs(values[:, 2] - np.median(values[:, 2])) > 3.0
    return {
        "rows": int(len(df)),
        "duration_s": float(t[-1] - t[0]) if len(t) else 0.0,
        "dt_s": finite_stats(dt),
        "stationary_fraction": float(np.mean(stationary)) if len(df) else 0.0,
        "stationary_accel_norm_mps2": finite_stats(accel_norm[stationary]),
        "stationary_up_accel_mps2": finite_stats(values[stationary, 2]),
        "gyro_norm_rad_s": finite_stats(gyro_norm),
        "speed_kmh": finite_stats(speed),
        "speed_derivative_mps2": finite_stats(dsdt),
        "forward_accel_mps2": finite_stats(values[:, 0]),
        "lateral_accel_mps2": finite_stats(values[:, 1]),
        "up_accel_mps2": finite_stats(values[:, 2]),
        "turn_proxy_fraction": float(np.mean(turn_proxy)),
        "turn_proxy_lateral_abs_median_mps2": finite_stats(np.abs(values[turn_proxy, 1])),
        "turn_proxy_up_abs_median_mps2": finite_stats(np.abs(values[turn_proxy, 2])),
        "bump_proxy_fraction": float(np.mean(bump_proxy)),
        "forward_vs_speed_derivative_corr": float(np.corrcoef(values[:, 0], dsdt)[0, 1]) if np.isfinite(dsdt).sum() > 2 and np.std(values[:, 0]) > 0 and np.std(dsdt) > 0 else None,
    }


def main() -> None:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    train = list(manifest["train"])
    validation = list(manifest["validation"])
    processed = {p.stem: p for p in PROCESSED.glob("*.csv")}
    analyzed = train + validation
    frames = {name: pd.read_csv(processed[name]) for name in analyzed}
    all_df = pd.concat(list(frames.values()), ignore_index=True)
    per_trip = {name: trip_metrics(frames[name]) for name in analyzed}

    # Axis consistency is assessed only on stationary gravity and non-stationary
    # variance; it cannot prove the physical mounting transform.
    axis = {}
    for name, df in frames.items():
        stationary = df["vehicle_speed"] < 0.5
        axis[name] = {
            "stationary_accel_median": [float(df.loc[stationary, c].median()) for c in ACC],
            "full_trip_accel_std": [float(df[c].std()) for c in ACC],
            "gyro_median": [float(df[c].median()) for c in GYRO],
        }

    report = {
        "verdict": "D",
        "verdict_text": "Frame provenance is insufficient and the corpus cannot responsibly be used for final training.",
        "scope": {"processed_trips": 32, "analyzed_splits": ["train", "validation"], "locked_test_excluded": sorted(LOCKED), "model_or_locked_test_evaluation": False},
        "corpus": {"processed_samples": 766749, "analyzed_samples": int(len(all_df)), "train_trips": train, "validation_trips": validation, "locked_test_trips": sorted(LOCKED), "window_contract": {"sample_rate_hz": 10, "window_samples": 50, "stride": 1}},
        "preprocessing_trace": {
            "source_script": "scripts/prepare_io_vnbd.py",
            "smartphone_fields": {"accel": "ACCELEROMETER X/Y/Z (m/s²)", "gyro_variant_b": "GYROSCOPE X/Y/Z (rad/s)", "gyro_variant_a": "GYROSCOPE Yaw/Pitch/Roll (rad/s)"},
            "frame_operation": "direct column copy; no rotation matrix, quaternion, calibration record, or per-trip transform is applied or persisted",
            "pairing": "S-<trip> paired with V-<trip>; both truncated to min(len(S), len(V)) by row index",
            "resampling": "numeric fields linearly interpolated to a 100 ms grid; no additional synchronization offset",
            "filtering": "model-time inference applies causal Hampel filtering to processed channels; processed corpus itself contains raw copied channels",
        },
        "frame_provenance": {
            "claimed_contract": ["forward", "lateral", "up", "gyro_forward", "gyro_left", "gyro_up"],
            "units": {"acceleration": "m/s²", "gyro": "rad/s", "gravity_included": True},
            "classification": "insufficiently_verified",
            "evidence_for": ["adapter comments assert IO-VNBD vehicle-frame X/Y/Z identity", "processed distributions can be checked for gravity-like magnitude"],
            "evidence_against": ["raw-to-vehicle transform is not encoded", "no calibration matrix/quaternion or mounting metadata is saved", "the preparation code does not transform axes", "gyro Variant A is renamed yaw/pitch/roll to x/y/z without a verified physical convention"],
            "required_resolution": ["obtain authoritative IO-VNBD sensor frame/mounting specification or reconstruct and validate the transform against vehicle references", "persist transform provenance/version per trip", "verify signs and axis semantics on representative train/validation motion before retraining"],
        },
        "label_provenance": {
            "source_file": "V-<trip> vehicle CSV",
            "source_field": "Indicated Vehicle Speed (km/hr)",
            "processed_field": "vehicle_speed",
            "unit_conversion": "vehicle_speed = source_field / 3.6 only in model preprocessing; processed CSV remains km/hr",
            "meaning": "vehicle/controller indicated speed, not independently verified ground-truth velocity; it is distinct from the vehicle CSV Velocity (km/hr) field",
            "synchronization": "paired by row index after common-length truncation; then target is linearly interpolated with the IMU onto the smartphone time grid",
            "adequacy": "reference field identity is clear, but independent synchronization offset/clock error and label semantics are not independently validated",
        },
        "leakage_audit": {
            "split_manifest": str(MANIFEST.relative_to(ROOT)),
            "trip_overlap": False,
            "window_overlap_across_splits": False,
            "normalization_policy": "fit_normalization uses train frames only",
            "training_loader_policy": "load_train_validation_trips excludes test trips",
            "observed_leakage": [],
            "caveat": "the current processed preparation is not provenance-rich enough to independently prove S/V synchronization correctness",
        },
        "physical_sanity_train_validation": {"aggregate": trip_metrics(all_df), "per_trip": per_trip, "axis_consistency": axis},
        "deployment_contract_comparison": {
            "training": {"order": ["forward", "lateral", "up", "gyro_forward", "gyro_left", "gyro_up"], "shape": [1, 6, 50], "rate_hz": 10, "accel_units": "m/s²", "gyro_units": "rad/s", "gravity_included": True},
            "android": {"canonical_order": ["vehicleAccelForward", "vehicleAccelLeft", "vehicleAccelUp", "vehicleGyroLeft", "vehicleGyroForward", "vehicleGyroUp"], "shape": [1, 6, 50], "rate_hz": 10, "accel_units": "m/s²", "gyro_units": "rad/s", "gravity_included": "raw accel path gravity-inclusive"},
            "mismatches": ["Android CanonicalImuSample.toFeatureArray must be verified: its named gyro order is left, forward, up while the training contract is forward, left, up", "Android input is vehicle-frame only after calibration, while corpus provenance does not encode the transform", "Android TCN is calibration-gated; corpus preparation has no equivalent runtime calibration state"],
        },
        "recommendation": "Do not retrain yet. Resolve frame provenance and label synchronization first, then regenerate only train/validation processed views under an explicitly verified transform and recompute train-only normalization. Keep Vta1a/Vta1b/Y1 frozen and untouched.",
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "io_vnbd_frame_provenance_audit.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = "# IO-VNBD frame provenance audit\n\n"
    md += f"## Verdict\n\n**D — {report['verdict_text']}**\n\n"
    md += "This is a train/validation-only audit. Locked test trips were excluded from quantitative checks and no model evaluation was run.\n\n"
    md += "## Key findings\n\n"
    md += "- The preparation code copies smartphone accelerometer/gyro columns directly; it does not apply or persist a phone-to-vehicle transform.\n"
    md += "- The target is `V-<trip>` **Indicated Vehicle Speed (km/hr)**, paired by row index, then linearly interpolated to the smartphone time grid.\n"
    md += "- The six-channel semantic contract and Android deployment contract have an apparent gyro ordering risk: training is forward/left/up, while Android canonical fields are left/forward/up before feature serialization is verified.\n"
    md += "- Split and normalization code show no observed train/validation/test leakage, but synchronization correctness is not independently established.\n\n"
    md += "## Detailed machine-readable results\n\nSee the accompanying JSON for per-trip physical statistics, axis consistency, provenance evidence, and the deployment comparison.\n\n"
    md += "## Recommendation\n\n" + report["recommendation"] + "\n"
    (OUT / "io_vnbd_frame_provenance_audit.md").write_text(md, encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "analyzed_trips": len(analyzed), "analyzed_samples": len(all_df), "locked_test_excluded": sorted(LOCKED), "reports": [str(OUT / "io_vnbd_frame_provenance_audit.json"), str(OUT / "io_vnbd_frame_provenance_audit.md")]}, indent=2))


if __name__ == "__main__":
    main()
