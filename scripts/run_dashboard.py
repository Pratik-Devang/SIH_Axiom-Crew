"""Launch the interactive replay dashboard."""

import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = PROJECT_ROOT / "src" / "dashboard" / "app.py"


if __name__ == "__main__":
    if not DASHBOARD.exists():
        raise FileNotFoundError(f"Dashboard not found: {DASHBOARD}")

    subprocess.run(
        [sys.executable, "-m", "streamlit", "run", str(DASHBOARD)],
        cwd=PROJECT_ROOT,
        check=True,
    )

