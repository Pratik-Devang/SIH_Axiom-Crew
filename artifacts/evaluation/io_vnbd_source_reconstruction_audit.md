# IO-VNBD source reconstruction audit

## Verdict

**B — Source data supports a reconstruction, but additional assumptions and validation are required before implementation or regeneration.**

Only train and validation trips were opened. Vta1a, Vta1b, and Y1 were not opened, evaluated, or used.

## Answers

1. **Phone-to-vehicle frame:** no explicit numeric transform exists, but source gravity, orientation, vehicle acceleration, and yaw signals support a label-independent per-trip estimation with validation.
2. **Synchronization:** nominally 10 Hz and manually synchronized where possible, but row-index pairing fails on several analyzed trips because source clocks reset or durations diverge.
3. **Authoritative label:** `Indicated Vehicle Speed (km/hr)` from the vehicle CSV, converted to m/s.
4. **Independent reference:** vehicle GPS `Velocity (km/hr)` and wheel-speed fields exist; agreement is not yet established.
5. **Android contract:** `CanonicalImuSample.toFeatureArray()` is correct by index: forward, left, up, gyro-forward, gyro-left, gyro-up. The existing Android unit test protects this mapping; no new test was needed.
6. **Replacement preprocessing:** timestamp-segmented clock alignment, per-trip transform estimation, verified-overlap resampling, explicit QA, then 10 Hz/50-sample windows.
7. **Missing:** numeric frame calibration, per-trip clock/drop metadata, and definitive speed-reference provenance.
8. **Regeneration:** not yet; first implement and validate the source alignment prototype.

## Important evidence

Examples of source duration disagreement: S2 is 9,201.099 s versus V2 9,387.5 s; S3b has a negative elapsed-time span due to a reset; S4 is 354.780 s versus 9,459.9 s; M is 6,171.748 s versus 10,597.3 s.

The published IO-VNBD documentation describes 10 Hz vehicle and smartphone acquisition, a phone holder, manual synchronization where possible, and vehicle longitudinal/lateral acceleration plus multiple speed fields.

## Next step

Do not regenerate yet. Implement a separate non-destructive source alignment/provenance prototype and validate its transform and clock map on train/validation only. Regeneration is justified only after those QA gates pass; Android and the locked test remain unchanged.
