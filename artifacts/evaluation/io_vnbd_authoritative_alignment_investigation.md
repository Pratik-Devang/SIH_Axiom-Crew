# IO-VNBD authoritative alignment investigation

## Verdict

**C — no authoritative reconstruction is possible from the distributed source data.** The official paper and repository establish intended paired collection and physical setup, but provide no reproducible synchronization operation, timing map, dropped-row map, or numeric phone-to-vehicle rotation.

## Authoritative sources

- [IO-VNBD Data in Brief paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC7907232/): simultaneous S/V pairs were manually synchronized where possible and placed in the Synchronised V and S datasets folder vehicle and phone streams were nominally 10 Hz, with phone GPS at 1 Hz the phone was held in a vehicle-mounted holder and the paper shows axis-alignment figures vehicle vibration interfered with measurement precision; no per-trip timing map or numeric transform is supplied
- [Official IO-VNBD repository](https://github.com/onyekpeu/IO-VNBD): distributes separate synchronized and unsynchronized S/V collections and README material does not publish a synchronization algorithm, per-trip offsets, dropped-row map, or frame calibration matrices

## Interpretation

The synchronized folder is evidence of author-selected simultaneous pairs and manual synchronization where possible. It is not an index-level correspondence contract and does not expose the manual operation. The phone holder and axis figures establish intended physical attachment/alignment, not a recoverable per-trip matrix.

The current `prepare_io_vnbd.py` deviates by truncating to the shorter file and pairing rows by index. Phone elapsed time is local, phone absolute datetime and vehicle time-of-day are separate domains, and resets/unequal coverage are present. S3b, S4, and M demonstrate that the distributed timestamps cannot recover exact correspondence.

No fitting, speed-based synchronization, training, regeneration, Android change, or locked-test access occurred. Author synchronization/calibration metadata or an independent reference is required before preprocessing can be implemented safely.
