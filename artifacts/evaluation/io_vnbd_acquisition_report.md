# IO-VNBD acquisition and provenance investigation

## Verdict

**C — reconstruction from IO-VNBD alone is impossible under the required provenance standard.** The official material documents a manually synchronized collection and physical holder setup, but does not expose the synchronization operation, dropped-sample map, or numeric frame calibration.

## Evidence

- [Official IO-VNBD paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC7907232/): classification A for manual synchronization where possible, nominal sampling, holder, and axis-alignment intent; classification D for a reproducible numeric procedure.
- [Official repository](https://github.com/onyekpeu/IO-VNBD): synchronized/unsynchronized collections and README are present; no synchronization/calibration implementation or metadata was found.

The local source tree contains CSV/JPG data under categorized and uncategorized synchronized folders. The current preparation script discovers S/V files recursively, truncates to equal length, pairs by row index, then sorts/deduplicates/resamples. That is a local assumption, not an author-provided correspondence contract.

## Missing authority

The required missing package is: per-pair offset/drift or exact sample mapping, dropped-row/start-stop map, numeric phone-to-vehicle rotation, Variant A gyro axis definition, and target/reference filtering/resampling provenance.

## New recording

Use raw phone IMU timestamps plus a reference vehicle IMU/CAN/VBOX stream, a shared hardware or visible/acoustic start/end trigger, a rigid measured mount pose, documented speed reference, stationary periods, straight cruising, acceleration/braking, turning, bumps, and optional GNSS-denied driving. Save all event timestamps, trigger times, pose matrices, rates, device metadata, hashes, and recording group IDs.

## Decision

Contact the IO-VNBD authors for the missing authority package, but begin engineering a new independently synchronized Percorsa pilot. Do not regenerate or train from the current IO-VNBD corpus.
