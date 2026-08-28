# Module interfaces

## Dataset adapter output

One time-ordered table per trip using the fields in `src/data/schema.py`.

## Speed model output

- Timestamp
- Forward-speed estimate in metres per second
- Prediction uncertainty or confidence
- Optional motion-state label

## IMU filtering contract

- Android and uploaded canonical sensor columns remain raw and are never overwritten.
- Causal Hampel filtering appends `filtered_accel_x/y/z` and
  `filtered_gyro_x/y/z` using only current and prior samples.
- `quality_flags` is a numeric bitmask. Bits 0-5 mark isolated spikes in
  accel X/Y/Z and gyro X/Y/Z; bits 8-13 mark invalid values in the same order.
- `sensor_spike_detected` and `filtered_sensor_columns` provide readable row-level diagnostics.
- TCN inference consumes only the filtered channels resampled to exactly 10 Hz,
  then aligns its predictions back to the original recording timestamps.

## Navigation output

- Timestamp
- East and north position in metres
- Speed in metres per second
- Heading in radians
- Navigation mode: `GNSS`, `dead_reckoning`, or `recovery`
- GNSS availability, accepted trust state, score, and rejection reason
- Position covariance or confidence radius
- Active correction and constraint flags (`GNSS`, `TCN_SPEED`, `ZUPT`, `NHC`)
- Stop-detection and NHC activity/violation diagnostics

During a controlled outage, reference GNSS position and GNSS-derived speed are
evaluation-only and must not enter the estimator. Returning GNSS fixes pass
both the rule-based trust manager and the EKF innovation gate. The first
accepted fixes use inflated measurement noise and are labelled `recovery`
before the estimator returns to `GNSS` mode.

## Evaluation rule

Ground-truth and hidden GNSS samples are available to the evaluator but must not
enter the estimator during the configured outage interval.
