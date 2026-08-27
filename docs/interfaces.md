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
- GNSS trust state
- Position covariance or confidence radius
- Active correction and constraint flags

## Evaluation rule

Ground-truth and hidden GNSS samples are available to the evaluator but must not
enter the estimator during the configured outage interval.

