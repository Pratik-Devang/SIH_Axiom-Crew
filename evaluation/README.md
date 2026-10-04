# Frozen TCN evaluation

Run `python evaluation/evaluate.py` from the repository root. The evaluator is separate from training and loads the frozen deployed bundle `artifacts/tcn_best.pt`.

It evaluates the manifest's held-out whole trips (`Vta1a`, `Vta1b`, `Y1`) using the checkpoint's exact six-channel z-score normalization and causal `[batch, 6, 50]` windows. It writes actual outputs beneath `evaluation/results/baseline_v1/`.

The root artifact is the canonical model because the existing evaluator, ONNX exporter and verifier, benchmark, and Android deployment script all select it first. Its legacy model-info count predates the current named split manifest; its checkpoint has no conflicting explicit trip list, and the existing evaluator specifies use of the current manifest in that legacy case. This observation is recorded in `metadata.json`. The evaluator stops on any explicit model, normalization, feature-order, input-shape, or split mismatch rather than silently substituting an artifact.

State-wise results are intentionally omitted. The processed IO-VNBD trip files have no validated state-label column, and the evaluator does not derive one.
