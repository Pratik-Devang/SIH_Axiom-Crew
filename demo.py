"""
demo.py
=======
Interactive end-to-end demo of the Percorsa Role 4 pipeline.

Simulates a vehicle driving through Chembur, Mumbai experiencing:
1. Normal driving with clean GNSS fixes -> accepted by GNSSTrustManager
2. A red light stop -> StopDetector triggers ZUPT ConstraintEvent
3. A sharp turn -> VehicleConstraintDetector assesses NHC state
4. Degraded GNSS / multipath jump -> rejected by GNSSTrustManager
5. Real-time road matching via CandidateGenerator & HMMMapMatcher
"""

from __future__ import annotations

import json
import time
from pathlib import Path

from src.preprocessing.coordinates import LatLonOrigin, latlon_to_enu
from src.maps.candidates import CandidateGenerator
from src.maps.hmm_matcher import HMMMapMatcher
from src.maps.osm_loader import OSMLoader
from src.constraints.gnss_trust import GNSSFix, GNSSTrustManager
from src.constraints.stop_detection import StopDetector
from src.constraints.vehicle import VehicleConstraintDetector


def run_demo() -> None:
    print("=" * 70)
    print(" PERCORSA (SIH 2026 PS 26168) - ROLE 4 PIPELINE DEMO")
    print("=" * 70)

    # 1. Initialize maps
    print("\n[1] Initializing OSM Road Network (Chembur Fixture)...")
    loader = OSMLoader(config_path="configs/role4.yaml")
    graphml_path = Path("data/chembur_1km.graphml")
    if not graphml_path.exists():
        print("    Downloading Chembur road network...")
        loader.load_from_point(19.051, 72.894, radius_m=1000, save_path=graphml_path)
    else:
        loader.load_from_graphml(graphml_path)
    
    cand_gen = CandidateGenerator(loader, config_path="configs/role4.yaml")
    cand_gen.build_index()
    matcher = HMMMapMatcher(config_path="configs/role4.yaml")
    print(f"    Graph ready: {loader.graph.number_of_nodes()} nodes, {loader.graph.number_of_edges()} edges")

    # 2. Export Android JSON preview
    android_json_path = Path("data/chembur_android_preview.json")
    loader.export_lightweight_json(android_json_path)
    print(f"    Exported lightweight Android graph -> {android_json_path}")

    # 3. Initialize Navigation Trust & Constraint Managers
    print("\n[2] Initializing GNSS Trust, Stop Detector & NHC Engine...")
    demo_log_path = Path("logs/demo_events.jsonl")
    gnss_mgr = GNSSTrustManager(event_log_path=demo_log_path)
    stop_det = StopDetector(event_log_path=demo_log_path)
    nhc_det = VehicleConstraintDetector(event_log_path=demo_log_path)

    # 4. Simulated vehicle trajectory in Chembur
    # Starting near (19.0510, 72.8940)
    # Scenario steps:
    # t=0..3: Normal driving (10 m/s heading 90 deg East)
    # t=4..7: Stopped at signal (0 m/s) -> triggers ZUPT
    # t=8..10: Accelerating & Turning North (heading 0 deg)
    # t=11: Corrupted GNSS fix (HDOP=9.5, jump 250m) -> GNSS Reject
    trajectory = [
        # (t, lat, lon, speed, heading, hdop, acc_m, sats, scenario_desc)
        (0.0, 19.05100, 72.89400, 10.0, 90.0, 1.1, 3.5, 9, "Normal driving (East)"),
        (1.0, 19.05100, 72.89409, 10.0, 90.0, 1.2, 4.0, 8, "Normal driving (East)"),
        (2.0, 19.05100, 72.89418, 10.0, 90.0, 1.0, 3.0, 10, "Normal driving (East)"),
        (3.0, 19.05100, 72.89427, 2.0, 90.0, 1.1, 3.2, 9, "Decelerating before red light"),
        (4.0, 19.05100, 72.89430, 0.0, 90.0, 1.0, 3.0, 9, "Stationary at red light"),
        (5.0, 19.05100, 72.89430, 0.0, 90.0, 1.0, 3.0, 9, "Stationary at red light"),
        (6.0, 19.05100, 72.89430, 0.0, 90.0, 1.1, 3.1, 9, "Stationary at red light"),
        (7.0, 19.05100, 72.89430, 0.0, 90.0, 1.0, 3.0, 9, "Stationary (ZUPT confirmed)"),
        (8.0, 19.05105, 72.89432, 6.0, 45.0, 1.3, 4.5, 8, "Accelerating & Turning North-East"),
        (9.0, 19.05115, 72.89433, 9.0, 10.0, 1.2, 4.0, 8, "Sharp turn North"),
        (10.0, 19.05125, 72.89433, 11.0, 0.0, 1.1, 3.8, 9, "Heading straight North"),
        (11.0, 19.05350, 72.89433, 11.0, 0.0, 9.5, 45.0, 3, "Degraded GNSS / Multipath Jump!"),
    ]

    print("\n[3] Processing Trajectory Steps...")
    print("-" * 105)
    print(f"{'Time':<5} | {'Scenario':<30} | {'GNSS Trust':<14} | {'Stop/ZUPT':<14} | {'NHC Status':<12} | {'Matched Road (u->v)'}")
    print("-" * 105)

    base_time = time.time()

    for item in trajectory:
        t_rel, lat, lon, spd, hdg, hdop, acc, sats, desc = item
        timestamp = base_time + t_rel

        # 1. Evaluate GNSS Fix
        fix = GNSSFix(
            timestamp=timestamp,
            lat=lat,
            lon=lon,
            hdop=hdop,
            accuracy_m=acc,
            num_satellites=sats,
            speed_m_s=spd,
        )
        trust = gnss_mgr.evaluate(fix)
        trust_str = f"ACCEPT ({trust.score:.2f})" if trust.accepted else "REJECT (Bad)"

        # 2. Evaluate Stop Detector & Role 3 ZUPT Constraint
        stop_ev = stop_det.update(timestamp=timestamp, speed_m_s=spd)
        zupt_constraint = stop_det.to_constraint_event(stop_ev)
        if zupt_constraint:
            stop_str = f"ZUPT (c={zupt_constraint.confidence:.2f})"
        elif stop_ev.is_stopped:
            stop_str = "STOPPED"
        else:
            stop_str = "MOVING"

        # 3. Evaluate Vehicle Non-Holonomic Constraints
        nhc_state = nhc_det.update(timestamp=timestamp, speed_m_s=spd, heading_deg=hdg)
        nhc_constraint = nhc_det.to_constraint_event(nhc_state)
        if nhc_state.violation:
            nhc_str = f"VIOLATION"
        else:
            nhc_str = f"OK ({nhc_state.lateral_velocity_m_s:.2f}m/s)"

        # 4. Map Matching against Road Network
        # Only use candidates if GNSS is trusted or if doing dead-reckoning snapping
        candidates = cand_gen.get_candidates(lat=lat, lon=lon, heading_deg=hdg, top_k=3)
        match_res = matcher.update(timestamp=timestamp, candidates=candidates)
        
        if match_res:
            road_str = f"{match_res.u} -> {match_res.v} (conf={match_res.confidence:.2f})"
        else:
            road_str = "No candidate"

        print(f"t={t_rel:<3.1f}s | {desc:<30} | {trust_str:<14} | {stop_str:<14} | {nhc_str:<12} | {road_str}")

    gnss_mgr.close()
    stop_det.close()
    nhc_det.close()

    print("-" * 105)
    print("\n[4] Structured Logging Verification:")
    print(f"    Events written to {demo_log_path}")
    if demo_log_path.exists():
        with open(demo_log_path) as f:
            lines = [json.loads(line) for line in f.readlines()]
        print(f"    Total logged events: {len(lines)}")
        sample = lines[-1]
        print(f"    Last event record preview:\n    {json.dumps(sample, indent=6)}")

    print("\n" + "=" * 70)
    print(" DEMO RUN COMPLETE: All systems functioning as specified.")
    print("=" * 70)


if __name__ == "__main__":
    run_demo()
