# IO-VNBD alignment prototype

## Verdict

**B / NO-GO for regeneration:** Prototype methodology is implementable, but synchronization and frame quality are not yet sufficient to authorize corpus regeneration.

Only the requested train/validation sample trips were opened. Vta1a, Vta1b, and Y1 were excluded and not opened.

## Method

Synchronization uses only rotation-invariant phone dynamic acceleration and vehicle indicated longitudinal/lateral acceleration. Rotation uses a positive-gravity Up anchor and a weighted, proper Kabsch solution against vehicle longitudinal/lateral dynamics. Speed, GPS speed, wheel speed, and future target values are excluded from optimization.

## Regeneration gate

- Temporal synchronization: **not yet reliable**; source resets and duration mismatches require segment handling.
- Frame estimation: **not yet reliable for all segments**; low-dynamics segments are poorly constrained and Variant A gyro semantics remain ambiguous.
- Label QA: indicated speed remains the candidate primary label; vehicle GPS velocity is reported as an independent QA reference.

Per-segment matrices, offsets, residuals, confidence, rejection reasons, and label QA are in the JSON.
