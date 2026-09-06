# Android Logger CSV schema

Each recording has a UUID `session_id`, also included in the filename. Numeric
missing values are empty CSV fields; they are never encoded as zero. Boolean
fields are `true`/`false` when known and empty when unavailable.

`timestamp_ns` and `accel_timestamp_ns` are the Android `SensorEvent.timestamp`
monotonic clock in nanoseconds. Rows are emitted on the accelerometer clock;
`gyro_timestamp_ns` identifies the timestamp of the latched raw gyroscope
sample. The raw `accel_*` and `gyro_*` columns remain in the phone frame and
are not rotated, smoothed, normalized, or resampled. Derived vehicle-frame,
linear-acceleration, gravity, and quaternion columns are separate fields.

GNSS time is recorded as both `gnss_timestamp_ms` (`Location.time`, Unix epoch
milliseconds) and `gnss_elapsed_realtime_ns` (`Location.elapsedRealtimeNanos`,
monotonic nanoseconds), with latitude, longitude, altitude, accuracy, speed,
and bearing. These are reference measurements, not ground truth.

TCN fields identify the canonical 10 Hz sample (`tcn_canonical_timestamp_ns`),
raw and filtered model speeds, inference activity, rate limiting, rejection
count, vehicle-motion gate, active INS injection, ESKF acceptance, and TCN NIS.

Active navigation fields are explicitly separate from the ESKF shadow fields.
The active provider remains `SimplifiedInsProvider`; ESKF fields are diagnostic
shadow output only. ESKF fields include initialization/validity, propagation
time and `dt`, ENU and latitude/longitude position, velocity, speed, heading,
quaternion and norm, covariance trace/finite/PSD status, and GNSS/TCN/NHC/ZUPT
measurement outcomes. GNSS, TCN, and logger speeds must not be interpreted as
ground truth without an independent reference source.
