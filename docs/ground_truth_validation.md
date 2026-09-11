# Ground-truth validation contract

`src.evaluation.ground_truth.audit_reference_frame` audits a future real-drive
recording before it is used for quantitative navigation accuracy claims. It
does not run an estimator and it does not promote GNSS observations to truth.

The accepted independent provenance values are `surveyed`, `rtk`, and
`external_reference`. A normal phone or vehicle GNSS stream is reported as an
observation and is suitable only for a GNSS-referenced diagnostic. The audit
also records canonical IMU availability, timestamp ordering and duplicates,
sample-period statistics, coordinate completeness, GNSS accuracy coverage,
and the largest position step.

The existing `src.evaluation.replay.run_outage_replay` remains the legacy
Python planar-EKF replay. Its reference columns are retained for compatibility;
this phase does not relabel or silently correct its existing benchmark.

For a future Android ESKF replay, supply a CSV containing canonical IMU rows,
timestamps, an independently identified reference trajectory, and GNSS fields
separately marked as estimator inputs versus evaluation-only fields. The
experimental Kotlin provider remains standalone and no production wiring is
changed by this contract.
