"""
Percorsa – Axiom Crew | Navigation Evaluation Dashboard v2
"""
from __future__ import annotations
import html, io, math, pathlib, sys
from typing import Optional
import folium
import numpy as np
import pandas as pd
import plotly.graph_objects as go
import streamlit as st
from folium.plugins import AntPath, MiniMap
from streamlit_folium import st_folium

_ROOT = pathlib.Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from src.data.live_trip import normalize_trip_frame
from src.evaluation.metrics import horizontal_errors, trajectory_metrics
from src.evaluation.replay import run_outage_replay
from src.ml.inference import OnnxSpeedPredictor

ONNX_PATH = _ROOT / "artifacts" / "v2" / "tcn.onnx"
NORM_PATH  = _ROOT / "artifacts" / "v2" / "normalization.json"

st.set_page_config(page_title="Percorsa | Navigation Dashboard", page_icon="🧭",
                   layout="wide", initial_sidebar_state="expanded")

st.markdown("""
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap');
html,body,[class*="css"]{font-family:'Inter',sans-serif;}
.stApp{background:#0b0f1a;color:#e2e8f0;}
[data-testid="stSidebar"]{background:linear-gradient(180deg,#0f1628 0%,#111827 100%);border-right:1px solid #1e2a3a;}
[data-testid="stSidebar"] *{color:#cbd5e1!important;}
.hero{background:linear-gradient(135deg,#0ea5e9 0%,#6366f1 50%,#a855f7 100%);border-radius:16px;padding:32px 40px;margin-bottom:24px;}
.hero-title{font-size:2rem;font-weight:700;color:#fff;margin:0;}
.hero-sub{font-size:1rem;color:rgba(255,255,255,0.8);margin-top:6px;}
.hero-badge{display:inline-block;background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.3);border-radius:999px;padding:4px 14px;font-size:0.75rem;font-weight:600;color:#fff;margin-bottom:12px;}
.kpi-card{background:linear-gradient(135deg,#111827 0%,#1a2235 100%);border:1px solid #1e3a5f;border-radius:14px;padding:20px 22px;text-align:center;margin-bottom:8px;}
.kpi-label{font-size:0.72rem;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#64748b;margin-bottom:6px;}
.kpi-value{font-size:1.8rem;font-weight:700;color:#38bdf8;line-height:1;}
.kpi-sub{font-size:0.72rem;color:#475569;margin-top:4px;}
.kpi-good .kpi-value{color:#34d399;} .kpi-warn .kpi-value{color:#f59e0b;} .kpi-bad .kpi-value{color:#f87171;}
.status-strip{display:flex;gap:12px;flex-wrap:wrap;background:#0f1628;border:1px solid #1e2a3a;border-radius:10px;padding:12px 18px;margin-bottom:20px;}
.status-item{display:flex;align-items:center;gap:8px;font-size:0.82rem;color:#94a3b8;}
.dot{width:8px;height:8px;border-radius:50%;flex-shrink:0;}
.dot-green{background:#34d399;box-shadow:0 0 8px #34d39980;} .dot-yellow{background:#f59e0b;} .dot-blue{background:#38bdf8;}
.outage-info{background:linear-gradient(90deg,#1a0a00,#1f1200);border-left:4px solid #f59e0b;border-radius:0 10px 10px 0;padding:12px 18px;font-size:0.85rem;color:#fbbf24;margin:12px 0;}
.footer{text-align:center;padding:24px;color:#334155;font-size:0.75rem;border-top:1px solid #1e2a3a;margin-top:32px;}
[data-baseweb="tab-list"]{background:#0f1628;border-radius:10px;padding:4px;border:1px solid #1e2a3a;}
[data-baseweb="tab"]{border-radius:8px!important;color:#64748b!important;}
[aria-selected="true"]{background:#1e3a5f!important;color:#38bdf8!important;}
</style>
""", unsafe_allow_html=True)

def kpi(label,value,sub="",quality=""):
    st.markdown(f"<div class='kpi-card {quality}'><div class='kpi-label'>{label}</div><div class='kpi-value'>{value}</div><div class='kpi-sub'>{sub}</div></div>",unsafe_allow_html=True)

