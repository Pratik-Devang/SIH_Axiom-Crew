"""Canonical schema for standardized Percorsa trip data."""

REQUIRED_COLUMNS = (
    "trip_id",
    "time_since_start_s",
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
    "vehicle_speed",
)

OPTIONAL_COLUMNS = (
    "timestamp",
    "latitude",
    "longitude",
    "east",
    "north",
)

IMU_COLUMNS = (
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
)

TARGET_COLUMN = "vehicle_speed"

TARGET_UNIT = "km/hr"
IMU_ACCEL_UNIT = "m/s²"
IMU_GYRO_UNIT = "rad/s"

SAMPLE_RATE_HZ = 10.0
SAMPLE_PERIOD_S = 0.1
