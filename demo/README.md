# Demo

This folder contains the hackathon demonstration configuration and supporting assets.

## Primary Demo Scenario

Replay one unseen IO-VNBD trip with a reproducible **30-second GNSS outage** and compare Percorsa dead reckoning against pure inertial navigation (unconstrained double-integration).

Run the demo from the repository root:

```powershell
python demo/demo.py
```

Or use the full role-4 scenario:

```powershell
python demo/demo_role4.py
```

## Folder Contents

```text
demo/
├── demo.py              # Main demo entry point (Python replay)
├── demo_role4.py        # Role-4 map constraints + GNSS outage scenario
├── demo_events.jsonl    # Pre-recorded sensor event stream for the demo trip
├── role4_events.jsonl   # Role-4 specific event stream
└── README.md            # This file
```

## Presentation Checklist

- [ ] Demo configuration and selected trip identifier confirmed.
- [ ] Exact presentation sequence rehearsed.
- [ ] Backup screenshots or recording prepared.
- [ ] Known limitations slide ready.

## Known Limitations

- The demo uses the IO-VNBD dataset which lacks fully authoritative ground-truth synchronization metadata (see `artifacts/evaluation/` for the audit).
- The historical benchmark (1.64 m max drift) was generated from a synthetic simulation — **not** from the current TCN + ESKF on real data. Do not cite it as an empirical result.
- Physical RTK-reference field validation remains future work.
