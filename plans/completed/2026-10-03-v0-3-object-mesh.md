# Reproducible metric object meshes from v1 captures

## Status
Complete — 2026-10-03

## Purpose and user-visible outcome
One desktop command validates a scan, reuses or builds its v0.2 sparse reconstruction, runs COLMAP dense stereo/fusion, reconstructs a conservative observed surface and exports independently readable PLY/OBJ/GLB meshes with metric diagnostics.

## Scope
Dense configuration/adapter; metric dense cloud; ball pivoting; conservative cleanup, components and normals; topology validation; three exporters; audited checkpoints; developer inspection; synthetic and real acceptance; CI and benchmarks.

## Non-goals
No capture changes, guided selection, texturing, room TSDF, generative closure, decimation, smoothing, later roadmap features or changes to raw formats.

## Current state
Clean main at 2090e04 includes completed v0.2. SparseCommand owns ingest and isolated workers. colmap/models/component-001 is the registered arbitrary-scale model; aligned-model.json schema 1 supplies capture-world metric Sim(3). Final real reference is build/acceptance/v02-real-capture-20261002/controlled-capture/final-run: 68/68 registered, 5118 points, scale 0.1309895682. Original scan is controlled-capture/data. Baseline tools/validate.sh passed, controlled sparse 10/10 and 4675 points on 2026-10-03. CPU pycolmap 3.13 lacks CUDA; RTX 5070 is available, a separate pinned dense runtime is being provisioned.

## Contracts and invariants
Existing raw/sparse artifacts are read-only. New object run is outside source and sparse directories. Version 1 derived manifest records source/input hashes, versions, configuration, runtime, outputs, failures. Input/config/backend/worker hashes qualify cache reuse; incompatible identities fail with a new-directory instruction. Stage implementation hashes allow compatible downstream module updates with recorded history and audited invalidation. Completed stages have hashes, incomplete stages may be retried without claiming valid cache. Input aligned status, proper finite positive Sim(3), registered cameras and source calibration/images must agree. No arbitrary image-count cap.

## Mathematics / algorithm
Column vectors. Capture-world right-handed metres, +Y up; COLMAP optical camera +X right/+Y down/+Z forward. Dense backend stays in original SfM coordinates, then exactly once p_world=s R_world_sfm p_sfm+t_world_sfm; normals n_world=R_world_sfm n_sfm, unit normalized. R must have determinant +1. PLY/OBJ/GLB store this world frame directly with no recenter, nonidentity root transform, scale compensation or axis flip; glTF is right-handed +Y up/metres. GLB float32 error tested relative to coordinate magnitude.
COLMAP undistortion -> geometrically consistent PatchMatch -> stereo fusion. Open3D ball pivoting uses measured point normals, radii in metres explicitly configured; triangles bridging above maximum edge are rejected. No extrapolated Poisson envelope. Exact duplicate vertices/faces and zero-area faces removed; invalid/nonfinite/index input fails. All components retained by default; optional minimum face threshold requires explicit config and records each removal. Area-weighted vertex normals derive from preserved winding; orientation conflicts, zero normal vertices and boundaries are reported. No arbitrary global flip. Degenerate tolerance scales with bounding diagonal.

## Implementation map
Kotlin ObjectCommand owns scan orchestration and CLI. Isolated desktop-workers/object-mesh runs mature native COLMAP/Open3D through Python adapter (no mobile Python runtime). mesh_geometry.py owns thin geometry validation/cleanup around native/NumPy arrays; exporters.py owns serialization. Existing native Sim(3) remains unchanged. tools/validate-object.sh, acceptance checker and benchmark extend tooling; docs/OBJECT_MESH.md documents actual workflow.

## Milestones
### M1 — baseline and contract
Acceptance: current regressions pass; real and controlled artifacts available; coordinate/licensing contract explicit.
Validation: tools/validate.sh; inspect current artifacts; CUDA import/API probe.
### M2 — geometry and independent exports
Acceptance: box/plane/tetra/component known answers, malformed geometry failure, normals, proper transform and cross-format checks pass. No new unsupported triangles or hidden conversion.
Validation: object-mesh unittest suite with Open3D/trimesh independent readers.
### M3 — dense adapter and checkpointed CLI
Acceptance: valid sparse input runs dense->mesh->all exports, hashes/version/config preserved, failures recorded; unchanged retry reuses completed stages, mutations rejected.
Validation: adapter tests; controlled sparse fixture dense GPU explicit path; Kotlin CLI tests.
### M4 — real acceptance and resources
Acceptance: existing 68-view scan produces valid dense cloud and nonempty mesh, metric sane bounds; independent exports agree, raw hashes unchanged. Inspect orthographic/interactive output; record runtime/counts/sizes/memory without inventing physical ground truth.
Validation: CLI object --sparse existing-final-run; check-object-acceptance.py; raw pre/post hash audit.
### M5 — regressions and closure
Acceptance: CI-equivalent bounded tests and previous regressions pass; remote CI passes; all evidence recorded; plan completed, commits pushed and clean synchronized tree.
Validation: tools/validate.sh; gh run view/log/download; git status/log.

