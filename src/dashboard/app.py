# ruff: noqa: E501
"""Judge-facing Percorsa journey replay and sensor dashboard."""

from __future__ import annotations

import html
import os
import sys
from io import BytesIO
from pathlib import Path

import numpy as np
import pandas as pd
import plotly.graph_objects as go
import streamlit as st

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.data.live_trip import normalize_trip_frame  # noqa: E402
from src.evaluation.replay import run_outage_replay  # noqa: E402
from src.ml.inference import OnnxSpeedPredictor  # noqa: E402

DATA_DIR = PROJECT_ROOT / "data" / "processed" / "io_vnbd" / "trips"
ONNX_PATH = PROJECT_ROOT / "artifacts" / "tcn.onnx"
NORMALIZATION_PATH = PROJECT_ROOT / "artifacts" / "normalization.json"
MAX_DASHBOARD_UPLOAD_BYTES = 25 * 1024 * 1024


def apply_theme() -> None:
    st.markdown(
        """
        <style>
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
        :root { --ink:#111318; --muted:#6c7078; --line:#e7e8e5;
                --green:#15865b; --green-soft:#e9f7f0; --paper:#ffffff;
                --canvas:#f6f7f5; --danger:#d95757; }
        html, body, [class*="css"] { font-family: Inter, ui-sans-serif, system-ui, sans-serif; }
        .stApp { background:var(--canvas); color:var(--ink); }
        .block-container { max-width:1440px; padding:1.15rem 2.3rem 3rem; }
        #MainMenu, footer, header[data-testid="stHeader"] { visibility:hidden; height:0; }
        .percorsa-nav { display:flex; align-items:center; justify-content:space-between;
            padding:13px 17px; background:rgba(255,255,255,.96); border:1px solid #ecece9;
            box-shadow:0 8px 28px rgba(23,27,24,.06); border-radius:18px; margin-bottom:18px; }
        .brand { display:flex; align-items:center; gap:12px; font-size:15px; font-weight:700; }
        .brand-mark { width:35px; height:35px; display:grid; place-items:center; color:white;
            border-radius:11px; background:#111318; font-size:18px; }
        .nav-links { display:flex; gap:28px; color:#53575f; font-size:13px; }
        .nav-actions { display:flex; align-items:center; gap:10px; }
        .secure-pill { padding:8px 12px; color:#177a55; background:#edf8f2;
            border:1px solid #cbeada; border-radius:999px; font-size:12px; font-weight:600; }
        .hero { position:relative; overflow:hidden; background:var(--paper); border:1px solid var(--line);
            border-radius:25px; padding:34px 38px 31px; margin-bottom:18px; }
        .hero:after { content:""; position:absolute; width:290px; height:290px; right:-90px;
            top:-130px; border-radius:50%; background:radial-gradient(circle,#dff5e9 0%,rgba(223,245,233,0) 70%); }
        .eyebrow { display:inline-flex; align-items:center; gap:7px; padding:6px 10px;
            border:1px solid #a9dfc5; background:#f2fbf6; color:#15764f; border-radius:999px;
            font-size:11px; font-weight:700; letter-spacing:.02em; }
        .hero h1 { position:relative; z-index:1; font-size:36px; line-height:1.08; letter-spacing:-.04em;
            max-width:720px; margin:15px 0 10px; }
        .hero p { position:relative; z-index:1; max-width:720px; color:var(--muted);
            font-size:14px; line-height:1.65; margin:0; }
        .section-kicker { color:#177c55; font-size:11px; font-weight:700; text-transform:uppercase;
            letter-spacing:.12em; margin-bottom:4px; }
        .section-title { font-size:23px; font-weight:700; letter-spacing:-.025em; margin-bottom:3px; }
        .section-copy { color:var(--muted); font-size:13px; margin-bottom:13px; }
        .metric-card { min-height:104px; background:white; border:1px solid var(--line);
            border-radius:18px; padding:17px 18px; box-shadow:0 5px 18px rgba(17,19,24,.035); }
        .metric-label { color:#737780; font-size:11px; font-weight:600; margin-bottom:12px; }
        .metric-value { color:#16181d; font-size:23px; font-weight:700; letter-spacing:-.035em; }
        .metric-note { color:#8a8d94; font-size:10px; margin-top:4px; }
        .status-dot { display:inline-block; width:7px; height:7px; border-radius:50%;
            background:#21a56f; margin-right:6px; box-shadow:0 0 0 4px #e5f6ed; }
        div[data-testid="stVerticalBlockBorderWrapper"] { background:white; border:1px solid var(--line) !important;
            border-radius:20px; box-shadow:0 5px 18px rgba(17,19,24,.025); }
        .stButton > button, .stDownloadButton > button { border-radius:11px; border:1px solid #dfe1de;
            font-weight:600; min-height:42px; }
        .stDownloadButton > button { background:#111318; color:white; border-color:#111318; }
        [data-testid="stFileUploaderDropzone"] { background:#fbfcfa; border:1px dashed #cfd4cf;
            border-radius:14px; }
        div[data-baseweb="select"] > div, .stSlider [data-baseweb="slider"] { border-radius:11px; }
        .notice { border-radius:14px; padding:13px 15px; font-size:12px; line-height:1.5;
            background:#fff8e8; border:1px solid #f1dfad; color:#735c20; }
        .good-notice { background:#edf8f2; border-color:#cbeada; color:#166d4b; }
        .api-card { background:#111318; color:white; border-radius:18px; padding:18px 20px; }
        .api-card small { color:#afb3b9; } .api-card code { color:#a7efcb; }
        .footer-note { text-align:center; color:#96999f; font-size:11px; margin-top:30px; }
        @media (max-width: 800px) { .block-container{padding:1rem;} .nav-links{display:none;}
            .hero{padding:26px 22px;} .hero h1{font-size:30px;} }
        </style>
        """,
        unsafe_allow_html=True,
    )


