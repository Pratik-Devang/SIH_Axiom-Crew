"""Read-only IO-VNBD corpus audit. Never writes to data or loads model artifacts."""
from __future__ import annotations
import argparse, json, math
from pathlib import Path
import numpy as np
import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
PROCESSED = ROOT / "data" / "processed" / "io_vnbd" / "trips"
MANIFEST = ROOT / "data" / "splits" / "io_vnbd_splits.yaml"
TEST = {"Vta1a", "Vta1b", "Y1"}
IMU = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
BINS = [(0, 5), (5, 20), (20, 40), (40, 60), (60, 80), (80, math.inf)]

def speed_bin(kmh: float) -> str:
    for lo, hi in BINS:
        if lo <= kmh < hi: return f"{lo}-{hi if math.isfinite(hi) else 'plus'}"
    return "unknown"

def motion_state(speed: np.ndarray) -> np.ndarray:
    gradient = np.gradient(speed)
    return np.where(speed < 0.5, "stationary", np.where(gradient > 0.5, "acceleration", np.where(gradient < -0.5, "braking", "cruising")))

def audit_frame(df: pd.DataFrame) -> dict:
    out = {"rows": int(len(df)), "missing_channels": [c for c in IMU if c not in df], "nan_inf": {}, "duplicate_timestamps": 0, "non_monotonic_timestamps": 0, "timestamp_gaps_over_0_2s": 0, "windows": max(0, len(df)-49), "speed_bins": {}, "motion_states": {}}
    for c in IMU + ["vehicle_speed", "time_since_start_s"]:
        if c in df:
            values = pd.to_numeric(df[c], errors="coerce").to_numpy(float)
            out["nan_inf"][c] = int((~np.isfinite(values)).sum())
    if "time_since_start_s" in df:
        t = pd.to_numeric(df["time_since_start_s"], errors="coerce").to_numpy(float); dt = np.diff(t)
        out["duplicate_timestamps"] = int((dt == 0).sum()); out["non_monotonic_timestamps"] = int((dt < 0).sum()); out["timestamp_gaps_over_0_2s"] = int((dt > 0.2).sum()); out["duration_s"] = float(t[-1]-t[0]) if len(t)>1 else 0.0
    if "vehicle_speed" in df:
        speed = pd.to_numeric(df["vehicle_speed"], errors="coerce").to_numpy(float); kmh = speed
        out["speed_bins"] = {k: int(np.sum([speed_bin(x)==k for x in kmh])) for k in [speed_bin(x) for x in [2,10,30,50,70,90]]}
        states = motion_state(kmh); out["motion_states"] = {k: int((states==k).sum()) for k in sorted(set(states))}
        out["suspicious_speed_values"] = int((~np.isfinite(speed) | (speed < 0) | (speed > 200)).sum())
        out["stationary_windows"] = int((kmh < 0.5).sum())
    if all(c in df for c in IMU):
        values = df[IMU].apply(pd.to_numeric, errors="coerce").to_numpy(float); out["suspicious_imu_values"] = int((~np.isfinite(values).all(axis=1) | (np.abs(values)>100).any(axis=1)).sum())
    return out

def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--output-dir", default=str(ROOT/"artifacts"/"evaluation")); args=parser.parse_args()
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8")); processed = {p.stem:p for p in PROCESSED.glob("*.csv")}
    expected = set(sum(manifest.values(), [])); available = set(processed); excluded = sorted(available-expected)
    per_trip = {}; all_frames = {}
    for name, path in sorted(processed.items()):
        df = pd.read_csv(path); all_frames[name] = df; per_trip[name] = audit_frame(df)
    train_names = manifest["train"]; val_names = manifest["validation"]
    train_rows = sum(per_trip[n]["rows"] for n in train_names if n in per_trip); all_rows=sum(x["rows"] for x in per_trip.values())
    train_windows=sum(per_trip[n]["windows"] for n in train_names if n in per_trip)
    quality = {"nan_inf": int(sum(sum(x["nan_inf"].values()) for x in per_trip.values())), "missing_channels": sorted(set(sum((x["missing_channels"] for x in per_trip.values()), []))), "timestamp_duplicates": int(sum(x["duplicate_timestamps"] for x in per_trip.values())), "timestamp_non_monotonic": int(sum(x["non_monotonic_timestamps"] for x in per_trip.values())), "timestamp_gaps_over_0_2s": int(sum(x["timestamp_gaps_over_0_2s"] for x in per_trip.values()))}
    report={"source":{"processed_dir":str(PROCESSED),"manifest":str(MANIFEST),"raw_dir":str(ROOT/"data"/"raw"/"io_vnbd"),"available_processed_trips":sorted(available),"additional_unassigned_trips":excluded},"split":{"train":train_names,"validation":val_names,"test":sorted(TEST)},"dataset_size":{"total_trips":len(available),"total_samples":all_rows,"train_samples":train_rows,"train_windows_stride1":train_windows,"usable_duration_s":float(sum(x.get("duration_s",0) for x in per_trip.values()))},"quality":quality,"frame_contract":{"channels":IMU,"semantic_order":["forward_accel","lateral_accel","up_accel","gyro_forward","gyro_left","gyro_up"],"units":{"accel":"m/s^2","gyro":"rad/s"},"gravity_included":True,"vehicle_frame_transform_verified":False,"reason":"processed CSVs contain vehicle-aligned fields but raw transform provenance is not encoded per row"},"label_audit":{"vehicle_speed_source":"Indicated Vehicle Speed (km/hr), converted to vehicle_speed in preparation","independent_reference_comparison":"not performed; no independent reference field was assumed"},"per_trip":per_trip}
    out=Path(args.output_dir); out.mkdir(parents=True,exist_ok=True); (out/"io_vnbd_dataset_audit.json").write_text(json.dumps(report,indent=2),encoding="utf-8"); (out/"io_vnbd_dataset_audit.md").write_text("# IO-VNBD Dataset Audit\n\n"+json.dumps({k:report[k] for k in ['dataset_size','quality','split','frame_contract','label_audit']},indent=2),encoding="utf-8"); print(json.dumps({"json":str(out/"io_vnbd_dataset_audit.json"),"trips":len(available),"train_windows":train_windows,"additional":excluded},indent=2))
if __name__ == "__main__": main()