## Test and validation matrix
Known metric plane/box/tetrahedron, proper rotations/translations/scales and signed tetra volume; malformed finite/index/topology; cleanup and explicit component filtering; normal known answers; PLY/OBJ/GLB independent parse and invariant comparison; adapter incomplete/stale/version/error paths; CLI scan/reuse; actual GPU controlled and real dense acceptance. CI tests deterministic geometry/CPU adapter fixtures and prior suites; GPU dense heavy acceptance stays explicit local.

## Performance / resource budgets
Four CPU threads, explicit COLMAP cache and maximum image resolution; subprocess timeout/cancellation follows existing orchestration. Default dense max image 1600 (actual real inputs 640x480), cache 2GB, no photo cap. Arrays stored in binary NPZ/PLY, not enormous JSON. Record process max RSS (lifetime high water), per-stage wall time and bytes. Initial radii 0.005/0.01m, maximum edge 0.02m are explicit conservative MVP profile, revise only with controlled/real evidence. No mesh smoothing.

## Migration and compatibility
Raw schema and sparse schema unchanged. New derived version 1 artifacts only. No existing result overwritten; object checkpoints owned by new run and fingerprint.

## Failure handling
Structured failed stage plus logs preserved for prerequisite/version/camera/hash/coordinate errors, CUDA unavailable, stereo/fusion failure, empty/nonfinite clouds, unsupported edges, empty/invalid mesh and failed export. Missing input cannot masquerade as success. Cache identity mismatch fails explicitly.

## Risks and mitigations
CUDA wheel/GPU architecture compatibility: verify real execution early. Ball pivoting may expose holes: retain honest boundaries and report topology. Floor belongs to observations: retain background, no undocumented object segmentation. Large components or normals ambiguity: report measurable diagnostics, no cosmetic deletion. External validators: independent parser reads geometry and confirms glTF structure.

## Subagent/model plan
Primary Sol high owns contracts, integration, diagnosis and review; escalate numerical reasoning as needed. Luna high read-only baseline explorer and exporter worker receive bounded interfaces. Sol high geometry worker handles local edge cases. Workers have non-overlapping files; primary reviews changes, owns final acceptance/commits.

## Progress
- [x] M1 baseline regression, real-artifact inspection and CUDA execution.
- [x] M2 geometry and exports.
- [x] M3 dense adapter/CLI/checkpoints.
- [x] M4 controlled and real acceptance/resources.
- [x] M5 final regressions, independent export acceptance, remote CI and documented closure.

## Decisions made during implementation
Use COLMAP dense plus Open3D ball pivoting instead of unsupported closure. Keep approved sparse pycolmap CPU runtime distinct from pinned dense CUDA runtime. Preserve all components by default, permitting explicit audited face threshold.

## Discoveries
Existing successful real dataset includes the floor; v0.3 has no segmentation requirement, so the observation-supported surroundings remain part of output.

## Final validation
Final GPU controlled scan-to-mesh command (no manually supplied sparse) passed in build/v03-controlled-final: 10 registered views, 71,131 dense points, 46,047 vertices, 64,924 triangles, 1,094 components. Dimensions 4.8329170773 × 3.6702923009 × 1.2489509275m. Known-plane residual median 1.938mm, p95 7.627mm; >1m known depth span preserved. Dense 128.372s, mesh 2.333s, export 0.448s, initial peak RSS 847,675,392B, initial artifacts 149,264,055B.

Final real command reused the accepted v0.2 sparse through the public object workflow: build/v03-real-final-full. Backpack capture ID10ff175e-1ee9-4583-ac88-ce95c700c790, 68/68 views, 5,118 sparse points. Dense 331,345 points; final 202,621 vertices, 348,412 triangles, 3,162 components. Bounds dimensions 3.4606054282 × 0.8750737608 × 4.3057631445m include measured background/floor. Area 2.6896245532m²; 55,328 boundary edges allowed. Zero degenerate triangles, nonmanifold edges/vertices, winding conflicts, undefined or nonunit normals. Cleanup removes 139,131 unreferenced cloud points, zero triangles and zero components. 688 conflicting native edges require seams: 1,252 faces isolated, 6,976 fan splits, 10,732 new seam vertices; 325 superseded originals unreferenced. Every triangle corner/winding/order and area preserved, no flip/move/delete.