def _pd():
    return dict(paper_bgcolor="rgba(0,0,0,0)",plot_bgcolor="rgba(15,22,40,0.6)",
                font=dict(family="Inter",color="#94a3b8",size=12),
                margin=dict(l=8,r=8,t=36,b=8),
                legend=dict(bgcolor="rgba(0,0,0,0)"),
                xaxis=dict(gridcolor="#1e2a3a",zerolinecolor="#1e2a3a",color="#64748b"),
                yaxis=dict(gridcolor="#1e2a3a",zerolinecolor="#1e2a3a",color="#64748b"))

def sensor_fig(df,cols,title,units=""):
    COLORS=["#38bdf8","#818cf8","#34d399","#f59e0b","#f87171","#e879f9"]
    fig=go.Figure()
    t=df["time_since_start_s"].to_numpy(float)
    for i,col in enumerate(cols):
        if col not in df.columns: continue
        fig.add_trace(go.Scatter(x=t,y=df[col].to_numpy(float),name=col,mode="lines",
                                  line=dict(color=COLORS[i%len(COLORS)],width=1.5)))
    fig.update_layout(title=dict(text=title,font=dict(color="#e2e8f0",size=14),x=0.02),**_pd())
    if units: fig.update_yaxes(title_text=units)
    fig.update_xaxes(title_text="Time (s)")
    return fig

def error_fig(replay,out_start,out_end):
    t=replay["time_since_start_s"].to_numpy(float)
    err=replay["position_error_m"].to_numpy(float)
    unc=replay["position_uncertainty_m"].to_numpy(float)
    fig=go.Figure()
    fig.add_vrect(x0=out_start,x1=out_end,fillcolor="#f59e0b",opacity=0.08,line_width=0,
                  annotation_text="GNSS denied",annotation_position="top left",annotation_font_color="#f59e0b")
    fig.add_trace(go.Scatter(x=t,y=unc,name="2σ uncertainty",mode="lines",fill="tozeroy",
                             fillcolor="rgba(56,189,248,0.08)",line=dict(color="#38bdf8",width=1,dash="dot")))
    fig.add_trace(go.Scatter(x=t,y=err,name="Horizontal error",mode="lines",line=dict(color="#f87171",width=2)))
    fig.update_layout(title=dict(text="Position error vs time",font=dict(color="#e2e8f0",size=14),x=0.02),**_pd())
    fig.update_yaxes(title_text="Error (m)"); fig.update_xaxes(title_text="Time (s)")
    return fig

def speed_fig(replay):
    t=replay["time_since_start_s"].to_numpy(float)
    fig=go.Figure()
    for col,label,color in [("estimated_speed_mps","EKF speed","#38bdf8"),
                             ("vehicle_speed","Reference (km/h÷3.6)","#34d399"),
                             ("gps_speed_mps","GNSS speed","#f59e0b")]:
        if col not in replay.columns: continue
        vals=pd.to_numeric(replay[col],errors="coerce").to_numpy(float)
        if col=="vehicle_speed": vals=vals/3.6
        fig.add_trace(go.Scatter(x=t,y=vals,name=label,mode="lines",line=dict(color=color,width=1.8)))
    fig.update_layout(title=dict(text="Speed comparison (m/s)",font=dict(color="#e2e8f0",size=14),x=0.02),**_pd())
    return fig

def heading_fig(replay):
    t=replay["time_since_start_s"].to_numpy(float)
    fig=go.Figure()
    if "estimated_heading_rad" in replay.columns:
        fig.add_trace(go.Scatter(x=t,y=np.degrees(replay["estimated_heading_rad"].to_numpy(float)),
                                 name="EKF heading",mode="lines",line=dict(color="#a78bfa",width=1.8)))
    if "gps_bearing_deg" in replay.columns:
        fig.add_trace(go.Scatter(x=t,y=replay["gps_bearing_deg"].to_numpy(float),
                                 name="GNSS bearing",mode="lines",line=dict(color="#fb923c",width=1.2,dash="dot")))
    fig.update_layout(title=dict(text="Heading (degrees)",font=dict(color="#e2e8f0",size=14),x=0.02),**_pd())
    return fig

