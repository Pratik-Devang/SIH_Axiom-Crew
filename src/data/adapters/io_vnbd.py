from pathlib import Path
import pandas as pd


# ---------------------------------------------------------------------
# IO-VNBD dataset location
# ---------------------------------------------------------------------

DATA_ROOT = Path(
    "data/raw/io_vnbd/Synchronised V abd S datasets"
)


# ---------------------------------------------------------------------
# Raw column names
# ---------------------------------------------------------------------

COMMON_COLUMNS = {
    "GPS LATITUDE (degrees)": "latitude_deg",
    "GPS LONGITUDE (degrees)": "longitude_deg",
    "GPS ALTITUDE (m)": "altitude_m",
    "GPS SPEED (Kmh)": "speed_kmh",
    "GPS ACCURACY (m)": "gps_accuracy_m",
    "GPS ORIENTATION (Â°)": "gps_orientation_deg",
    "GPS SATELLITES IN RANGE": "gps_satellites",
    "TIME SINCE START (ms)": "time_since_start_ms",
    "DATE (YYYY-MO-DD HH-MI-SS_SSS)": "timestamp",
    "DATE (YYYY-MO-DD HH-MI-SS_SSS": "timestamp",
    

    "ACCELEROMETER X (m/s²)": "accelerometer_x_ms2",
    "ACCELEROMETER Y (m/s²)": "accelerometer_y_ms2",
    "ACCELEROMETER Z (m/s²)": "accelerometer_z_ms2",

    "GRAVITY X (m/s²)": "gravity_x_ms2",
    "GRAVITY Y (m/s²)": "gravity_y_ms2",
    "GRAVITY Z (m/s²)": "gravity_z_ms2",

    "MAGNETIC FIELD X (Î¼T)": "magnetic_x_uT",
    "MAGNETIC FIELD Y (Î¼T)": "magnetic_y_uT",
    "MAGNETIC FIELD Z (Î¼T)": "magnetic_z_uT",
}


# ---------------------------------------------------------------------
# Variant-specific column names
# ---------------------------------------------------------------------

VARIANT_A_COLUMNS = {
    "GYROSCOPE Yaw (rad/s)": "gyroscope_yaw_rads",
    "GYROSCOPE Pitch (rad/s)": "gyroscope_pitch_rads",
    "GYROSCOPE Roll (rad/s)": "gyroscope_roll_rads",

    "ORIENTATION (Yaw) (Â°)": "orientation_yaw_deg",
    "ORIENTATION (Pitch) (Â°)": "orientation_pitch_deg",
    "ORIENTATION (Roll ) (Â°)": "orientation_roll_deg",
}


VARIANT_B_COLUMNS = {
    "GYROSCOPE X (rad/s)": "gyroscope_x_rads",
    "GYROSCOPE Y (rad/s)": "gyroscope_y_rads",
    "GYROSCOPE Z (rad/s)": "gyroscope_z_rads",

    "ORIENTATION (Azimuth) (Â°)": "orientation_azimuth_deg",
    "ORIENTATION (Pitch) (Â°)": "orientation_pitch_deg",
    "ORIENTATION (Roll ) (Â°)": "orientation_roll_deg",
}


# ---------------------------------------------------------------------
# Column cleaning
# ---------------------------------------------------------------------

def clean_column_names(df):
    """
    Remove unnecessary whitespace from raw column names.
    """

    df.columns = (
        df.columns
        .astype(str)
        .str.strip()
    )

    return df


# ---------------------------------------------------------------------
# Detect schema variant
# ---------------------------------------------------------------------

def detect_schema_variant(columns):
    """
    Identify whether a smartphone file uses Variant A or Variant B.
    """

    columns = set(columns)

    variant_a_required = set(
        VARIANT_A_COLUMNS.keys()
    )

    variant_b_required = set(
        VARIANT_B_COLUMNS.keys()
    )

    if variant_a_required.issubset(columns):
        return "A"

    if variant_b_required.issubset(columns):
        return "B"

    raise ValueError(
        "Unknown IO-VNBD smartphone schema variant."
    )


# ---------------------------------------------------------------------
# Read one smartphone CSV
# ---------------------------------------------------------------------

def read_smartphone_file(file_path):
    """
    Read and normalize one IO-VNBD smartphone CSV file.
    """

    df = pd.read_csv(
        file_path,
        encoding="latin1"
    )

    df = clean_column_names(df)

    variant = detect_schema_variant(
        df.columns
    )

    # Rename common columns
    rename_map = dict(COMMON_COLUMNS)

    # Add variant-specific columns
    if variant == "A":
        rename_map.update(
            VARIANT_A_COLUMNS
        )
    else:
        rename_map.update(
            VARIANT_B_COLUMNS
        )

    df = df.rename(
        columns=rename_map
    )

    # Parse timestamp.
    # We initially use the documented format.
    df["timestamp"] = pd.to_datetime(
        df["timestamp"],
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce"
    )

    # Add source metadata
    df["source_file"] = str(
        file_path
    )

    df["schema_variant"] = variant

    return df


# ---------------------------------------------------------------------
# Find all smartphone files
# ---------------------------------------------------------------------

def find_smartphone_files(
    data_root=DATA_ROOT
):
    """
    Find all smartphone CSV files recursively.
    """

    return sorted(
        data_root.rglob("S-*.csv")
    )


# ---------------------------------------------------------------------
# Read the complete smartphone dataset
# ---------------------------------------------------------------------

def load_io_vnbd(
    data_root=DATA_ROOT
):
    """
    Load all IO-VNBD smartphone CSV files.
    """

    files = find_smartphone_files(
        data_root
    )

    if not files:
        raise FileNotFoundError(
            f"No smartphone CSV files found in {data_root}"
        )

    frames = []

    for file_path in files:

        print(
            f"Reading: {file_path}"
        )

        df = read_smartphone_file(
            file_path
        )

        frames.append(df)

    combined = pd.concat(
        frames,
        ignore_index=True
    )

    return combined