Real dense 623.796s (undistort1.074, stereo600.829, fusion21.394), mesh54.575s, export2.032s; initial process peak RSS1,474,867,200B, initial artifacts871,575,891B. First-run diagnostics saved separately because retries report their own process high-water memory; later log growth increases directory bytes. Export bytes: PLY32,549,944; OBJ41,331,287; GLB9,044,912. Independent trimesh geometry and Open3D stored-normal parsing pass all formats. PLY/OBJ positional error0; GLB max1.1896262e-7m. Dense Sim(3) forward/inverse checks pass; every mesh vertex is exactly a dense observation (support distance0). Khronos2.0.0-dev.3.10 validates both GLBs with0errors/0warnings, one harmless NODE_MATRIX_DEFAULT info.

Raw277files and sparse305files pre/post SHA256 match (582 total). Actual repeated full object command completes in3.172s and reuses dense/mesh/export; mesh-validate independently rechecks hashes/topology/scale. Browser inspection of final offline HTML shows upright object, floor/background and open measured patches; yaw/pitch/zoom/wire controls work. Observed-corner overlay makes the sampled fine triangles readable, without changing stored geometry. Screenshot saved with durable evidence.

Final local tools/validate.sh passes (build/v03-final-all-regression.log): desktop30, shared98, Android host17 tests with0failures/errors/skips, including all prior regressions, native2/2, sparse known-answer acceptance10/10, and object52/52 in fresh pinned CPU runtime. GPU runtime independently passed geometry/adapter suite; actual controlled and real stereo are GPU acceptance, not mocked CI. Plane benchmark6,561vertices/12,800triangles/0.5m²: validation~0.35s, cleanup/normals~0.16s, loading~0.0005s, all exports~0.058s. Linux iOS simulator execution is unavailable and unchanged; no v0.3 iOS path claimed.

