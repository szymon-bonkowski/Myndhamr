# ADR-0004: Metric dense mesh products and isolated adapters

- Status: Accepted
- Date: 2026-10-03
- Supersedes: none

## Context
The v0.3 task requires dense object reconstruction from the existing registered v0.2 scene, conservative observed surfaces and PLY/OBJ/GLB, without changing capture evidence or sparse estimation. Dense COLMAP requires CUDA and the local RTX5070 exposes the upstream Blackwell kernel compiler defect in3.13.

## Decision
Kotlin retains project validation and process orchestration. A separate version-pinned COLMAP4.2.1 dense adapter owns undistortion, geometric PatchMatch/filtering and stereo fusion; sparse remains3.13.0. Native Open3D0.20 ball pivoting consumes observed points/normals in capture-world metres. NumPy native operations and thin Python worker code validate/clean geometry; the mobile runtime gains no Python dependency.

Dense coordinates remain in the original SfM frame until one explicit proper Sim(3): p_world=s R_world_sfm p_sfm+t_world_sfm; normals use R only. PLY/OBJ and glTF2/GLB store the same right-handed +Y-up world geometry in metres directly. No normalization, recentering, reflection, hidden root scale, smoothing or unsupported hole filling. All components are retained by default; explicit filter thresholds record deletions. If locally measured normals induce nonorientable adjacency, isolate the conflicting-edge incident faces and split disconnected vertex fans into explicit open seams. Every triangle corner/position/winding/area remains unchanged; seam duplicates must not be welded afterward. Record affected face/edge IDs and measured orientation uncertainty.

Object artifacts are independent version1 derived manifests/binary arrays/exports outside capture and sparse directories. Configuration, source reconstruction/hash, image/calibration input hashes, backend versions and implementation hashes qualify reuse. Completed stages require closed expected output sets with SHA256 checks; incomplete native workspace files are evidence only, never trusted checkpoints. Failure/timeout does not produce a successful job. Changed input/configuration/worker/backend identity requires a new object directory. Stage implementation hashes include transitive dense-transform helpers; geometry/export-module changes archive/rebuild only affected downstream products, while unchanged dense outputs remain reusable.

## Rationale
COLMAP/Open3D follow the approved permissive-first mature stack. Ball pivoting exposes missing observations without extrapolating a Poisson envelope. Explicit process boundaries contain native failures. Different backend environments avoid altering accepted sparse behavior. Correctness gates use numerical known answers and independent export reads.

## Alternatives considered
OpenMVS remains excluded from proprietary default dependencies because of AGPL. Poisson closure would require additional support trimming/uncertainty decisions beyond this conservative MVP. Linking engines into the JVM/native mobile library adds ownership and crash surface without benefiting desktop orchestration.

## Consequences
CUDA stereo is an explicit capability requirement; CPU CI exercises bounded actual geometry plus orchestration/negative adapters, with controlled and real GPU acceptance separate. The observed background remains present because segmentation is outside v0.3. Open mesh boundaries are allowed; valid manifold edges/winding and normalized normals are required. Quantitative metric consistency is not independently measured physical accuracy.

## Compatibility and migration
No raw schema, clock, coordinate or v0.2 artifact migration. New object artifacts can be discarded/rebuilt; old raw/sparse artifacts remain immutable. Separately installed development workers are not a distributed release bundle; exact wheel/CUDA notices need review when bundling.

## Validation / evidence
Controlled plane/tetra/box and proper Sim(3) fixtures; real Open3D ball pivoting; independent Open3D/trimesh parsers and GLB structure/winding; content-cache failures; full GPU controlled/68-view backpack acceptance recorded in v0.3 ExecPlan. [COLMAP Blackwell fix](https://github.com/colmap/colmap/pull/4213), [Open3D surface APIs](https://www.open3d.org/docs/latest/tutorial/geometry/surface_reconstruction.html), [glTF2 coordinate contract](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#coordinate-system-and-units).

## Revisit when
CPU dense stereo, independently calibrated dense depth support or a later surface/texturing specification changes capability or uncertainty requirements.
