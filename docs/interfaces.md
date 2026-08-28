# Module interfaces

## Dataset adapter output

One time-ordered table per trip using the fields in `src/data/schema.py`.

## Speed model output

- Timestamp
- Forward-speed estimate in metres per second
- Prediction uncertainty or confidence
- Optional motion-state label

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