Remote CI [37133878420](https://github.com/szymon-bonkowski/Myndhamr/actions/runs/37133878420) passed on final executable revision dfe39ba578eb65c5fad207dc9c50ecdb242ecd90. Downloaded24XML suites: shared98, desktop30, Android host17 tests, zero failures/errors/skips. Log and instrumentation HTML confirm2executed JNI tests without failures/skips; Android native semantic/unknown-field/determinism/rejection tests and benchmark execute successfully on API35 x86_64 emulator. Native host2/2 and Python object52/52 pass; controlled sparse10/10 with4674points. Remote plane benchmark validation0.657s/cleanupNormals0.300s/load0.00081s/exportAll0.110s. Durable acceptance is saved under /home/boniek/Documents/Myndhamr/acceptance/v0.3/2026-10-03-backpack; large data remain outside Git.

## Outcome
v0.3 Object Mesh MVP is complete. The public single-command scan workflow builds/reuses validated sparse inputs and produces audited dense, conservative open mesh and PLY/OBJ/GLB outputs in the inherited metric world frame. Controlled and real GPU reconstruction, independent geometry/normal/schema acceptance, source immutability, resume, all local regression suites and remote emulator CI passed. All applicable milestones satisfy their observable criteria.

Implementation commits:49b620d (dense/geometry/CLI/export foundation),8b69093 (CPU capability regression/Khronos tooling),18a0fbb (stored-normal oracle regression),dfe39ba (readable offline inspector). A final documentation-only completion commit moves this plan; executable code remains identical to the green CI revision. Existing v0.0–v0.2 history and raw/sparse data are preserved; no release or history rewrite.

Limits are explicit: CUDA is needed for the selected dense backend; observed background, holes, disconnected patches and orientation seams remain; no production textures, segmentation, generative closure or independently measured physical backpack ground truth. External GPU stereo/fusion is not promised bitwise determinism. Linux iOS simulator tests are unavailable and not claimed as executed. No unresolved software defect or external blocker remains within v0.3. Next milestone is v0.4 guided capture.

### 2026-10-03 — Blackwell dense backend fix
COLMAP3.13 CUDA actual controlled PatchMatch produced 0 geometric valid depth pixels/fused points, despite passing sparse. RTX5070 matches upstream [Blackwell kernel compiler bug #4213](https://github.com/colmap/colmap/pull/4213). Upgrade only separate object runtime to pinned4.2.1 with upstream workaround; v0.2 stays3.13. Keep failed probe and interrupted real attempt in ignored build directories. No thresholds relaxed to hide failure. Open3D0.20 is required for host Python3.14; use0.20 consistently in CI.

Upgraded4.2.1 controlled stereo now fuses70,005 points: Blackwell issue resolved. API4.2 changed stereo_fusion default output_type to binary reconstruction directory; .ply path requires explicit output_type='ply'. Fixed wrapper rather than weakening validation, and preserved failed run diagnostics. Controlled full rerun and real run pending.

### 2026-10-03 — independent review and real topology diagnosis
Geometry/export known-answer tests passed. Review fixed Path JSON serialization, inspector bounds contract, incomplete validate hashes/expected output sets, source camera/image calibration consistency, explicit numeric rtol, native camera dimensions/model and homogeneous poses, saved manifest identity comparison, accurate unique stage reuse reporting, active fusion cache. Failed incomplete native workspaces are archived before fresh computation because upstream PatchMatch skips existing files without integrity checks. Tests include tampering/corruption, actual native sparse conversion, real Open3D planes, no-mesh failures, retry, and quantitative collapsed/1000x/fragmentation gates (40 tests current).
Real stereo/fusion completed328,628 points. Strict topology gate failed: native BPA produced345,959 triangles with0nonmanifold edges but681 same-direction shared edges. Cleanup preserved every face/component (1796) and only removed137,461 unreferenced points. World dimensions3.50424064/0.87722566/4.32168239m include observed floor/background, not an invented backpack measurement. Preserve build/v03-real-second dense and surface-review artifacts. Diagnose local winding and orientability; do not hide this failure or discard components. Native output orientation consistency is being corrected using measured normals; geometry itself remains fixed. Final fresh controlled/real acceptance runs are required after fixes; first failed attempts are diagnostic evidence only.

### 2026-10-03 — conservative topological seams
Independent parity diagnosis found17 nonorientable components, including the main component.681 conflicting edges are0.125% of edges;1255 incident faces0.363% of faces,0.8245% of area. Every face agrees positively with mean measured normals; forced global orientation contradicts local observations. New split_ambiguous_topology preserves EVERY triangle's exact corner coordinates/winding/order and total area, isolates those1255 faces, splits6431 disconnected vertex fans with6974 additional splits, and creates10739 seam vertices (331 superseded unreferenced originals removed). Result201575vertices345959faces3208components, area2.688126445860563m², identical bounds, zero nonmanifold edges/vertices/winding conflicts/zero normals. Deliberate duplicate positions are seams, never welded after repair. Real saved-geometry repair takes5.578s; Möbius/flippedadjacent/bowtie/closed-tetra known answers prove topology repair without geometry invention. Diagnostics explicitly retain orientation uncertainty and original affected faces/edges.
Stage-specific implementation hashes now preserve valid expensive dense outputs across downstream geometry/export module changes; input/config/worker/backend mismatches still fail. Dense compatibility includes transitive transform helper source. Stale downstream outputs are archived and rebuilt, validation refuses stale dependencies; tests added. Final dense run build/v03-real-current uses stable worker/resource configuration; all geometry changes afterward are handled by this tested dependency boundary.

Final local CI-equivalent tools/validate.sh passed on implementation49b620d (49object tests at that point). Fresh CPU environment exposed a test capability assumption in partial-native-workspace regression; mock CUDA capability for that isolated native-call test and add an explicit no-CUDA failure/checkpoint test. Fresh CPU50/50 and benchmark pass; production worker unchanged, current final GPU jobs remain valid. Independent Khronos GLB validator pinned2.0.0-dev.3.10 is an optional test tool for final outputs.

### 2026-10-03 — independent normal oracle and inspector verification
Independent acceptance originally used trimesh recomputed angle-weighted normals for PLY/glTF, differing from deliberately stored area-weighted normals. Use Open3D stored corner-normal parsing, accounting for Assimp float representation/reordering, while trimesh checks exact geometry/winding. Asymmetric tetra fixture accepts correct stored normals and rejects mutated PLY normals (52 tests total). Installed Khronos validator license verified Apache-2.0 and inventory corrected. Initial browser inspection found sparse sampled triangle visibility poor; increase zoom range/default and overlay sampled observed corners. No export/geometry change. Native stereo/fusion has non-bitwise variation across independent GPU runs: interim328,628 points versus final331,345. Version/config/source/output hashes provide provenance; do not claim bitwise reproducibility for external GPU stereo.
