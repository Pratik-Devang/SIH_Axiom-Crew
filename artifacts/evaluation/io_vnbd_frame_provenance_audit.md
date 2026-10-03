# IO-VNBD frame provenance audit

## Verdict

**D — Frame provenance is insufficient and the corpus cannot responsibly be used for final training.**

This is a train/validation-only audit. Locked test trips were excluded from quantitative checks and no model evaluation was run.

## Key findings

- The preparation code copies smartphone accelerometer/gyro columns directly; it does not apply or persist a phone-to-vehicle transform.
- The target is `V-<trip>` **Indicated Vehicle Speed (km/hr)**, paired by row index, then linearly interpolated to the smartphone time grid.
- The six-channel semantic contract and Android deployment contract have an apparent gyro ordering risk: training is forward/left/up, while Android canonical fields are left/forward/up before feature serialization is verified.
- Split and normalization code show no observed train/validation/test leakage, but synchronization correctness is not independently established.

## Detailed machine-readable results

See the accompanying JSON for per-trip physical statistics, axis consistency, provenance evidence, and the deployment comparison.

## Recommendation

Do not retrain yet. Resolve frame provenance and label synchronization first, then regenerate only train/validation processed views under an explicitly verified transform and recompute train-only normalization. Keep Vta1a/Vta1b/Y1 frozen and untouched.
