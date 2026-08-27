# Architecture decisions

1. IO-VNBD is the only dataset required for the first prototype.
2. Dataset splits are performed by complete trip.
3. The first ML output is forward speed with uncertainty.
4. The prototype filter is a planar EKF.
5. A bias-aware error-state EKF is a later engineering stage.
6. Map corrections are confidence gated and are not nearest-road snapping.
7. The Python replay pipeline is the source of truth for the internal demo.
8. Android integration consumes the same documented interfaces.

