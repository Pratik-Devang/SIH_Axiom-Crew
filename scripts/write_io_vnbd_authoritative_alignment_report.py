"""Write the source-forensics report from existing train/validation audit data."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts" / "evaluation"
LOCKED = ["Vta1a", "Vta1b", "Y1"]

def main():
    prior = json.loads((OUT / "io_vnbd_source_reconstruction_audit.json").read_text(encoding="utf-8"))
    trips = prior["scope"]["analyzed_trips"]
    report = {
        "verdict": "C",
        "verdict_text": "No authoritative, reproducible synchronization/alignment procedure can be recovered from the distributed source.",
        "scope": {"train_validation_trips": trips, "trip_count": len(trips), "locked_excluded": LOCKED, "locked_accessed": False, "speed_used": False, "optimization_performed": False},
        "authoritative_sources": [
            {"title": "IO-VNBD Data in Brief paper", "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC7907232/", "claims": ["simultaneous S/V pairs were manually synchronized where possible and placed in the Synchronised V and S datasets folder", "vehicle and phone streams were nominally 10 Hz, with phone GPS at 1 Hz", "the phone was held in a vehicle-mounted holder and the paper shows axis-alignment figures", "vehicle vibration interfered with measurement precision; no per-trip timing map or numeric transform is supplied"]},
            {"title": "Official IO-VNBD repository", "url": "https://github.com/onyekpeu/IO-VNBD", "claims": ["distributes separate synchronized and unsynchronized S/V collections and README material", "does not publish a synchronization algorithm, per-trip offsets, dropped-row map, or frame calibration matrices"]},
        ],
        "author_intended_relationship": {"physical_acquisition": "A phone attached to the vehicle was intended to mimic vehicle motion while vehicle CAN/VBOX and phone streams were collected at nominal 10 Hz.", "distributed_files": "The synchronized directory identifies author-selected simultaneous S/V pairs; duplicate categorized/uncategorized copies audited here are byte-identical.", "not_provided": ["shared clock", "manual sync event/index or offset", "dropped-row correspondence", "numeric rotation", "per-trip holder angle", "author preprocessing implementation"]},
        "current_pipeline_deviation": ["prepare_io_vnbd.py truncates to min(len(S), len(V)) and pairs rows by index", "phone elapsed time is treated as the working clock without reconstructing manual synchronization", "raw phone axes are copied without a persisted author-supplied vehicle-frame rotation", "indicated speed is selected as target, which is separate from alignment"],
        "timestamp_semantics": {"phone_elapsed": "local milliseconds since phone recording/app start; resets occur", "phone_absolute": "phone wall-clock datetime in a separate clock/time-zone domain", "vehicle_time": "seconds since start of day with nominal 0.1 s sample period", "S3b": "elapsed field reset/discontinuity while absolute phone datetime remains the monotonic wall-clock reference", "S4_M": "distributed fields show unequal coverage/timing; not a unit-conversion error"},
        "source_file_evidence": {"inherited_train_validation_audit": "artifacts/evaluation/io_vnbd_source_reconstruction_audit.json", "examples": {"S2": "phone 9201.099 s vs vehicle 9387.5 s; one phone nonpositive delta", "S3b": "phone elapsed duration -2026.412 s vs vehicle 681.2 s; one reset", "S4": "phone 354.780 s vs vehicle 9459.9 s; two resets", "M": "phone 6171.748 s vs vehicle 10597.3 s; one reset"}},
        "orientation_conclusion": {"known": ["phone axes/gravity fields are documented", "holder attachment and intended axis alignment are documented"], "unknown": ["per-trip mount angles", "numeric fixed rotation", "whether placement was identical", "physical meaning of Variant A yaw/pitch/roll axes"], "decision": "Do not invent a matrix from the figures."},
        "authoritative_reconstruction": {"found": False, "pairing_folder_is_evidence_of_selected_pairing": True, "pairing_folder_is_numeric_algorithm": False, "reproducible_from_files": False},
        "remaining_unknowable": ["exact sample correspondence after starts/stops/drops", "absolute timing residual", "constant vs affine vs piecewise map used by authors", "exact phone yaw per trip", "whether distributed phone channels were transformed"],
        "recommendation": "Insufficient evidence exists to implement corrected preprocessing safely. Obtain author synchronization/calibration metadata or an independently synchronized reference; otherwise IO-VNBD cannot safely support final supervised speed training under the required provenance standard.",
    }
    (OUT / "io_vnbd_authoritative_alignment_investigation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = "# IO-VNBD authoritative alignment investigation\n\n## Verdict\n\n**C — no authoritative reconstruction is possible from the distributed source data.** The official paper and repository establish intended paired collection and physical setup, but provide no reproducible synchronization operation, timing map, dropped-row map, or numeric phone-to-vehicle rotation.\n\n## Authoritative sources\n\n" + "\n".join(f"- [{s['title']}]({s['url']}): " + " ".join(s['claims']) for s in report['authoritative_sources']) + "\n\n## Interpretation\n\nThe synchronized folder is evidence of author-selected simultaneous pairs and manual synchronization where possible. It is not an index-level correspondence contract and does not expose the manual operation. The phone holder and axis figures establish intended physical attachment/alignment, not a recoverable per-trip matrix.\n\nThe current `prepare_io_vnbd.py` deviates by truncating to the shorter file and pairing rows by index. Phone elapsed time is local, phone absolute datetime and vehicle time-of-day are separate domains, and resets/unequal coverage are present. S3b, S4, and M demonstrate that the distributed timestamps cannot recover exact correspondence.\n\nNo fitting, speed-based synchronization, training, regeneration, Android change, or locked-test access occurred. Author synchronization/calibration metadata or an independent reference is required before preprocessing can be implemented safely.\n"
    (OUT / "io_vnbd_authoritative_alignment_investigation.md").write_text(md, encoding="utf-8")
    print(json.dumps({"verdict": "C", "trips": len(trips), "locked_accessed": False}, indent=2))

if __name__ == "__main__":
    main()
