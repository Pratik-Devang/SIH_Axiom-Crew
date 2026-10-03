# Android Logger CSV schema

Each recording has a UUID `session_id`, also included in the filename. Numeric
missing values are empty CSV fields; they are never encoded as zero. Boolean
fields are `true`/`false` when known and empty when unavailable.

The primary navigation/diagnostics file is accompanied by a
`*_raw_imu.csv` sidecar with the same session ID. Each sidecar row is one raw
accelerometer or gyroscope callback, in callback arrival order, with the
original `SensorEvent.timestamp` and only the applicable sensor channels
populated. It is not a synchronized or resampled stream.

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
When Android reports speed or bearing as unavailable, the corresponding CSV
field is empty and no velocity-direction update is applied.

TCN fields identify the canonical 10 Hz sample (`tcn_canonical_timestamp_ns`),
raw and filtered model speeds, inference activity, rate limiting, rejection
count, vehicle-motion gate, active ESKF handoff, ESKF acceptance, and TCN NIS.

Active navigation fields are emitted from the authoritative
`PercorsaEskfProvider`. ESKF fields include initialization/validity, propagation
time and `dt`, ENU and latitude/longitude position, velocity, speed, heading,
quaternion and norm, covariance trace/finite/PSD status, and GNSS/TCN/NHC/ZUPT
measurement outcomes. GNSS, TCN, and logger speeds must not be interpreted as
ground truth without an independent reference source.

Route diagnostics include the matched segment, monotonic route progress,
lateral error, active-heading error, turn state, yaw rate, off-route state, and
reroute state. These fields describe navigation decisions and are not an
independent accuracy reference.

Before initialization, `eskf_initialized=false`, `eskf_valid=false`, and
unavailable ESKF numeric fields are empty. `eskf_valid=true` means the active
state and covariance passed its finite, PSD, and quaternion-norm invariants.
`active_provider=PERCORSA_ESKF` identifies rows whose navigation state came
from that estimator; an uninitialized or invalid estimator is reported as a
degraded bootstrap/fallback row rather than silently relabeled as ESKF.
`eskf_runtime_state` and `eskf_degradation_reason` explain initialization,
calibration, or numerical failure; TCN/NHC vehicle-frame updates remain gated
until `eskf_calibration_active=true`.
