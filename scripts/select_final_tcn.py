"""Freeze one already-trained candidate using validation MAE only."""
from __future__ import annotations
import json, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / "artifacts" / "runs"
FINAL = ROOT / "artifacts" / "final_tcn"

def select(candidates: list[dict]) -> dict:
    if len(candidates) != 4:
        raise ValueError("Final selection requires exactly four candidates")
    winner = min(candidates, key=lambda item: item["best_validation_mae_kmh"])
    return {"candidates": candidates, "selected_winner": winner["run_id"], "selection_criterion": "lowest validation MAE in km/h"}

def main() -> None:
    candidates = []
    for run in sorted(RUNS.iterdir()):
        info_path = run / "model_info.json"
        metrics_path = run / "validation_metrics.json"
        if info_path.exists() and metrics_path.exists():
            info = json.loads(info_path.read_text()); metrics = json.loads(metrics_path.read_text())
            if info.get("candidate_name"):
                candidates.append({**info, **metrics, "run_id": run.name})
    report = select(candidates)
    winner = RUNS / report["selected_winner"]
    FINAL.mkdir(parents=True, exist_ok=True)
    for name in ("tcn_best.pt", "normalization.json", "model_info.json", "validation_metrics.json", "training_history.json", "run_manifest.json"):
        shutil.copy2(winner / name, FINAL / name)
    (FINAL / "model_selection.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