def build_map(replay,out_start,out_end,show_circles=True):
    lat_col=next((c for c in ("latitude","latitude_deg") if c in replay.columns),None)
    lon_col=next((c for c in ("longitude","longitude_deg") if c in replay.columns),None)
    lat_est=replay["estimated_latitude"].to_numpy(float)
    lon_est=replay["estimated_longitude"].to_numpy(float)
    center_lat=float(np.nanmean(lat_est)); center_lon=float(np.nanmean(lon_est))
    m=folium.Map(location=[center_lat,center_lon],zoom_start=15,tiles=None,prefer_canvas=True)
    folium.TileLayer(tiles="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
                     attr="CARTO",name="Dark (default)",max_zoom=19).add_to(m)
    folium.TileLayer(tiles="OpenStreetMap",name="OpenStreetMap").add_to(m)
    folium.TileLayer(tiles="https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                     attr="Esri",name="Satellite",max_zoom=19).add_to(m)
    if lat_col and lon_col:
        glat=pd.to_numeric(replay[lat_col],errors="coerce").to_numpy(float)
        glon=pd.to_numeric(replay[lon_col],errors="coerce").to_numpy(float)
        valid=np.isfinite(glat)&np.isfinite(glon)
        if valid.sum()>=2:
            folium.PolyLine(list(zip(glat[valid],glon[valid])),color="#34d399",weight=3,
                            opacity=0.7,tooltip="GNSS ground truth",dash_array="6 4").add_to(m)
    times=replay["time_since_start_s"].to_numpy(float)
    out_mask=(times>=out_start)&(times<=out_end)
    before_mask=times<out_start; after_mask=times>out_end
    def _seg(mask,color,name,w=3.5):
        lats=lat_est[mask]; lons=lon_est[mask]
        v=np.isfinite(lats)&np.isfinite(lons)
        if v.sum()>=2:
            folium.PolyLine(list(zip(lats[v],lons[v])),color=color,weight=w,
                            opacity=0.95,tooltip=name).add_to(m)
    _seg(before_mask,"#38bdf8","EKF — GNSS active")
    _seg(out_mask,"#f59e0b","EKF — GNSS DENIED",w=4.5)
    _seg(after_mask,"#818cf8","EKF — GNSS recovered")
    valid_all=np.isfinite(lat_est)&np.isfinite(lon_est)
    if valid_all.sum()>=2:
        AntPath(locations=list(zip(lat_est[valid_all],lon_est[valid_all])),
                color="#0ea5e9",weight=2,opacity=0.4,delay=600,
                dash_array=[10,20],tooltip="Estimated route (animated)").add_to(m)
    sl=float(lat_est[np.isfinite(lat_est)][0]); slo=float(lon_est[np.isfinite(lon_est)][0])
    el=float(lat_est[np.isfinite(lat_est)][-1]); elo=float(lon_est[np.isfinite(lon_est)][-1])
    folium.Marker([sl,slo],tooltip="Trip start",icon=folium.Icon(color="green",icon="play",prefix="fa")).add_to(m)
    folium.Marker([el,elo],tooltip="Trip end",icon=folium.Icon(color="red",icon="stop",prefix="fa")).add_to(m)
    out_lats=lat_est[out_mask]; out_lons=lon_est[out_mask]
    vout=np.isfinite(out_lats)&np.isfinite(out_lons)
    if vout.sum()>=1:
        folium.Marker([float(out_lats[vout][0]),float(out_lons[vout][0])],
                      tooltip=f"GNSS outage starts t={out_start:.0f}s",
                      icon=folium.Icon(color="orange",icon="exclamation-triangle",prefix="fa")).add_to(m)
        folium.Marker([float(out_lats[vout][-1]),float(out_lons[vout][-1])],
                      tooltip=f"GNSS outage ends t={out_end:.0f}s",
                      icon=folium.Icon(color="blue",icon="check-circle",prefix="fa")).add_to(m)
    if show_circles:
        step=max(1,len(replay)//80)
        for i in range(0,len(replay),step):
            if not(np.isfinite(lat_est[i]) and np.isfinite(lon_est[i])): continue
            err=float(replay["position_error_m"].iloc[i])
            unc=float(replay["position_uncertainty_m"].iloc[i])
            ts=float(replay["time_since_start_s"].iloc[i])
            cc="#f59e0b" if out_mask[i] else "#38bdf8"
            folium.CircleMarker([lat_est[i],lon_est[i]],radius=max(3,min(unc/3,12)),
                                color=cc,fill=True,fill_color=cc,fill_opacity=0.25,
                                tooltip=f"t={ts:.1f}s err={err:.1f}m σ={unc:.1f}m").add_to(m)
    MiniMap(toggle_display=True,position="bottomright").add_to(m)
    folium.LayerControl(position="topright").add_to(m)
    return m

# ── Sidebar ──
with st.sidebar:
    st.markdown("## 🧭 Percorsa")
    st.markdown("**GNSS-denied Navigation Evaluation**")
    st.markdown("---")
    st.markdown("### 📂 Data Source")
    source_mode=st.radio("Input mode",["Upload CSV","Demo (synthetic)"],label_visibility="collapsed")
    uploaded_file=None
    if source_mode=="Upload CSV":
        uploaded_file=st.file_uploader("Upload Android sensor CSV",type=["csv"])
    st.markdown("---")
    st.markdown("### ⚙️ TCN Model")
    onnx_ok=ONNX_PATH.exists() and NORM_PATH.exists()
    if onnx_ok:
        st.success("TCN ONNX loaded ✓"); use_tcn=st.checkbox("Use TCN speed",value=True)
    else:
        st.warning("ONNX not found"); use_tcn=False
    st.markdown("---")
    st.markdown("### 🗺️ Map Options")
    map_height=st.slider("Map height (px)",400,900,600,50)
    show_circles=st.checkbox("Show uncertainty circles",value=True)
    st.markdown("---"); st.caption(f"Root: `{_ROOT.name}`"); st.caption("Dashboard v2.0 — Axiom Crew")

# ── Hero ──
st.markdown("""
<div class='hero'>
  <div class='hero-badge'>🛰️ GNSS-DENIED NAVIGATION</div>
  <div class='hero-title'>Percorsa Navigation Dashboard</div>
  <div class='hero-sub'>Axiom Crew · ESKF · TCN Speed Estimation · Real-map Replay</div>
</div>""",unsafe_allow_html=True)

# ── Demo data ──
@st.cache_data
def _demo_trip():
    rng=np.random.default_rng(42); n=600; dt=0.1; t=np.arange(n)*dt
    lat0,lon0=28.6139,77.2090
    speed=np.clip(rng.normal(12,2,n),0,25)
    heading=np.zeros(n); heading[300:]=np.pi/2
    east=np.cumsum(speed*np.sin(heading)*dt); north=np.cumsum(speed*np.cos(heading)*dt)
    M=6_335_439.0; N=6_378_137.0
    lat=lat0+np.degrees(north/M); lon=lon0+np.degrees(east/(N*math.cos(math.radians(lat0))))
    return pd.DataFrame({"trip_id":["demo"]*n,"time_since_start_s":t,
        "accel_x":rng.normal(0.1,0.05,n),"accel_y":rng.normal(0,0.05,n),"accel_z":rng.normal(-9.81,0.1,n),
        "gyro_x":rng.normal(0,0.005,n),"gyro_y":rng.normal(0,0.005,n),
        "gyro_z":np.concatenate([np.zeros(290),rng.normal(0.05,0.01,20),np.zeros(290)]),
        "latitude":lat,"longitude":lon,"vehicle_speed":speed*3.6,
        "gps_accuracy_m":rng.uniform(2,6,n),"gps_speed_mps":speed+rng.normal(0,0.3,n),
        "gps_bearing_deg":np.degrees(heading)})

raw_df=None; source_error=""; validation=None
if source_mode=="Upload CSV" and uploaded_file is not None:
    try:
        raw_df=pd.read_csv(io.BytesIO(uploaded_file.read()))
        raw_df,validation=normalize_trip_frame(raw_df,uploaded_file.name)
    except Exception as exc: source_error=str(exc)
elif source_mode=="Demo (synthetic)":
    raw_df=_demo_trip()
    try: raw_df,validation=normalize_trip_frame(raw_df,"demo_trip")
    except Exception as exc: source_error=str(exc)

if source_error:
    st.error(f"❌ **Trip rejected:** {source_error}"); st.stop()
if raw_df is None or validation is None:
    st.markdown("<div style='text-align:center;padding:60px;color:#475569;'><div style='font-size:3rem;'>📤</div><div style='font-size:1.1rem;color:#94a3b8;'>Upload Android CSV or switch to Demo mode</div></div>",unsafe_allow_html=True); st.stop()

trip=raw_df; v=validation.as_dict(); trip_id=html.escape(str(v["trip_id"]))
times=trip["time_since_start_s"].to_numpy(float)
dt_arr=np.diff(times); sample_rate=1.0/np.median(dt_arr[dt_arr>0]) if len(dt_arr)>0 else 10.0
duration=float(times[-1]-times[0]); has_gnss=bool(v["replay_ready"])
speed_src_lbl="TCN ONNX" if(onnx_ok and use_tcn) else "Reference speed"

st.markdown(f"""
<div class='status-strip'>
  <div class='status-item'><div class='dot dot-green'></div>Trip: <strong>{trip_id}</strong></div>
  <div class='status-item'><div class='dot {"dot-green" if has_gnss else "dot-yellow"}'></div>GNSS: {"available" if has_gnss else "IMU-only"}</div>
  <div class='status-item'><div class='dot {"dot-blue" if onnx_ok else "dot-yellow"}'></div>{speed_src_lbl}</div>
  <div class='status-item'><div class='dot dot-green'></div>{int(v["rows"]):,} samples · {sample_rate:.1f} Hz · {duration:.1f}s</div>
</div>""",unsafe_allow_html=True)

if not has_gnss:
    c1,c2=st.columns(2)
    with c1: st.plotly_chart(sensor_fig(trip,["accel_x","accel_y","accel_z"],"Acceleration","m/s²"),use_container_width=True)
    with c2: st.plotly_chart(sensor_fig(trip,["gyro_x","gyro_y","gyro_z"],"Gyroscope","rad/s"),use_container_width=True)
    st.info("Add lat/lon to enable map replay."); st.stop()

# ── Outage controls ──
st.markdown("### ⚙️ Configure GNSS outage window")
with st.container(border=True):
    ctrl1,ctrl2,ctrl3=st.columns([1,1,1.6])
    default_start=min(60.0,max(0.0,duration*0.35))
    with ctrl1: outage_start=st.slider("Outage starts (s)",0.0,max(duration-1.0,1.0),min(default_start,max(duration-1.0,1.0)),1.0)
    max_out=max(1.0,duration-outage_start)
    with ctrl2: outage_dur=st.slider("Outage duration (s)",1.0,max_out,min(30.0,max_out),1.0)
    with ctrl3:
        st.markdown(f"<div class='outage-info'>⚠️ <strong>GNSS withheld</strong> t = {outage_start:.0f}s → {outage_start+outage_dur:.0f}s ({outage_dur:.0f}s). Ground-truth kept for error measurement.</div>",unsafe_allow_html=True)
outage_end=outage_start+outage_dur

# ── Run replay ──
speed_pred=speed_var=None; speed_status="Reference speed"
if onnx_ok and use_tcn:
    try:
        predictor=OnnxSpeedPredictor(ONNX_PATH,NORM_PATH); speed_pred,speed_var=predictor.predict(trip); speed_status="TCN ONNX"
    except Exception: speed_status="Reference speed (TCN failed)"

with st.spinner("🔄 Running GNSS-denied replay…"):
    replay,metrics=run_outage_replay(trip,outage_start,outage_dur,speed_pred,speed_var)

distance_km=float(np.hypot(np.diff(replay["east"].to_numpy()),np.diff(replay["north"].to_numpy())).sum()/1000.0)
summary=metrics["percorsa"]; baseline=metrics["last_fix"]

# ── KPIs ──
st.markdown("<br>",unsafe_allow_html=True)
k1,k2,k3,k4,k5,k6=st.columns(6)
with k1: kpi("Distance",f"{distance_km:.2f} km","GNSS ground-truth")
with k2: kpi("GNSS denied",f"{outage_dur:.0f} s",f"From t={outage_start:.0f}s")
with k3: kpi("RMSE",f"{summary['rmse_m']:.1f} m","EKF horizontal",
             "kpi-good" if summary['rmse_m']<20 else "kpi-warn" if summary['rmse_m']<50 else "kpi-bad")
with k4: kpi("Endpoint drift",f"{summary['endpoint_error_m']:.1f} m","Before GNSS recovery",
             "kpi-good" if summary['endpoint_error_m']<30 else "kpi-warn")
with k5: kpi("Baseline drift",f"{baseline['endpoint_error_m']:.1f} m","Last-fix dead-reckoning")
with k6: kpi("Speed source",speed_status,"10 Hz EKF update")

# ── Map ──
st.markdown("<br>",unsafe_allow_html=True)
st.markdown("""
<div style='margin-bottom:8px;'>
  <span style='font-size:0.7rem;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;color:#0ea5e9;'>Route replay</span><br>
  <span style='font-size:1.35rem;font-weight:700;color:#f1f5f9;'>Live map — real tiles, real coordinates</span><br>
  <span style='font-size:0.82rem;color:#64748b;'>🟢 GNSS ground truth &nbsp;|&nbsp; 🔵 EKF active &nbsp;|&nbsp; 🟠 GNSS denied &nbsp;|&nbsp; 🟣 Recovered &nbsp;|&nbsp; Circles = uncertainty σ</span>
</div>""",unsafe_allow_html=True)

map_col,state_col=st.columns([2.4,1],gap="large")
with map_col:
    m=build_map(replay,outage_start,outage_end,show_circles)
    st_folium(m,height=map_height,use_container_width=True)

with state_col:
    with st.container(border=True):
        st.markdown("#### 🧭 Navigation state")
        improvement=baseline["endpoint_error_m"]-summary["endpoint_error_m"]
        st.markdown(f"""
        <div style="background:#0a1f0a;border:1px solid #166534;border-radius:10px;padding:14px;margin-bottom:12px;">
            <span style="color:#4ade80;font-weight:700;">✓ Replay complete</span><br>
            <span style="color:#64748b;font-size:0.8rem;">EKF vs last-fix over {outage_dur:.0f}s outage</span>
        </div>""",unsafe_allow_html=True)
        for label,val,unit,color in [
            ("EKF endpoint error",summary['endpoint_error_m'],"m","#38bdf8"),
            ("Last-fix endpoint error",baseline['endpoint_error_m'],"m","#f59e0b"),
            ("EKF improvement",improvement,"m","#34d399"),
            ("EKF RMSE (outage)",summary['rmse_m'],"m","#818cf8"),
            ("Final 2σ uncertainty",float(replay["position_uncertainty_m"].iloc[-1]),"m","#94a3b8"),
        ]:
            st.markdown(f"<div style='margin-bottom:10px;'><div style='font-size:0.7rem;color:#475569;text-transform:uppercase;'>{label}</div><div style='font-size:1.4rem;font-weight:700;color:{color};'>{val:.1f} <span style='font-size:0.9rem;font-weight:400;'>{unit}</span></div></div>",unsafe_allow_html=True)
        st.download_button("⬇️ Download evaluated CSV",replay.to_csv(index=False),
                           f"{v['trip_id']}_evaluated.csv","text/csv",use_container_width=True)

# ── Tabs ──
st.markdown("<br>",unsafe_allow_html=True)
t1,t2,t3,t4,t5=st.tabs(["📉 Error timeline","🚗 Speed analysis","🧭 Heading","📡 IMU signals","📋 Raw data"])
with t1:
    st.plotly_chart(error_fig(replay,outage_start,outage_end),use_container_width=True)
    comp_df=pd.DataFrame(metrics).T.rename(columns={"rmse_m":"RMSE (m)","mae_m":"MAE (m)","max_error_m":"Max error (m)","endpoint_error_m":"Endpoint (m)"})
    st.dataframe(comp_df.style.format("{:.2f}"),use_container_width=True)
with t2:
    st.plotly_chart(speed_fig(replay),use_container_width=True)
with t3:
    st.plotly_chart(heading_fig(replay),use_container_width=True)
with t4:
    i1,i2=st.columns(2,gap="large")
    with i1: st.plotly_chart(sensor_fig(replay,["accel_x","accel_y","accel_z"],"Acceleration","m/s²"),use_container_width=True)
    with i2: st.plotly_chart(sensor_fig(replay,["gyro_x","gyro_y","gyro_z"],"Gyroscope","rad/s"),use_container_width=True)
with t5:
    disp=["time_since_start_s","estimated_latitude","estimated_longitude","position_error_m","position_uncertainty_m","estimated_speed_mps","gnss_available"]
    show=[c for c in disp if c in replay.columns]
    st.dataframe(replay[show].style.format({c:"{:.4f}" for c in show if c!="gnss_available"}),use_container_width=True,height=400)

st.markdown("<div class='footer'>Percorsa · Axiom Crew · GNSS-denied resilient vehicle navigation · Dashboard v2.0</div>",unsafe_allow_html=True)