def demo_trip(samples: int = 900) -> pd.DataFrame:
    """Generate a labelled fallback route for interface testing."""
    time = np.arange(samples) / 10.0
    speed_mps = 7.0 + 2.0 * np.sin(time / 12.0)
    heading = 0.3 * np.sin(time / 25.0)
    east = np.cumsum(speed_mps * np.sin(heading) / 10.0)
    north = np.cumsum(speed_mps * np.cos(heading) / 10.0)
    return pd.DataFrame(
        {
            "trip_id": "built_in_demo",
            "time_since_start_s": time,
            "accel_x": np.gradient(speed_mps, time),
            "accel_y": np.zeros(samples),
            "accel_z": np.full(samples, 9.81),
            "gyro_x": np.zeros(samples),
            "gyro_y": np.zeros(samples),
            "gyro_z": np.gradient(heading, time),
            "latitude": 19.05 + north / 111_000.0,
            "longitude": 72.89 + east / (111_000.0 * np.cos(np.deg2rad(19.05))),
            "vehicle_speed": speed_mps * 3.6,
            "gps_accuracy_m": 3.0,
        }
    )


@st.cache_data(show_spinner=False)
def load_local_trip(path: str) -> pd.DataFrame:
    return pd.read_csv(path)


@st.cache_data(show_spinner=False)
def parse_upload(content: bytes, filename: str) -> tuple[pd.DataFrame, dict]:
    source = pd.read_csv(BytesIO(content))
    frame, validation = normalize_trip_frame(source, Path(filename).stem)
    return frame, validation.as_dict()


@st.cache_resource(show_spinner=False)
def load_speed_predictor() -> OnnxSpeedPredictor:
    return OnnxSpeedPredictor(ONNX_PATH, NORMALIZATION_PATH)


def metric_card(label: str, value: str, note: str) -> None:
    st.markdown(
        f"""
        <div class="metric-card">
          <div class="metric-label">{html.escape(label)}</div>
          <div class="metric-value">{html.escape(value)}</div>
          <div class="metric-note">{html.escape(note)}</div>
        </div>
        """,
        unsafe_allow_html=True,
    )


