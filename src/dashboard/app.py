import sys
from pathlib import Path

import pandas as pd
import streamlit as st


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "data" / "processed" / "io_vnbd" / "trips"
MODEL_PATH = PROJECT_ROOT / "models" / "speed_model.pt"


st.set_page_config(
    page_title="Percorsa",
    page_icon="🚗",
    layout="wide",
)

st.title("Percorsa")
st.subheader("Vehicle Speed & Navigation Dashboard")

files = sorted(DATA_DIR.glob("*.csv"))

if not files:
    st.error(f"No processed trips found in: {DATA_DIR}")
    st.stop()

trip_names = [f.stem for f in files]

selected = st.selectbox(
    "Select a trip",
    trip_names,
)

trip_path = DATA_DIR / f"{selected}.csv"
trip = pd.read_csv(trip_path)

col1, col2, col3, col4 = st.columns(4)

col1.metric("Trip", selected)
col2.metric("Samples", f"{len(trip):,}")

if "time_since_start_s" in trip:
    duration = (
        trip["time_since_start_s"].iloc[-1]
        - trip["time_since_start_s"].iloc[0]
    )
else:
    duration = 0

col3.metric("Duration", f"{duration:.1f} s")

if "vehicle_speed" in trip:
    max_speed = trip["vehicle_speed"].max()
    mean_speed = trip["vehicle_speed"].mean()
else:
    max_speed = 0
    mean_speed = 0

col4.metric("Max speed", f"{max_speed:.1f} km/h")

st.divider()

st.subheader("Vehicle speed")

if "time_since_start_s" in trip and "vehicle_speed" in trip:
    chart_data = trip.set_index("time_since_start_s")[
        ["vehicle_speed"]
    ]

    st.line_chart(chart_data)

st.subheader("Sensor data")

sensor_columns = [
    column
    for column in [
        "accel_x",
        "accel_y",
        "accel_z",
        "gyro_x",
        "gyro_y",
        "gyro_z",
    ]
    if column in trip.columns
]

if sensor_columns:
    st.line_chart(
        trip.set_index("time_since_start_s")[sensor_columns]
    )

st.subheader("GPS trajectory")

if {"latitude", "longitude"}.issubset(trip.columns):
    gps = trip[
        ["latitude", "longitude"]
    ].rename(
        columns={
            "latitude": "lat",
            "longitude": "lon",
        }
    )

    st.map(gps)

st.divider()

st.write(
    f"Processed trips available: **{len(files)}**"
)

if MODEL_PATH.exists():
    st.success("Speed model found.")
else:
    st.warning("Speed model checkpoint not found.")

