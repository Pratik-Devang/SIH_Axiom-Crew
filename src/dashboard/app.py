"""Percorsa controlled-outage replay dashboard."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import plotly.graph_objects as go
import streamlit as st

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.evaluation.replay import run_outage_replay  # noqa: E402
from src.ml.inference import OnnxSpeedPredictor  # noqa: E402

DATA_DIR = PROJECT_ROOT / "data" / "processed" / "io_vnbd" / "trips"
MODEL_PATH = PROJECT_ROOT / "artifacts" / "tcn_best.pt"
ONNX_PATH = PROJECT_ROOT / "artifacts" / "tcn.onnx"
NORMALIZATION_PATH = PROJECT_ROOT / "artifacts" / "normalization.json"


def demo_trip(samples: int = 900) -> pd.DataFrame:
    """Generate a clearly labelled fallback route for UI testing."""
    time = np.arange(samples) / 10.0
    speed_mps = 7.0 + 2.0 * np.sin(time / 12.0)
    heading = 0.3 * np.sin(time / 25.0)
    east = np.cumsum(speed_mps * np.sin(heading) / 10.0)
    north = np.cumsum(speed_mps * np.cos(heading) / 10.0)
    return pd.DataFrame(
        {
            "trip_id": "built_in_demo",
            "time_since_start_s": time,
            "latitude": 19.05 + north / 111_000.0,
            "longitude": 72.89 + east / (111_000.0 * np.cos(np.deg2rad(19.05))),
            "vehicle_speed": speed_mps * 3.6,
            "gyro_z": np.gradient(heading, time),
            "gps_accuracy_m": 3.0,
        }
    )


@st.cache_data(show_spinner=False)
def load_trip(path: str) -> pd.DataFrame:
    return pd.read_csv(path)


@st.cache_resource(show_spinner=False)
def load_speed_predictor() -> OnnxSpeedPredictor:
    return OnnxSpeedPredictor(ONNX_PATH, NORMALIZATION_PATH)


st.set_page_config(page_title="Percorsa", page_icon="P", layout="wide")
st.title("Percorsa")
st.caption("Resilient navigation replay for a controlled GNSS outage")

files = sorted(DATA_DIR.glob("*.csv"))
labels = [path.stem for path in files]
if not files:
    st.info(
        "No local IO-VNBD trips were found, so the dashboard is using a "
        "built-in synthetic route for interface testing."
    )
    trip = demo_trip()
    selected = "built_in_demo"
else:
    selected = st.sidebar.selectbox("Trip", labels)
    trip = load_trip(str(files[labels.index(selected)]))

duration = float(trip["time_since_start_s"].max() - trip["time_since_start_s"].min())
default_start = min(60.0, max(0.0, duration * 0.35))
outage_start = st.sidebar.slider(
    "Outage start (s)", 0.0, max(duration, 1.0), default_start, 1.0
)
max_outage = max(1.0, duration - outage_start)
outage_duration = st.sidebar.slider(
    "Outage duration (s)", 1.0, max_outage, min(30.0, max_outage), 1.0
)
st.sidebar.caption(f"TCN checkpoint: {'ready' if MODEL_PATH.exists() else 'missing'}")

speed_prediction = speed_variance = None
speed_status = "Reference speed fallback"
if ONNX_PATH.exists() and NORMALIZATION_PATH.exists():
    try:
        speed_prediction, speed_variance = load_speed_predictor().predict(trip)
        speed_status = "TCN ONNX speed estimate"
    except ValueError as error:
        st.sidebar.warning(f"TCN fallback: {error}")
st.sidebar.caption(f"Speed input: {speed_status}")

try:
    replay, metrics = run_outage_replay(
        trip,
        outage_start,
        outage_duration,
        speed_prediction,
        speed_variance,
    )
except ValueError as error:
    st.error(f"This trip cannot be replayed: {error}")
    st.stop()

outage = ~replay["gnss_available"]
summary = metrics["percorsa"]
columns = st.columns(5)
columns[0].metric("Trip", selected)
columns[1].metric("Samples", f"{len(replay):,}")
columns[2].metric("Outage RMSE", f"{summary['rmse_m']:.1f} m")
columns[3].metric("Endpoint error", f"{summary['endpoint_error_m']:.1f} m")
columns[4].metric(
    "2-sigma uncertainty", f"{replay['position_uncertainty_m'].iloc[-1]:.1f} m"
)

route = go.Figure()
route.add_trace(
    go.Scattermap(
        lat=replay["latitude"],
        lon=replay["longitude"],
        mode="lines",
        name="GNSS ground truth",
    )
)
route.add_trace(
    go.Scattermap(
        lat=replay["estimated_latitude"],
        lon=replay["estimated_longitude"],
        mode="lines",
        name="Percorsa estimate",
    )
)
route.add_trace(
    go.Scattermap(
        lat=replay.loc[outage, "latitude"],
        lon=replay.loc[outage, "longitude"],
        mode="markers",
        marker={"size": 5, "color": "#ef4444"},
        name="GNSS denied",
    )
)
route.update_layout(
    map_style="open-street-map",
    height=470,
    margin={"l": 0, "r": 0, "t": 25, "b": 0},
    legend={"orientation": "h"},
)
st.plotly_chart(route, width="stretch")

left, right = st.columns(2)
with left:
    st.subheader("Position error and confidence")
    error_chart = replay.set_index("time_since_start_s")[
        ["position_error_m", "position_uncertainty_m"]
    ]
    st.line_chart(error_chart)
with right:
    st.subheader("Speed estimate")
    speed_chart = replay.set_index("time_since_start_s")[["estimated_speed_mps"]]
    st.line_chart(speed_chart)

st.subheader("Outage comparison")
comparison = pd.DataFrame(metrics).T.rename(
    columns={
        "rmse_m": "RMSE (m)",
        "mae_m": "MAE (m)",
        "max_error_m": "Maximum error (m)",
        "endpoint_error_m": "Endpoint error (m)",
    }
)
st.dataframe(comparison.style.format("{:.2f}"), width="stretch")
st.download_button(
    "Download replay CSV",
    replay.to_csv(index=False),
    f"{selected}_replay.csv",
    "text/csv",
)