def sensor_figure(frame: pd.DataFrame, columns: list[str], title: str) -> go.Figure:
    figure = go.Figure()
    colors = ["#167f58", "#4a8dd8", "#e29c35"]
    for color, column in zip(colors, columns, strict=False):
        figure.add_trace(
            go.Scatter(
                x=frame["time_since_start_s"],
                y=frame[column],
                mode="lines",
                line={"width": 1.6, "color": color},
                name=column.replace("_", " ").title(),
            )
        )
    figure.update_layout(
        title={"text": title, "font": {"size": 14}},
        height=315,
        margin={"l": 20, "r": 20, "t": 48, "b": 25},
        paper_bgcolor="white",
        plot_bgcolor="white",
        legend={"orientation": "h", "y": 1.12, "x": 0.48},
        hovermode="x unified",
    )
    figure.update_xaxes(title="Journey time (s)", gridcolor="#eef0ed")
    figure.update_yaxes(gridcolor="#eef0ed")
    return figure


def animated_route(replay: pd.DataFrame) -> go.Figure:
    """Build a lightweight Plotly journey animation with play controls."""
    outage = ~replay["gnss_available"].to_numpy(bool)
    indices = np.unique(
        np.linspace(0, len(replay) - 1, min(100, len(replay)), dtype=int)
    )
    figure = go.Figure(
        data=[
            go.Scattermap(
                lat=replay["latitude"],
                lon=replay["longitude"],
                mode="lines",
                line={"width": 4, "color": "#cfd4d0"},
                name="GNSS ground truth",
            ),
            go.Scattermap(
                lat=replay.loc[outage, "latitude"],
                lon=replay.loc[outage, "longitude"],
                mode="lines",
                line={"width": 5, "color": "#e05c5c"},
                name="GNSS withheld",
            ),
            go.Scattermap(
                lat=replay["estimated_latitude"].iloc[:1],
                lon=replay["estimated_longitude"].iloc[:1],
                mode="lines",
                line={"width": 5, "color": "#16855b"},
                name="Percorsa estimate",
            ),
            go.Scattermap(
                lat=replay["estimated_latitude"].iloc[:1],
                lon=replay["estimated_longitude"].iloc[:1],
                mode="markers",
                marker={"size": 13, "color": "#111318"},
                name="Vehicle",
            ),
        ]
    )
    frames = []
    for index in indices:
        frames.append(
            go.Frame(
                name=str(index),
                traces=[2, 3],
                data=[
                    go.Scattermap(
                        lat=replay["estimated_latitude"].iloc[: index + 1],
                        lon=replay["estimated_longitude"].iloc[: index + 1],
                        mode="lines",
                        line={"width": 5, "color": "#16855b"},
                    ),
                    go.Scattermap(
                        lat=[replay["estimated_latitude"].iloc[index]],
                        lon=[replay["estimated_longitude"].iloc[index]],
                        mode="markers",
                        marker={"size": 13, "color": "#111318"},
                    ),
                ],
            )
        )
    figure.frames = frames
    steps = [
        {
            "label": f"{replay['time_since_start_s'].iloc[i]:.0f}s",
            "method": "animate",
            "args": [[str(i)], {"mode": "immediate", "frame": {"duration": 0}}],
        }
        for i in indices[:: max(1, len(indices) // 8)]
    ]
    figure.update_layout(
        height=535,
        map={
            "style": "open-street-map",
            "center": {
                "lat": float(replay["latitude"].median()),
                "lon": float(replay["longitude"].median()),
            },
            "zoom": 14,
        },
        margin={"l": 0, "r": 0, "t": 10, "b": 0},
        paper_bgcolor="white",
        legend={"orientation": "h", "x": 0.01, "y": 1.0},
        updatemenus=[
            {
                "type": "buttons",
                "direction": "left",
                "x": 0.01,
                "y": 0.02,
                "buttons": [
                    {
                        "label": "Play journey",
                        "method": "animate",
                        "args": [
                            None,
                            {
                                "fromcurrent": True,
                                "frame": {"duration": 90, "redraw": True},
                                "transition": {"duration": 0},
                            },
                        ],
                    },
                    {
                        "label": "Pause",
                        "method": "animate",
                        "args": [
                            [None],
                            {"mode": "immediate", "frame": {"duration": 0}},
                        ],
                    },
                ],
            }
        ],
        sliders=[
            {
                "active": 0,
                "x": 0.26,
                "len": 0.71,
                "y": 0.02,
                "steps": steps,
                "currentvalue": {"prefix": "Journey "},
            }
        ],
    )
    return figure


def error_timeline(
    replay: pd.DataFrame, outage_start: float, outage_end: float
) -> go.Figure:
    figure = go.Figure()
    figure.add_trace(
        go.Scatter(
            x=replay["time_since_start_s"],
            y=replay["position_error_m"],
            mode="lines",
            line={"color": "#16855b", "width": 2.4},
            name="Position error",
        )
    )
    figure.add_trace(
        go.Scatter(
            x=replay["time_since_start_s"],
            y=replay["position_uncertainty_m"],
            mode="lines",
            line={"color": "#7a7f87", "width": 1.5, "dash": "dot"},
            name="2-sigma confidence",
        )
    )
    figure.add_vrect(
        x0=outage_start,
        x1=outage_end,
        fillcolor="#e05c5c",
        opacity=0.09,
        line_width=0,
        annotation_text="GNSS denied",
        annotation_position="top left",
    )
    figure.update_layout(
        height=330,
        margin={"l": 18, "r": 18, "t": 36, "b": 30},
        paper_bgcolor="white",
        plot_bgcolor="white",
        hovermode="x unified",
        legend={"orientation": "h", "y": 1.14},
    )
    figure.update_xaxes(title="Journey time (s)", gridcolor="#eef0ed")
    figure.update_yaxes(title="Metres", gridcolor="#eef0ed")
    return figure


st.set_page_config(
    page_title="Percorsa Journey Intelligence", page_icon="P", layout="wide"
)
apply_theme()

api_configured = len(os.getenv("PERCORSA_API_KEY", "")) >= 32
api_label = "API secured" if api_configured else "API locked until key is set"
st.markdown(
    f"""
    <div class="percorsa-nav">
      <div class="brand"><span class="brand-mark">P</span><span>Percorsa</span></div>
      <div class="nav-links"><span>Overview</span><span>Journey</span><span>Signals</span><span>Integration</span></div>
      <div class="nav-actions"><span class="secure-pill">● {html.escape(api_label)}</span></div>
    </div>
    <div class="hero">
      <span class="eyebrow"><span class="status-dot"></span>GNSS-resilient navigation</span>
      <h1>Turn a recorded drive into a clear navigation story.</h1>
      <p>Replay the complete journey, withhold GNSS over a controlled interval, and show exactly how Percorsa carries the vehicle forward using phone IMU, learned speed and uncertainty-aware filtering.</p>
    </div>
    """,
    unsafe_allow_html=True,
)

source_column, upload_column = st.columns([1.05, 1.5], gap="large")
local_files = sorted(DATA_DIR.glob("*.csv"))
with source_column:
    with st.container(border=True):
        st.markdown(
            "<div class='section-kicker'>Trip source</div>", unsafe_allow_html=True
        )
        source_options = ["Built-in judge demo"] + [path.stem for path in local_files]
        selected_source = st.selectbox(
            "Available recordings", source_options, label_visibility="collapsed"
        )
        st.caption("Use the demo now or select a processed team recording.")
with upload_column:
    with st.container(border=True):
        st.markdown(
            "<div class='section-kicker'>New recording</div>", unsafe_allow_html=True
        )
        uploaded = st.file_uploader(
            "Upload Android trip CSV",
            type=["csv"],
            label_visibility="collapsed",
            help="Maximum 25 MB. Files are validated before replay.",
        )
        st.caption(
            "Current IMU-only exports open in sensor mode. GNSS-enabled exports unlock route replay."
        )

validation: dict | None = None
source_error: str | None = None
if uploaded is not None:
    content = uploaded.getvalue()
    if len(content) > MAX_DASHBOARD_UPLOAD_BYTES:
        source_error = "The uploaded file exceeds the 25 MB dashboard limit."
        trip = demo_trip()
    else:
        try:
            trip, validation = parse_upload(content, uploaded.name)
        except (ValueError, pd.errors.ParserError, UnicodeDecodeError) as error:
            source_error = str(error)
            trip = demo_trip()
elif selected_source == "Built-in judge demo":
    trip = demo_trip()
    validation = {
        "trip_id": "built_in_demo",
        "rows": len(trip),
        "duration_s": float(trip["time_since_start_s"].iloc[-1]),
        "replay_ready": True,
        "has_imu": True,
        "has_gnss": True,
        "issues": [],
    }
else:
    trip = load_local_trip(str(local_files[source_options.index(selected_source) - 1]))
    try:
        trip, result = normalize_trip_frame(trip, selected_source)
        validation = result.as_dict()
    except ValueError as error:
        source_error = str(error)

if source_error:
    st.error(f"Recording rejected: {source_error}")
    st.stop()

assert validation is not None
trip_name = html.escape(str(validation["trip_id"]))
sample_times = trip["time_since_start_s"].to_numpy(float)
sample_dt = np.diff(sample_times)
sample_rate = 1.0 / np.median(sample_dt[sample_dt > 0])

if not bool(validation["replay_ready"]):
    st.markdown(
        f"<div class='notice'><strong>{trip_name}</strong> is a valid sensor recording. Route replay is waiting for latitude and longitude, so this view shows the IMU timeline only.</div>",
        unsafe_allow_html=True,
    )
    metric_columns = st.columns(4)
    with metric_columns[0]:
        metric_card("Recording", str(validation["trip_id"]), "Validated Android CSV")
    with metric_columns[1]:
        metric_card(
            "Duration",
            f"{float(validation['duration_s']):.1f} s",
            "Monotonic sensor timeline",
        )
    with metric_columns[2]:
        metric_card("Samples", f"{int(validation['rows']):,}", "Synchronized IMU rows")
    with metric_columns[3]:
        metric_card(
            "Observed rate", f"{sample_rate:.1f} Hz", "Calculated from timestamps"
        )
    st.markdown(
        "<br><div class='section-title'>Sensor timeline</div><div class='section-copy'>The same upload becomes replay-ready automatically when GNSS fields are added later.</div>",
        unsafe_allow_html=True,
    )
    left, right = st.columns(2, gap="large")
    with left:
        st.plotly_chart(
            sensor_figure(trip, ["accel_x", "accel_y", "accel_z"], "Acceleration"),
            width="stretch",
        )
    with right:
        st.plotly_chart(
            sensor_figure(trip, ["gyro_x", "gyro_y", "gyro_z"], "Angular velocity"),
            width="stretch",
        )
    st.stop()

duration = float(sample_times[-1] - sample_times[0])
with st.container(border=True):
    st.markdown(
        "<div class='section-kicker'>Controlled evaluation</div>",
        unsafe_allow_html=True,
    )
    control_one, control_two, control_three = st.columns([1, 1, 1.4])
    default_start = min(60.0, max(0.0, duration * 0.35))
    with control_one:
        outage_start = st.slider(
            "Outage starts at",
            0.0,
            max(duration - 1.0, 1.0),
            min(default_start, max(duration - 1.0, 1.0)),
            1.0,
        )
    max_outage = max(1.0, duration - outage_start)
    with control_two:
        outage_duration = st.slider(
            "Outage duration",
            1.0,
            max_outage,
            min(30.0, max_outage),
            1.0,
        )
    with control_three:
        st.markdown(
            f"<div class='good-notice notice'><strong>{trip_name}</strong><br>{len(trip):,} samples at approximately {sample_rate:.1f} Hz. GNSS will be withheld only from the estimator.</div>",
            unsafe_allow_html=True,
        )

speed_prediction = speed_variance = None
speed_status = "Reference speed fallback"
if ONNX_PATH.exists() and NORMALIZATION_PATH.exists():
    try:
        speed_prediction, speed_variance = load_speed_predictor().predict(trip)
        speed_status = "TCN ONNX"
    except ValueError:
        speed_status = "Reference speed fallback"

with st.spinner("Replaying the controlled GNSS outage..."):
    replay, metrics = run_outage_replay(
        trip,
        outage_start,
        outage_duration,
        speed_prediction,
        speed_variance,
    )

distance_km = float(
    np.hypot(np.diff(replay["east"]), np.diff(replay["north"])).sum() / 1000.0
)
summary = metrics["percorsa"]
baseline = metrics["last_fix"]
metric_columns = st.columns(5)
with metric_columns[0]:
    metric_card("Journey distance", f"{distance_km:.2f} km", "GNSS ground-truth path")
with metric_columns[1]:
    metric_card(
        "GNSS denied",
        f"{outage_duration:.0f} s",
        f"Starts at {outage_start:.0f} seconds",
    )
with metric_columns[2]:
    metric_card(
        "Outage RMSE", f"{summary['rmse_m']:.1f} m", "Percorsa horizontal error"
    )
with metric_columns[3]:
    metric_card(
        "Endpoint drift", f"{summary['endpoint_error_m']:.1f} m", "Before GNSS recovery"
    )
with metric_columns[4]:
    metric_card("Speed source", speed_status, "10 Hz estimator updates")

st.markdown(
    "<br><div class='section-kicker'>Journey replay</div><div class='section-title'>One route, two sources of truth</div><div class='section-copy'>Press Play journey to watch the estimator cross the red GNSS-denied segment.</div>",
    unsafe_allow_html=True,
)
map_column, state_column = st.columns([2.25, 1], gap="large")
with map_column:
    with st.container(border=True):
        st.plotly_chart(animated_route(replay), width="stretch")
with state_column:
    with st.container(border=True):
        st.markdown(
            "<div class='section-kicker'>Run summary</div><div class='section-title'>Navigation state</div>",
            unsafe_allow_html=True,
        )
        st.markdown(
            f"""
            <div class="good-notice notice"><span class="status-dot"></span><strong>Replay complete</strong><br>GNSS handover and recovery were evaluated.</div>
            <br>
            <div class="metric-label">Percorsa endpoint error</div><div class="metric-value">{summary["endpoint_error_m"]:.1f} m</div><br>
            <div class="metric-label">Last-fix endpoint error</div><div class="metric-value">{baseline["endpoint_error_m"]:.1f} m</div><br>
            <div class="metric-label">Final 2-sigma confidence</div><div class="metric-value">{replay["position_uncertainty_m"].iloc[-1]:.1f} m</div>
            """,
            unsafe_allow_html=True,
        )
        st.download_button(
            "Download evaluated journey",
            replay.to_csv(index=False),
            f"{validation['trip_id']}_evaluated.csv",
            "text/csv",
            width="stretch",
        )

overview_tab, signals_tab, integration_tab = st.tabs(
    ["Error timeline", "Sensor signals", "Android integration"]
)
with overview_tab:
    st.plotly_chart(
        error_timeline(replay, outage_start, outage_start + outage_duration),
        width="stretch",
    )
    comparison = pd.DataFrame(metrics).T.rename(
        columns={
            "rmse_m": "RMSE (m)",
            "mae_m": "MAE (m)",
            "max_error_m": "Maximum error (m)",
            "endpoint_error_m": "Endpoint error (m)",
        }
    )
    st.dataframe(comparison.style.format("{:.2f}"), width="stretch")
with signals_tab:
    sensor_left, sensor_right = st.columns(2, gap="large")
    with sensor_left:
        st.plotly_chart(
            sensor_figure(replay, ["accel_x", "accel_y", "accel_z"], "Acceleration"),
            width="stretch",
        )
    with sensor_right:
        st.plotly_chart(
            sensor_figure(replay, ["gyro_x", "gyro_y", "gyro_z"], "Angular velocity"),
            width="stretch",
        )
with integration_tab:
    api_state = "Configured" if api_configured else "Disabled until an API key is set"
    st.markdown(
        f"""
        <div class="api-card">
          <strong>Secure Android ingestion</strong><br><br>
          <small>Status</small><br>{api_state}<br><br>
          <small>CSV upload</small><br><code>POST /api/v1/trips/upload</code><br><br>
          <small>Buffered sensor batches</small><br><code>POST /api/v1/trips/batches</code><br><br>
          <small>Authentication header</small><br><code>X-Percorsa-Key: &lt;secret&gt;</code>
        </div>
        """,
        unsafe_allow_html=True,
    )
    st.caption(
        "Keep the API bound to localhost until the Android client is ready. Use HTTPS before exposing it outside a trusted LAN."
    )

st.markdown(
    "<div class='footer-note'>Percorsa · Axiom Crew · Controlled GNSS-denied navigation evaluation</div>",
    unsafe_allow_html=True,
)
