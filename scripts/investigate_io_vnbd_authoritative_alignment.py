"""Documentation/source forensics for IO-VNBD synchronization provenance.

No fitting, speed-based alignment, corpus regeneration, model work, or locked
test access occurs here.  The report combines the existing train/validation
source audit with raw-file structure and timestamp-semantic checks.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw" / "io_vnbd" / "Synchronised V abd S datasets"
MANIFEST = ROOT / "data" / "splits" / "io_vnbd_splits.yaml"
SOURCE_AUDIT = ROOT / "artifacts" / "evaluation" / "io_vnbd_source_reconstruction_audit.json"
OUT = ROOT / "artifacts" / "evaluation"
LOCKED = {"Vta1a", "Vta1b", "Y1"}


def _hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _all_named(prefix: str, trip: str) -> list[Path]:
    return sorted(p for p in RAW.rglob(f"{prefix}-{trip}*.csv") if p.is_file())


def _timestamp_semantics(phone_path: Path, vehicle_path: Path) -> dict:
    phone = pd.read_csv(phone_path, encoding="latin1")
    vehicle = pd.read_csv(vehicle_path, encoding="latin1")
    phone.columns = phone.columns.astype(str).str.strip(); vehicle.columns = vehicle.columns.astype(str).str.strip()
    elapsed = pd.to_numeric(phone["TIME SINCE START (ms)"], errors="coerce") / 1000.0
    absolute = pd.to_datetime(phone["DATE (YYYY-MO-DD HH-MI-SS_SSS)"], format="%Y-%m-%d %H:%M:%S:%f", errors="coerce")
    vtime = pd.to_numeric(vehicle["Time Since Start of Day (seconds)"], errors="coerce")
    return {
        "phone_elapsed_start_end_s": [float(elapsed.iloc[0]), float(elapsed.iloc[-1])],
        "phone_elapsed_duration_s": float(elapsed.iloc[-1] - elapsed.iloc[0]),
        "phone_elapsed_nonpositive_deltas": int((elapsed.diff().dropna() <= 0).sum()),
        "phone_elapsed_large_gaps_over_0_5s": int((elapsed.diff().dropna() > 0.5).sum()),
        "phone_absolute_start_end": [str(absolute.iloc[0]), str(absolute.iloc[-1])],
        "phone_absolute_duration_s": float((absolute.iloc[-1] - absolute.iloc[0]).total_seconds()) if absolute.notna().all() else None,
        "absolute_datetime_nonpositive_deltas": int((absolute.diff().dt.total_seconds().dropna() <= 0).sum()),
        "vehicle_time_start_end_s": [float(vtime.iloc[0]), float(vtime.iloc[-1])],
        "vehicle_time_duration_s": float(vtime.iloc[-1] - vtime.iloc[0]),
        "vehicle_time_nonpositive_deltas": int((vtime.diff().dropna() <= 0).sum()),
        "vehicle_sample_period_median_s": float(pd.to_numeric(vehicle["Sample period (seconds)"], errors="coerce").median()),
    }


def main() -> None:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    trips = manifest["train"] + manifest["validation"]
    assert not set(trips) & LOCKED
    audit = json.loads(SOURCE_AUDIT.read_text(encoding="utf-8"))
    pairs = {x["trip"]: x for x in audit["pairs_train_validation"]}
    structural = {}
    duplicate_groups = []
    for trip in trips:
        sfiles, vfiles = _all_named("S", trip), _all_named("V", trip)
        hashes = {"smartphone": [{"path": str(p.relative_to(ROOT)), "sha256": _hash(p)} for p in sfiles], "vehicle": [{"path": str(p.relative_to(ROOT)), "sha256": _hash(p)} for p in vfiles]}
        if len(vfiles) > 1 and len({x["sha256"] for x in hashes["vehicle"]}) == 1:
            duplicate_groups.append(trip)
        pair = pairs[trip]
        structural[trip] = {"smartphone_files": hashes["smartphone"], "vehicle_files": hashes["vehicle"], "selected_pair_folder": str(Path(pair["smartphone_file"]).parent), "selected_files_share_folder": Path(pair["smartphone_file"]).parent == Path(pair["vehicle_file"]).parent, "rows": [pair["smartphone_rows"], pair["vehicle_rows"]], "timestamps": _timestamp_semantics(ROOT / pair["smartphone_file"], ROOT / pair["vehicle_file"])}
    report = {
        "verdict": "C",
        "verdict_text": "No authoritative, reproducible synchronization/alignment procedure can be recovered from the distributed source. The authors provide a manually synchronized collection and physical acquisition description, but not the per-recording timing map, dropped-sample map, or numeric phone-to-vehicle transform required for deterministic reconstruction.",
        "scope": {"train_validation_trips": trips, "trip_count": len(trips), "locked_excluded": sorted(LOCKED), "locked_accessed": False, "speed_used": False, "optimization_performed": False},
        "authoritative_sources": [
            {"title": "IO-VNBD Data in Brief paper", "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC7907232/", "claims": ["The source describes simultaneous S/V pairs as manually synchronized where possible and stored in the Synchronised V and S datasets folder.", "The vehicle CAN/VBOX stream is 10 Hz and the smartphone stream is sampled every 0.1 s with smartphone GPS at 1 Hz.", "The smartphone was held in a vehicle-mounted holder; the paper shows sensor/vehicle axis diagrams and says effort was made to align the smartphone sensor axis with the vehicle axis.", "The paper states vehicle vibration interfered with measurement precision and provides no per-trip numeric transform or clock/drop map." ]},
            {"title": "Official IO-VNBD repository", "url": "https://github.com/onyekpeu/IO-VNBD", "claims": ["The repository distributes separate Synchronised V abd S datasets and Unsynchronised V and S Dataset collections, plus README material and data files.", "The repository README describes the dataset and sensor families but does not publish a synchronization algorithm, per-trip offsets, or frame calibration matrices."]},
        ],
        "author_intended_relationship": {"physical_acquisition": "A phone attached to the vehicle was intended to mimic vehicle motion; vehicle CAN/VBOX and phone streams were collected at nominal 10 Hz.", "distributed_files": "The synchronized directory identifies selected S-/V- recordings that authors considered simultaneous and manually synchronized where possible; duplicate copies exist in categorized and uncategorized subtrees and are byte-identical for the audited trip names.", "what_is_not_provided": ["shared absolute clock", "manual synchronization event/index or offset", "dropped-row correspondence", "numeric phone-to-vehicle rotation", "per-trip holder-angle record", "author preprocessing script implementing the manual synchronization"]},
        "current_pipeline_deviation": ["prepare_io_vnbd.py truncates each pair to min(len(S), len(V)) and pairs rows by index before resampling.", "It treats the phone elapsed field as the sole working clock and does not reconstruct the author’s manual synchronization event/map.", "It copies raw phone sensor axes into the vehicle contract without a persisted, author-supplied rotation matrix.", "It uses the vehicle indicated-speed field as the target, which is a defensible label choice but is not itself an alignment procedure."],
        "raw_structure": {"duplicate_vehicle_trip_names_byte_identical": duplicate_groups, "per_trip": structural},
        "timestamp_semantics": {"phone_elapsed": "Milliseconds since the phone recording/app start; it is not a shared vehicle clock. It resets in some distributed files.", "phone_absolute": "Wall-clock datetime emitted by the phone; it is monotonic for the checked recordings but is in a different clock/time-zone domain from vehicle time-of-day.", "vehicle_time": "Seconds since start of day from the vehicle/VBOX stream, with a nominal 0.1 s sample period.", "S3b": "The elapsed field has a discontinuity/reset while the absolute phone datetime remains the relevant monotonic wall-clock sequence; therefore the reset is a recording/logging-clock discontinuity, not evidence that the vehicle stream restarted.", "S4_M": "The duration mismatches are present in the distributed elapsed/vehicle fields and are not explained by a unit conversion; they indicate unequal recording coverage and/or missing/reset phone timing, not a valid row-index map."},
        "orientation_conclusion": {"known": ["Phone axes and gravity fields are documented; the holder physically attaches the phone to the vehicle; the paper includes axis-alignment figures."], "unknown": ["exact mount angles per trip", "whether each phone was placed identically", "numeric fixed rotation used by authors", "unambiguous physical-axis interpretation of Variant A yaw/pitch/roll columns"], "policy": "Do not invent a fixed rotation from the figures. The figures support an acquisition intention, not a reproducible matrix."},
        "authoritative_reconstruction": {"found": False, "manual_pairing_folder_is_evidence_of_pairing": True, "manual_pairing_folder_is_a_numeric_algorithm": False, "reproducible_from_distributed_files": False, "reason": "The distribution records the author’s selected pairs but omits the manual synchronization operation and frame calibration parameters."},
        "remaining_unknowable": ["exact sample-to-sample correspondence after starts/stops/drops", "absolute timing residual", "whether a constant, affine, or piecewise clock map was used by the authors", "exact phone yaw relative to forward for each trip", "whether the published vehicle axes/signs were applied to phone channels before distribution"],
        "recommendation": "There is not enough evidence to implement a corrected deterministic preprocessing pipeline for this TCN objective. Obtain author-provided synchronization/calibration metadata or an independently synchronized reference recording; otherwise treat IO-VNBD as unsuitable for final supervised speed training under the required provenance standard.",
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "io_vnbd_authoritative_alignment_investigation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = "# IO-VNBD authoritative alignment investigation\n\n## Verdict\n\n**C — no authoritative reconstruction is possible from the distributed source data.** The official paper and repository establish the intended paired collection and physical setup, but do not provide a reproducible synchronization operation, per-trip timing map, dropped-row map, or numeric phone-to-vehicle rotation.\n\n## Sources\n\n" + "\n".join(f"- [{s['title']}]({s['url']}): " + " ".join(s["claims"]) for s in report["authoritative_sources"]) + "\n\n## Consequence\n\nThe synchronized folder is evidence that the authors selected simultaneous S/V pairs and manually synchronized some/all where possible. It is not evidence that rows share an index-level correspondence, nor does it expose the manual operation. The current `prepare_io_vnbd.py` therefore deviates by truncating to the shorter file and pairing by row index.\n\nThe phone holder and axis figures establish intended physical attachment/alignment, not a recoverable per-trip matrix. The paper also reports vibration-induced measurement interference. A fixed rotation must not be invented from the figures.\n\n## Timestamp result\n\nThe phone elapsed field is a local millisecond clock, phone absolute datetime is a separate wall-clock domain, and vehicle time is seconds since start of day. Resets and unequal coverage are present in the distributed files. S3b’s elapsed reset is not a vehicle restart; S4/M duration differences are real source-coverage/timing ambiguity. Exact sample correspondence and timing residuals remain unknowable.\n\n## Decision\n\nNo fitting or speed-based synchronization was performed. No locked trip was opened. There is insufficient evidence to implement the corrected preprocessing pipeline safely. Author synchronization/calibration metadata or an independently synchronized reference is required before regeneration or TCN training.\n", encoding="utf-8")
    (OUT / "io_vnbd_authoritative_alignment_investigation.md").write_text(md, encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "trips": len(trips), "duplicate_vehicle_trip_names": duplicate_groups, "locked_accessed": False}, indent=2))


if __name__ == "__main__":
    main()
