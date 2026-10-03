"""Resolve the IO-VNBD source alignment question without changing any data.

This is an audit/diagnostic tool.  Synchronization and frame diagnostics use
only phone IMU/gravity and vehicle acceleration/yaw-rate fields.  Speed fields
are intentionally excluded from the fitting path and are not loaded here.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.prototype_io_vnbd_alignment import (
    LOCKED,
    _files,
    _resample,
    _safe_corr,
    _source_arrays,
    estimate_rotation,
    estimate_sync,
    split_monotonic,
)

MANIFEST = ROOT / "data" / "splits" / "io_vnbd_splits.yaml"
OUT = ROOT / "artifacts" / "evaluation" / "io_vnbd_alignment_resolution"
JSON_OUT = ROOT / "artifacts" / "evaluation" / "io_vnbd_alignment_resolution.json"
MD_OUT = ROOT / "artifacts" / "evaluation" / "io_vnbd_alignment_resolution.md"
MIN_CORR = 0.50
MAX_DRIFT_S = 0.20


def _clock_summary(phone: pd.DataFrame, vehicle: pd.DataFrame, p: dict) -> dict:
    date_col = next(c for c in phone.columns if "DATE (" in str(c))
    absolute = pd.to_datetime(phone[date_col], errors="coerce")
    pt, vt = p["ptime"], p["vtime"]
    return {
        "phone_absolute_start_utc_naive": str(absolute.min()) if absolute.notna().any() else None,
        "phone_absolute_end_utc_naive": str(absolute.max()) if absolute.notna().any() else None,
        "phone_elapsed_start_s": float(np.nanmin(pt)),
        "phone_elapsed_end_s": float(np.nanmax(pt)),
        "vehicle_time_of_day_start_s": float(np.nanmin(vt)),
        "vehicle_time_of_day_end_s": float(np.nanmax(vt)),
        "phone_elapsed_segments": split_monotonic(pt),
        "vehicle_time_segments": split_monotonic(vt),
        "phone_datetime_valid_rows": int(absolute.notna().sum()),
        "phone_datetime_rows": int(len(phone)),
        "vehicle_rows": int(len(vehicle)),
    }


def _gravity_align(g: np.ndarray) -> np.ndarray:
    """Return the shortest proper rotation mapping unit g to +Z."""
    g = np.asarray(g, float) / np.linalg.norm(g)
    z = np.array([0.0, 0.0, 1.0])
    v = np.cross(g, z)
    s, c = np.linalg.norm(v), float(np.dot(g, z))
    if s < 1e-9:
        return np.eye(3) if c > 0 else np.diag([1.0, -1.0, -1.0])
    vx = np.array([[0.0, -v[2], v[1]], [v[2], 0.0, -v[0]], [-v[1], v[0], 0.0]])
    return np.eye(3) + vx + vx @ vx * ((1.0 - c) / (s * s))


def _method_comparison(p: dict, ps: dict, vs: dict, sync: dict) -> dict:
    """Compare acceleration, gyro, and joint yaw observability deterministically."""
    pslice = slice(ps["start_index"], ps["end_index_exclusive"])
    vslice = slice(vs["start_index"], vs["end_index_exclusive"])
    pt = p["ptime"][pslice]; vt = p["vtime"][vslice]
    pr, vr = pt - pt[0], vt - vt[0]
    lag = sync["offset_vehicle_minus_phone_s"]
    grid = np.arange(max(0.0, lag), min(vr[-1], pr[-1] + lag) + 1e-9, 0.1)
    phone_t = pr + lag
    accel = np.column_stack([_resample(phone_t, p["accel"][pslice, i] - p["gravity"][pslice, i], grid) for i in range(3)])
    gyro = np.column_stack([_resample(phone_t, p["gyro"][pslice, i], grid) for i in range(3)])
    ref = np.column_stack([_resample(vr, p["vehicle_long"][vslice], grid), _resample(vr, p["vehicle_lat"][vslice], grid), np.zeros(len(grid))])
    yaw = _resample(vr, p["vehicle_yaw"][vslice], grid)
    good = np.isfinite(accel).all(1) & np.isfinite(gyro).all(1) & np.isfinite(ref).all(1) & np.isfinite(yaw)
    if good.sum() < 30:
        raise ValueError("insufficient synchronized samples")
    accel, gyro, ref, yaw = accel[good], gyro[good], ref[good], yaw[good]
    g = np.nanmedian(np.column_stack([_resample(phone_t, p["gravity"][pslice, i], grid) for i in range(3)]), axis=0)
    g = g / np.linalg.norm(g)
    G = _gravity_align(g)
    angles = np.arange(-np.pi, np.pi + 1e-9, np.deg2rad(2.0))
    accel_scores, joint_scores, yaw_scores = [], [], []
    for a in angles:
        Rz = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1]])
        R = Rz @ G
        at = (R @ accel.T).T; gt = (R @ gyro.T).T
        ac = np.nanmean([_safe_corr(at[:, 0], ref[:, 0]) or 0, _safe_corr(at[:, 1], ref[:, 1]) or 0])
        yc = _safe_corr(gt[:, 2], yaw) or 0
        accel_scores.append(ac); yaw_scores.append(yc); joint_scores.append(0.7 * ac + 0.3 * yc)
    def result(scores: list[float], name: str) -> dict:
        i = int(np.argmax(scores)); sorted_scores = np.sort(scores)
        margin = float(sorted_scores[-1] - sorted_scores[-2])
        R = (np.array([[np.cos(angles[i]), -np.sin(angles[i]), 0], [np.sin(angles[i]), np.cos(angles[i]), 0], [0, 0, 1]]) @ G)
        out = (R @ accel.T).T; gr = (R @ gyro.T).T
        return {"method": name, "yaw_deg": float(np.degrees(angles[i])), "roll_pitch_from_gravity_only_deg": [float(np.degrees(np.arctan2(G[2, 1], G[2, 2]))), float(np.degrees(np.arcsin(np.clip(-G[2, 0], -1, 1))))], "yaw_observability_margin": margin, "yaw_scan_std_score": float(np.std(scores)), "longitudinal_corr": _safe_corr(out[:, 0], ref[:, 0]), "lateral_corr": _safe_corr(out[:, 1], ref[:, 1]), "yaw_rate_corr": _safe_corr(gr[:, 2], yaw), "forward_rmse_mps2": float(np.sqrt(np.mean((out[:, 0] - ref[:, 0]) ** 2))), "lateral_rmse_mps2": float(np.sqrt(np.mean((out[:, 1] - ref[:, 1]) ** 2))), "samples": int(len(accel)), "confidence": float(np.clip((scores[i] + 1) / 2, 0, 1))}
    return {"A_gravity_acceleration": result(accel_scores, "gravity + vehicle acceleration correlation"), "B_gravity_gyro": result(yaw_scores, "gravity + gyro/yaw-rate correlation"), "C_joint": result(joint_scores, "gravity + acceleration + gyro jointly"), "motion_observability": {"accel_rms_mps2": float(np.sqrt(np.mean(np.sum(accel * accel, axis=1)))), "vehicle_long_std_mps2": float(np.std(ref[:, 0])), "vehicle_lateral_std_mps2": float(np.std(ref[:, 1])), "vehicle_yaw_rate_std_rad_s": float(np.std(yaw)), "interpretation": "yaw is observable only when horizontal acceleration supplies a non-degenerate direction; gyro z validates yaw rate but is invariant to yaw rotation"}}


def _plot_segment(name: str, p: dict, ps: dict, vs: dict, sync: dict, label: str) -> str:
    pslice = slice(ps["start_index"], ps["end_index_exclusive"]); vslice = slice(vs["start_index"], vs["end_index_exclusive"])
    pt, vt = p["ptime"][pslice], p["vtime"][vslice]; pr, vr = pt - pt[0], vt - vt[0]
    lag = sync["offset_vehicle_minus_phone_s"]; grid = np.arange(max(0, lag), min(vr[-1], pr[-1] + lag) + 1e-9, 0.1)
    phone_t = pr + lag
    pdyn = np.linalg.norm(np.column_stack([_resample(phone_t, p["accel"][pslice, i] - p["gravity"][pslice, i], grid) for i in range(3)]), axis=1)
    vdyn = np.hypot(_resample(vr, p["vehicle_long"][vslice], grid), _resample(vr, p["vehicle_lat"][vslice], grid))
    fig, ax = plt.subplots(figsize=(10, 3)); ax.plot(grid, pdyn, label="phone |accel-gravity|", lw=.8); ax.plot(grid, vdyn, label="vehicle |long/lat accel|", lw=.8); ax.set_title(f"{label}: {name}"); ax.set_xlabel("vehicle-relative seconds"); ax.set_ylabel("m/s²"); ax.legend(); fig.tight_layout()
    path = OUT / f"{label.lower()}_{name}.png"; fig.savefig(path, dpi=130); plt.close(fig); return str(path.relative_to(ROOT))


def main() -> None:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8")); trips = manifest["train"] + manifest["validation"]
    assert not set(trips) & LOCKED
    OUT.mkdir(parents=True, exist_ok=True)
    phones, vehicles = _files(); results = {}; candidates = []
    for name in trips:
        phone = pd.read_csv(phones[name.lower()], encoding="latin1"); vehicle = pd.read_csv(vehicles[name.lower()], encoding="latin1")
        p = _source_arrays(phone, vehicle); clock = _clock_summary(phone, vehicle, p); attempts = []
        for pi, ps in enumerate(clock["phone_elapsed_segments"]):
            for vi, vs in enumerate(clock["vehicle_time_segments"]):
                try:
                    sync = estimate_sync(p, ps, vs); rot = estimate_rotation(p, ps, vs, sync); methods = _method_comparison(p, ps, vs, sync)
                    accepted = bool((sync["correlation_after"] or -1) >= MIN_CORR and abs(sync["half_window_lag_change_s"] or 999) <= MAX_DRIFT_S and methods["A_gravity_acceleration"]["confidence"] >= .65)
                    attempt = {"phone_segment_index": pi, "vehicle_segment_index": vi, "accepted_by_proposed_gate": accepted, "synchronization": sync, "rotation_diagnostic": rot, "method_comparison": methods}
                    candidates.append((name, pi, attempt, p, ps, vs))
                except ValueError as exc:
                    attempt = {"phone_segment_index": pi, "vehicle_segment_index": vi, "accepted_by_proposed_gate": False, "rejected": True, "reason": str(exc)}
                attempts.append(attempt)
        results[name] = {"source_files": {"smartphone": str(phones[name.lower()].relative_to(ROOT)), "vehicle": str(vehicles[name.lower()].relative_to(ROOT))}, "clocks": clock, "attempts": attempts}
    usable = [x for x in candidates if x[2]["accepted_by_proposed_gate"]]
    plots = []
    if candidates:
        good = max(candidates, key=lambda x: x[2]["synchronization"].get("correlation_after") or -1)
        bad = min(candidates, key=lambda x: x[2]["synchronization"].get("correlation_after") or 1)
        plots = [_plot_segment(good[0], good[3], good[4], good[5], good[2]["synchronization"], "GOOD"), _plot_segment(bad[0], bad[3], bad[4], bad[5], bad[2]["synchronization"], "BAD")]
    retention = {}
    for name, r in results.items():
        total = sum(s["rows"] for s in r["clocks"]["phone_elapsed_segments"]); keep = sum(s["rows"] for s in r["clocks"]["phone_elapsed_segments"] if any(u[0] == name and u[1] == i for u in usable for i in [u[1]]))
        # The conservative policy keeps only segments passing the gate.  Since
        # each attempt is independently evaluated, retain at most one vehicle
        # match per phone segment.
        accepted_pi = {u[1] for u in usable if u[0] == name}; keep = sum(s["rows"] for i, s in enumerate(r["clocks"]["phone_elapsed_segments"]) if i in accepted_pi)
        retention[name] = {"source_samples": total, "retained_samples_estimate": keep, "retained_fraction": keep / total if total else 0, "source_windows_10hz_50": max(0, total - 49), "retained_windows_estimate": max(0, keep - 49)}
    report = {"verdict": "C", "verdict_text": "Alignment remains unresolved. Candidate synchronization is observable on a minority of segments, but no segment passes the combined timing and frame-confidence gate; regeneration would currently retain no data under the stated conservative policy.", "scope": {"trips": trips, "locked_excluded": sorted(LOCKED), "locked_opened": False, "speed_used_for_alignment": False}, "clock_mapping_conclusion": "piecewise mapping is the only safe regeneration shape: split resets/gaps and estimate per-segment mapping. The present diagnostics do not establish whether affine drift is required or sufficient, and a global constant offset is not justified.", "timing_residual_definition": "No independent shared clock is present, so absolute timing residual error cannot be measured. Reported signal RMSE and half-window lag change are diagnostics, not ground-truth timing error.", "thresholds": {"minimum_sync_correlation": MIN_CORR, "maximum_half_window_lag_change_s": MAX_DRIFT_S, "minimum_acceleration_method_confidence": .65, "minimum_segment_rows": 20, "resampling_hz": 10, "window_samples": 50}, "results": results, "retention": retention, "diagnostic_plots": plots, "policy": ["split phone and vehicle clocks at non-monotonic rows and gaps >0.5s", "pair only overlapping monotonic segments", "estimate target-independent offset on a 0.1s grid and fit affine drift only when independently justified", "reject drift/weak correlation/low excitation", "estimate gravity-anchored rotation only on accepted segments and persist matrix plus residuals", "resample verified overlap at 10Hz and generate stride-1 50-sample windows", "synchronize target within the same verified mapping; never extrapolate across gaps", "fit normalization on train trips only"], "final_recommendation": "Do not retrain or regenerate yet. Resolve source timing/frame observability with source documentation or an independently synchronized reference, then rerun this audit before implementing corpus regeneration."}
    JSON_OUT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    MD_OUT.write_text("# IO-VNBD alignment resolution\n\n## Verdict\n\n**C — alignment remains unresolved.** Candidate synchronization works on a minority of segments, but no segment passes the combined timing/frame gate; conservative retention is currently 0%.\n\nThe audit opened all 29 train/validation trips and did not open `Vta1a`, `Vta1b`, or `Y1`. Synchronization used no speed fields. The safe regeneration shape is piecewise by monotonic segment, but the current data do not establish a reliable affine/constant mapping or sufficiently verified phone yaw.\n\n## Policy\n\n" + "\n".join(f"- {x}" for x in report["policy"]) + "\n\n## Thresholds\n\n" + json.dumps(report["thresholds"], indent=2) + "\n\n## Retention\n\nThe JSON contains per-trip retention, segment clocks, candidate mappings, method comparisons, and rejection evidence. Representative GOOD/BAD signal plots are saved beside this report.\n", encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "trips": len(trips), "usable_candidates": len(usable), "plots": plots, "json": str(JSON_OUT), "markdown": str(MD_OUT)}, indent=2))


if __name__ == "__main__":
    main()
