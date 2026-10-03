# IO-VNBD alignment resolution

## Verdict

**C — alignment remains unresolved.** Candidate synchronization works on a minority of segments, but no segment passes the combined timing/frame gate; conservative retention is currently 0%.

The audit opened all 29 train/validation trips and did not open `Vta1a`, `Vta1b`, or `Y1`. Synchronization used no speed fields. The safe regeneration shape is piecewise by monotonic segment, but the current data do not establish a reliable affine/constant mapping or sufficiently verified phone yaw.

## Policy

- split phone and vehicle clocks at non-monotonic rows and gaps >0.5s
- pair only overlapping monotonic segments
- estimate target-independent offset on a 0.1s grid and fit affine drift only when independently justified
- reject drift/weak correlation/low excitation
- estimate gravity-anchored rotation only on accepted segments and persist matrix plus residuals
- resample verified overlap at 10Hz and generate stride-1 50-sample windows
- synchronize target within the same verified mapping; never extrapolate across gaps
- fit normalization on train trips only

## Thresholds

{
  "minimum_sync_correlation": 0.5,
  "maximum_half_window_lag_change_s": 0.2,
  "minimum_acceleration_method_confidence": 0.65,
  "minimum_segment_rows": 20,
  "resampling_hz": 10,
  "window_samples": 50
}

## Retention

The JSON contains per-trip retention, segment clocks, candidate mappings, method comparisons, and rejection evidence. Representative GOOD/BAD signal plots are saved beside this report.
