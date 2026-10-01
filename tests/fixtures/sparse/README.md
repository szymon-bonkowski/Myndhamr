# v0.2 sparse fixtures

`tools/render-sparse-fixture.py` deterministically renders ten cameras viewing three independently textured metric planes, with stored analytic poses and axial depth. The seed is 20261001. `:desktopApp:generateSparseFixture` writes the canonical v1 Protobuf project using the existing store. Raw evidence and every generated reconstruction stay in ignored build directories. No downloaded media or huge fixtures are committed.

`tools/validate-sparse.sh` exercises validation, normalization, pair graph, actual native COLMAP SIFT/matching/mapping, C++ alignment, depth and trajectory export. Acceptance requires >95% fixed eligible frames, one component, nonzero points/tracks, maximum camera residual<2mm, maximum orientation residual<0.1deg, p95 point reprojection<1pixel and median independent depth-scale error<0.5%. These bounds accommodate subpixel interpolation and quantized millimetre depth; they are fixture tolerances, not physical-phone accuracy guarantees.

Native exact goldens separately prove transform direction, proper rotation and known scale over several orders of magnitude to1e-10 relative accuracy, reject reflection and source/target degeneracy, and test injected outliers/noise. Worker smoke tests also cover a three-view nonplanar scene. Negative ingest/pair/worker/process/depth tests run in normal deterministic CI.
