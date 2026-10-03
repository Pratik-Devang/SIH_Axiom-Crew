"""Non-destructive IO-VNBD source clock/frame alignment prototype.

The optimization path deliberately never reads speed, GPS speed, wheel speed,
or any target-derived quantity. Speed fields are loaded only in the separate
label-QA stage after alignment parameters have been estimated.
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
OUT = ROOT / "artifacts" / "evaluation" / "io_vnbd_alignment"
REPORT = ROOT / "artifacts" / "evaluation"
LOCKED = {"Vta1a", "Vta1b", "Y1"}
SAMPLE_TRIPS = ["S1", "S2", "S3a", "S3b", "S4", "M", "Vfa01", "Vfa02"]


def _files() -> tuple[dict[str, Path], dict[str, Path]]:
    files = list(RAW.rglob("*.csv"))
    phones = {p.stem[2:].lower(): p for p in files if p.stem.lower().startswith("s-")}
    vehicles = {p.stem[2:].lower(): p for p in files if p.stem.lower().startswith("v-")}
    return phones, vehicles


def split_monotonic(t: np.ndarray, *, max_gap_s: float = 0.5) -> list[dict]:
    """Return valid ranges; reset/gap samples begin a new segment."""
    t = np.asarray(t, dtype=float)
    if len(t) == 0:
        return []
    bad = (~np.isfinite(t)) | (np.r_[False, np.diff(t) <= 0]) | (np.r_[False, np.diff(t) > max_gap_s])
    starts = [0] + [int(i) for i in np.flatnonzero(bad[1:]) + 1]
    segments = []
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(t)
        # A discontinuity belongs to the segment after it. Drop the invalid
        # boundary sample itself when it is a reset/non-monotonic row.
        if start > 0 and (not np.isfinite(t[start]) or t[start] <= t[start - 1] or t[start] - t[start - 1] > max_gap_s):
            start += 1
        if end - start >= 20:
            segments.append({"start_index": start, "end_index_exclusive": end, "rows": end - start, "start_s": float(t[start]), "end_s": float(t[end - 1]), "duration_s": float(t[end - 1] - t[start])})
    return segments


def _safe_corr(a: np.ndarray, b: np.ndarray) -> float | None:
    good = np.isfinite(a) & np.isfinite(b)
    if good.sum() < 20 or np.std(a[good]) == 0 or np.std(b[good]) == 0:
        return None
    return float(np.corrcoef(a[good], b[good])[0, 1])


def _rmse(a: np.ndarray, b: np.ndarray) -> float | None:
    good = np.isfinite(a) & np.isfinite(b)
    return float(np.sqrt(np.mean((a[good] - b[good]) ** 2))) if good.sum() else None


def _mad(a: np.ndarray) -> float | None:
    a = a[np.isfinite(a)]
    return float(np.median(np.abs(a - np.median(a)))) if len(a) else None


def _resample(t_src: np.ndarray, x_src: np.ndarray, t_new: np.ndarray) -> np.ndarray:
    good = np.isfinite(t_src) & np.isfinite(x_src)
    if good.sum() < 2:
        return np.full(len(t_new), np.nan)
    return np.interp(t_new, t_src[good], x_src[good], left=np.nan, right=np.nan)


def _rotation_kabsch(phone_vectors: np.ndarray, vehicle_vectors: np.ndarray, weights: np.ndarray) -> np.ndarray:
    """Constrained proper rotation R where vehicle = R @ phone.

    Inputs include a gravity anchor and vehicle longitudinal/lateral dynamic
    references. SVD is only used to solve the explicitly defined orthogonal
    Procrustes problem; it is not an unconstrained black-box fit.
    """
    good = np.isfinite(phone_vectors).all(axis=1) & np.isfinite(vehicle_vectors).all(axis=1) & np.isfinite(weights)
    if good.sum() < 20:
        raise ValueError("fewer than 20 finite vector correspondences")
    h = (vehicle_vectors[good] * weights[good, None]).T @ phone_vectors[good]
    u, _, vt = np.linalg.svd(h)
    d = np.eye(3)
    d[2, 2] = np.linalg.det(u @ vt)
    return u @ d @ vt


def _source_arrays(phone: pd.DataFrame, vehicle: pd.DataFrame) -> dict:
    phone.columns = phone.columns.astype(str).str.strip()
    vehicle.columns = vehicle.columns.astype(str).str.strip()
    ptime = pd.to_numeric(phone["TIME SINCE START (ms)"], errors="coerce").to_numpy(float) / 1000.0
    vtime = pd.to_numeric(vehicle["Time Since Start of Day (seconds)"], errors="coerce").to_numpy(float)
    pa = phone[["ACCELEROMETER X (m/s²)", "ACCELEROMETER Y (m/s²)", "ACCELEROMETER Z (m/s²)"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
    pg = phone[["GRAVITY X (m/s²)", "GRAVITY Y (m/s²)", "GRAVITY Z (m/s²)"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
    if "GYROSCOPE X (rad/s)" in phone:
        gyro = phone[["GYROSCOPE X (rad/s)", "GYROSCOPE Y (rad/s)", "GYROSCOPE Z (rad/s)"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
        gyro_schema = "B_x_y_z"
    else:
        gyro = phone[["GYROSCOPE Yaw (rad/s)", "GYROSCOPE Pitch (rad/s)", "GYROSCOPE Roll (rad/s)"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
        gyro_schema = "A_yaw_pitch_roll"
    vl = pd.to_numeric(vehicle["Indicated Longitudinal Acceleration (g)"], errors="coerce").to_numpy(float) * 9.80665
    vlat = pd.to_numeric(vehicle["Indicated Lateral Acceleration (g)"], errors="coerce").to_numpy(float) * 9.80665
    vyaw = pd.to_numeric(vehicle["Yaw Rate (deg/sec)"], errors="coerce").to_numpy(float) * np.pi / 180.0
    return {"ptime": ptime, "vtime": vtime, "accel": pa, "gravity": pg, "gyro": gyro, "gyro_schema": gyro_schema, "vehicle_long": vl, "vehicle_lat": vlat, "vehicle_yaw": vyaw}


def estimate_sync(p: dict, ps: dict, vs: dict) -> dict:
    """Estimate vehicle_time ~= phone_time + offset using rotation-invariant dynamics."""
    pt = p["ptime"][ps["start_index"]:ps["end_index_exclusive"]]
    vt = p["vtime"][vs["start_index"]:vs["end_index_exclusive"]]
    # Gravity removal is source-provided and target-independent. Norm of the
    # dynamic acceleration is rotation invariant; vehicle dynamics use CAN
    # longitudinal/lateral acceleration magnitude.
    pdyn = p["accel"][ps["start_index"]:ps["end_index_exclusive"]] - p["gravity"][ps["start_index"]:ps["end_index_exclusive"]]
    p_sig = np.linalg.norm(pdyn, axis=1)
    v_sig = np.hypot(p["vehicle_long"][vs["start_index"]:vs["end_index_exclusive"],], p["vehicle_lat"][vs["start_index"]:vs["end_index_exclusive"]])
    # Relative clocks start at their own segment origin. Search a bounded
    # constant offset; no speed or GPS fields are accessed here.
    p_rel, v_rel = pt - pt[0], vt - vt[0]
    if min(p_rel[-1], v_rel[-1]) < 3.0:
        raise ValueError("insufficient temporal overlap")
    base = np.arange(0.0, min(p_rel[-1], v_rel[-1]) + 1e-9, 0.1)
    best = None
    for lag in np.arange(-30.0, 30.01, 0.1):
        pv = _resample(p_rel, p_sig, base + lag)
        vv = _resample(v_rel, v_sig, base)
        corr = _safe_corr(pv, vv)
        if corr is not None and (best is None or corr > best["correlation"]):
            best = {"offset_s": float(lag), "correlation": corr}
    if best is None:
        raise ValueError("dynamic signals have insufficient variation")
    aligned_p = _resample(p_rel, p_sig, base + best["offset_s"])
    aligned_v = _resample(v_rel, v_sig, base)
    zero_p = _resample(p_rel, p_sig, base)
    halves = []
    for lo, hi in [(0, len(base) // 2), (len(base) // 2, len(base))]:
        cands = []
        for lag in np.arange(best["offset_s"] - 2.0, best["offset_s"] + 2.01, 0.1):
            c = _safe_corr(_resample(p_rel, p_sig, base[lo:hi] + lag), aligned_v[lo:hi])
            if c is not None: cands.append((c, lag))
        if cands: halves.append(max(cands))
    drift_s = float(halves[-1][1] - halves[0][1]) if len(halves) == 2 else None
    return {"method": "grid_search_cross_correlation", "signals": ["norm(phone_accelerometer - phone_gravity)", "norm(vehicle_indicated_longitudinal/lateral_acceleration)"], "offset_vehicle_minus_phone_s": best["offset_s"], "constant_offset_search_range_s": [-30.0, 30.0], "search_step_s": 0.1, "correlation_before": _safe_corr(zero_p, aligned_v), "correlation_after": _safe_corr(aligned_p, aligned_v), "rmse_before_mps2": _rmse(zero_p, aligned_v), "rmse_after_mps2": _rmse(aligned_p, aligned_v), "half_window_lag_change_s": drift_s, "drift_assessment": "constant offset candidate; investigate affine/piecewise map" if drift_s is not None and abs(drift_s) > 0.2 else "constant offset adequate for this diagnostic window"}


def estimate_rotation(p: dict, ps: dict, vs: dict, sync: dict) -> dict:
    pslice = slice(ps["start_index"], ps["end_index_exclusive"])
    vslice = slice(vs["start_index"], vs["end_index_exclusive"])
    pt = p["ptime"][pslice]
    vt = p["vtime"][vslice]
    pt_rel, vt_rel = pt - pt[0], vt - vt[0]
    lag = sync["offset_vehicle_minus_phone_s"]
    # Use vehicle-relative time and place phone time into vehicle time.
    grid = np.arange(max(vt_rel[0], pt_rel[0] + lag), min(vt_rel[-1], pt_rel[-1] + lag) + 1e-9, 0.1)
    phone_t = pt_rel + lag
    pa = np.column_stack([_resample(phone_t, p["accel"][pslice, i], grid) for i in range(3)])
    pg = np.column_stack([_resample(phone_t, p["gravity"][pslice, i], grid) for i in range(3)])
    vl = _resample(vt_rel, p["vehicle_long"][vslice], grid)
    vlat = _resample(vt_rel, p["vehicle_lat"][vslice], grid)
    # Gravity anchor: phone gravity vector -> vehicle +Up. Dynamic vectors are
    # the phone gravity-removed acceleration -> vehicle [longitudinal,lateral,0].
    g = np.nanmedian(pg, axis=0); g = g / np.linalg.norm(g)
    dyn = pa - pg
    good = np.isfinite(dyn).all(1) & np.isfinite(vl) & np.isfinite(vlat)
    if good.sum() < 30:
        raise ValueError("insufficient synchronized dynamic samples")
    phone_vec = np.vstack([dyn[good], np.repeat(g[None, :], good.sum(), axis=0)])
    vehicle_vec = np.vstack([np.column_stack([vl[good], vlat[good], np.zeros(good.sum())]), np.tile([0.0, 0.0, 1.0], (good.sum(), 1))])
    weights = np.r_[np.ones(good.sum()), np.full(good.sum(), 3.0)]
    R = _rotation_kabsch(phone_vec, vehicle_vec, weights)
    transformed = (R @ dyn[good].T).T
    ref = np.column_stack([vl[good], vlat[good], np.zeros(good.sum())])
    res = transformed - ref
    # Euler values for R = Rz(yaw) Ry(pitch) Rx(roll), vehicle=R*phone.
    pitch = np.arcsin(np.clip(-R[2, 0], -1, 1)); yaw = np.arctan2(R[1, 0], R[0, 0]); roll = np.arctan2(R[2, 1], R[2, 2])
    quality = float(np.clip(0.5 * ((1 + (_safe_corr(transformed[:, 0], ref[:, 0]) or 0)) / 2) + 0.5 * ((1 + (_safe_corr(transformed[:, 1], ref[:, 1]) or 0)) / 2), 0, 1))
    return {"method": "gravity_anchor_plus_weighted_kabsch_dynamic_acceleration", "rotation_direction": "vehicle_vector = R_vehicle_from_phone @ phone_vector", "source_convention": "phone Android sensor axes; gravity vector is positive +g in source samples", "vehicle_convention": "+Forward, +Left, +Up", "matrix_vehicle_from_phone": R.tolist(), "euler_yaw_pitch_roll_deg": [float(np.degrees(yaw)), float(np.degrees(pitch)), float(np.degrees(roll))], "gravity_phone_unit": g.tolist(), "samples": int(good.sum()), "confidence": quality, "poorly_constrained": bool(good.sum() < 100 or quality < 0.35 or np.std(vl[good]) < 0.2 or np.std(vlat[good]) < 0.2), "residual_mps2": {"forward_rmse": _rmse(transformed[:, 0], ref[:, 0]), "lateral_rmse": _rmse(transformed[:, 1], ref[:, 1]), "up_rmse": _rmse(transformed[:, 2], ref[:, 2]), "forward_median_abs": _mad(res[:, 0]), "lateral_median_abs": _mad(res[:, 1]), "up_median_abs": _mad(res[:, 2]), "forward_corr": _safe_corr(transformed[:, 0], ref[:, 0]), "lateral_corr": _safe_corr(transformed[:, 1], ref[:, 1])}}


def validate_rotation(p: dict, ps: dict, vs: dict, sync: dict, rot: dict) -> dict:
    pslice = slice(ps["start_index"], ps["end_index_exclusive"])
    vslice = slice(vs["start_index"], vs["end_index_exclusive"])
    R = np.asarray(rot["matrix_vehicle_from_phone"], float)
    lag = sync["offset_vehicle_minus_phone_s"]
    pt = p["ptime"][pslice]
    vt = p["vtime"][vslice]
    pt_rel, vt_rel = pt - pt[0], vt - vt[0]
    grid = np.arange(max(vt_rel[0], pt_rel[0] + lag), min(vt_rel[-1], pt_rel[-1] + lag) + 1e-9, 0.1)
    phone_t = pt_rel + lag
    accel = np.column_stack([_resample(phone_t, p["accel"][pslice, i] - p["gravity"][pslice, i], grid) for i in range(3)])
    out = (R @ accel.T).T
    ref = np.column_stack([_resample(vt_rel, p["vehicle_long"][vslice], grid), _resample(vt_rel, p["vehicle_lat"][vslice], grid), np.zeros(len(grid))])
    gyro = np.column_stack([_resample(phone_t, p["gyro"][pslice, i], grid) for i in range(3)])
    gyro_v = (R @ gyro.T).T
    yaw_ref = _resample(vt_rel, p["vehicle_yaw"][vslice], grid)
    return {"acceleration_forward": {"correlation": _safe_corr(out[:, 0], ref[:, 0]), "rmse_mps2": _rmse(out[:, 0], ref[:, 0]), "median_abs_error_mps2": _mad(out[:, 0] - ref[:, 0])}, "acceleration_lateral": {"correlation": _safe_corr(out[:, 1], ref[:, 1]), "rmse_mps2": _rmse(out[:, 1], ref[:, 1]), "median_abs_error_mps2": _mad(out[:, 1] - ref[:, 1])}, "gravity_up": {"median_mps2": float(np.nanmedian((R @ p["gravity"].T).T[:, 2])), "expected_mps2": 9.80665}, "yaw_rate": {"correlation": _safe_corr(gyro_v[:, 2], yaw_ref), "rmse_rad_s": _rmse(gyro_v[:, 2], yaw_ref), "note": "interpretation depends on Variant A yaw/pitch/roll physical-axis semantics"}, "segment_duration_s": float(grid[-1] - grid[0]) if len(grid) else 0.0}


def label_qa(phone: pd.DataFrame, vehicle: pd.DataFrame) -> dict:
    phone.columns = phone.columns.astype(str).str.strip(); vehicle.columns = vehicle.columns.astype(str).str.strip()
    a = pd.to_numeric(vehicle["Indicated Vehicle Speed (km/hr)"], errors="coerce").to_numpy(float)
    b = pd.to_numeric(vehicle["Velocity (km/hr)"], errors="coerce").to_numpy(float)
    good = np.isfinite(a) & np.isfinite(b)
    d = a[good] - b[good]
    return {"primary": "Indicated Vehicle Speed (km/hr)", "independent_reference": "Velocity (km/hr) from vehicle GPS/VBOX", "count": int(good.sum()), "bias_kmh": float(np.mean(d)) if len(d) else None, "mae_kmh": float(np.mean(np.abs(d))) if len(d) else None, "rmse_kmh": float(np.sqrt(np.mean(d * d))) if len(d) else None, "correlation": _safe_corr(a, b), "outlier_over_10_kmh": int(np.sum(np.abs(d) > 10.0)), "wheel_speed_status": "not converted: wheel radius/odometry calibration metadata is absent", "target_replacement": "not automatic"}


def main() -> None:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    allowed = set(manifest["train"] + manifest["validation"])
    assert not (set(SAMPLE_TRIPS) & LOCKED)
    phones, vehicles = _files()
    results = {}
    for name in SAMPLE_TRIPS:
        if name not in allowed:
            raise ValueError(f"sample trip {name} is outside train/validation")
        pp, vp = phones[name.lower()], vehicles[name.lower()]
        phone = pd.read_csv(pp, encoding="latin1"); vehicle = pd.read_csv(vp, encoding="latin1")
        p = _source_arrays(phone, vehicle)
        ps = split_monotonic(p["ptime"]); vs = split_monotonic(p["vtime"])
        segs = []
        for pseg in ps:
            for vseg in vs:
                try:
                    sync = estimate_sync(p, pseg, vseg)
                    rot = estimate_rotation(p, pseg, vseg, sync)
                    val = validate_rotation(p, pseg, vseg, sync, rot)
                    segs.append({"phone_segment": pseg, "vehicle_segment": vseg, "synchronization": sync, "rotation": rot, "validation": val})
                except ValueError as exc:
                    segs.append({"phone_segment": pseg, "vehicle_segment": vseg, "rejected": True, "reason": str(exc)})
        results[name] = {"source_files": {"smartphone": str(pp.relative_to(ROOT)), "vehicle": str(vp.relative_to(ROOT))}, "phone_segments": ps, "vehicle_segments": vs, "segment_attempts": segs, "label_qa": label_qa(phone, vehicle)}
    report = {"verdict": "B", "verdict_text": "Prototype methodology is implementable, but synchronization and frame quality are not yet sufficient to authorize corpus regeneration.", "scope": {"sample_trips": SAMPLE_TRIPS, "train_validation_only": True, "locked_test_excluded": sorted(LOCKED), "locked_test_opened": False, "target_used_in_alignment": False}, "alignment_method": {"clock": "bounded 0.1 s grid cross-correlation of norm(phone accelerometer - phone gravity) against norm(vehicle indicated longitudinal/lateral acceleration)", "rotation": "gravity-anchored proper rotation solved by weighted Kabsch using phone gravity and vehicle longitudinal/lateral acceleration vectors", "target_independent_signals": ["phone accelerometer", "phone gravity", "phone gyro retained for validation", "vehicle indicated longitudinal acceleration", "vehicle indicated lateral acceleration", "vehicle yaw rate retained for validation"], "not_used_for_optimization": ["Indicated Vehicle Speed", "GPS speed", "wheel speed", "future target values"]}, "coordinate_conventions": {"phone": "source Android sensor axes; Variant A gyro names are retained as yaw/pitch/roll but their physical axis mapping remains a limitation", "vehicle": "+Forward, +Left, +Up", "rotation": "vehicle = R_vehicle_from_phone @ phone", "gravity": "positive +g source gravity vector constrains vehicle +Up", "training_order_after_transform": ["forward_accel", "lateral_accel", "up_accel", "gyro_forward", "gyro_left", "gyro_up"]}, "results": results, "label_qa_policy": "performed after alignment parameter estimation only; target retained unless independently disproven", "limitations": ["source clocks exhibit resets and large duration differences; a constant offset is not universally adequate", "dynamic acceleration Kabsch is weak on straight/low-dynamics segments and cannot prove frame correctness alone", "Variant A gyro yaw/pitch/roll semantics require source-level confirmation", "wheel speed cannot be converted without wheel-radius/odometry calibration", "this prototype does not regenerate data"], "regeneration_gate": {"temporal_synchronization_reliable": False, "rotation_reliably_estimable": False, "enough_segments_constrained": False, "indicated_speed_independently_consistent": "requires review of reported QA metrics", "recommendation": "NO-GO until rejected/reset segments are handled and transform/synchronization quality thresholds are reviewed on train/validation"}, "proposed_deterministic_algorithm": ["parse raw S/V fields", "split non-monotonic/reset/gap segments", "estimate target-independent clock offset per overlapping segment", "reject insufficient overlap or excessive drift", "estimate gravity-anchored rotation from dynamic acceleration references", "validate residual/correlation and persist matrix/quality", "compare indicated speed with vehicle GPS velocity as QA", "resample only verified monotonic overlap", "construct 50-sample stride-1 windows at 10 Hz", "fit normalization on train trips only"]}
    OUT.mkdir(parents=True, exist_ok=True)
    (REPORT / "io_vnbd_alignment_prototype.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = "# IO-VNBD alignment prototype\n\n"
    md += f"## Verdict\n\n**B / NO-GO for regeneration:** {report['verdict_text']}\n\n"
    md += "Only the requested train/validation sample trips were opened. Vta1a, Vta1b, and Y1 were excluded and not opened.\n\n"
    md += "## Method\n\n"
    md += "Synchronization uses only rotation-invariant phone dynamic acceleration and vehicle indicated longitudinal/lateral acceleration. Rotation uses a positive-gravity Up anchor and a weighted, proper Kabsch solution against vehicle longitudinal/lateral dynamics. Speed, GPS speed, wheel speed, and future target values are excluded from optimization.\n\n"
    md += "## Regeneration gate\n\n"
    md += "- Temporal synchronization: **not yet reliable**; source resets and duration mismatches require segment handling.\n- Frame estimation: **not yet reliable for all segments**; low-dynamics segments are poorly constrained and Variant A gyro semantics remain ambiguous.\n- Label QA: indicated speed remains the candidate primary label; vehicle GPS velocity is reported as an independent QA reference.\n\n"
    md += "Per-segment matrices, offsets, residuals, confidence, rejection reasons, and label QA are in the JSON.\n"
    (REPORT / "io_vnbd_alignment_prototype.md").write_text(md, encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "trips": SAMPLE_TRIPS, "locked_test_opened": False, "reports": [str(REPORT / "io_vnbd_alignment_prototype.json"), str(REPORT / "io_vnbd_alignment_prototype.md")]}, indent=2))


if __name__ == "__main__":
    main()
