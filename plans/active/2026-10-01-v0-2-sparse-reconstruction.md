# Metric sparse reconstruction from immutable v1 captures

## Status
Active

## Purpose and user-visible outcome
The desktop CLI validates/imports a v1 capture, reconstructs its manual keyframes with COLMAP, exports cameras and sparse points, robustly aligns them to the metric AR capture frame, and emits numerical diagnostics and a trajectory viewer. Every result records input hashes, configuration, backend version and failures.

## Scope
Directory and ZIP ingest, image decoding, explicit normalized inputs, deterministic bounded pair graph, isolated COLMAP worker, native Sim(3), depth sanity checks, derived artifacts, acceptance fixtures, local/remote CI and developer commands.

## Non-goals
No dense stereo, meshing, automatic keyframes, capture changes, GUI framework, transfer or later milestones.

## Current state
Clean main at e64ec1c; v0.1 complete. CaptureProject validates and streams four Protobuf journals, imports checked ZIPs, and preserves raw I420 RGB and calibrated depth. desktopApp provides validate/inspect/replay. Native C++20/JNI and deterministic code generation exist. Real captures live under ignored build/acceptance including 31-keyframe moving-long scan. Baseline tools/validate.sh passed on 2026-10-01. COLMAP is being provisioned in an ignored virtual environment.

## Contracts and invariants
Capture schema v1 stays unchanged. Raw directories/packages are read-only. A run destination must be new and outside the raw project. Full journal/asset validation precedes ingest. Eligible frames are stored manual keyframes with valid decodable image, intrinsics and TRACKING pose; exclusions happen before reconstruction and remain fixed. Corrupt projects fail rather than discard records. No retroactive denominator changes. Normalized DTOs retain original CaptureFrame and frame ID, timestamp/clock, calibration and source hashes. Worker-specific representations do not become scan contracts. Missing priors are explicit and no metric success is emitted without trustworthy alignment.

## Mathematics / algorithm
Column vectors; AR T_world_camera is right-handed, metres, column-major, camera +X right/+Y up/-Z forward. Optical camera is +X right/+Y down/+Z forward. D=diag(1,-1,-1) is a proper rotation. T_world_optical=T_world_camera D. COLMAP q=(qw,qx,qy,qz), Hamilton, describes R_optical_sfm with translation t_optical_sfm; C_sfm=-R^T t. Metric output p_world=s R_world_sfm p_sfm+t_world_sfm; camera orientation R_world_optical=R_world_sfm R_sfm_optical (no scale in rotation).
Native alignment uses Eigen SVD/Umeyama least squares with proper-rotation constraint, deterministic robust sampling and inlier refit. At least three noncollinear centers and a measurable metric baseline are required. Default pose residual threshold 0.05m is an explicit configurable prior-consistency policy, not an accuracy promise. Orientation residuals are reported separately. Synthetic exact transforms require scale/rotation/translation errors below 1e-8, noisy/outlier fixtures use their injected noise bounds. Real metric consistency is not ground-truth accuracy.
Pair graph always includes sequence neighbors (default window 5) plus bounded spatial hash candidates (default radius 2m, 12 loop neighbors, view angle <=75 degrees). Feature verification decides overlap. No quadratic all-pairs scan. Priors guide pairing and metric alignment; unconstrained visual BA preserves independent optimized poses, avoiding invented prior covariance.
Depth checks project aligned observed sparse points to original CPU pixels, apply stored H_depthPixels_cpuImagePixels, use raw axial depth in metres, zero invalid and available raw confidence. AR raw and smoothed sources remain separate; timing mismatch and unavailable calibration are reported rather than approximate reassociation.

## Implementation map
- desktopApp reconstruction package: typed ingest, pair graph, run orchestration, diagnostics, viewer.
- desktop-workers/colmap-adapter: pinned Python binding around native COLMAP, process isolation and native model export.
- native/geometry: robust similarity estimator and standalone/JNI boundary with controlled tests.
- tests/fixtures and tools: deterministic synthetic scan generator and reconstruction acceptance, benchmark and CI paths.
- docs: contract/commands and dependency audit.

## Milestones
### M1 — Validated inputs and bounded pair graph
Acceptance: v1 directory/ZIP produce fixed eligible IDs; corrupt assets/metadata fail with named record; images decoded without rotation; graph deterministic, temporal-connected, loop-capable and O(Nk) edges.
Validation: desktop ingest/pair tests and real scan ingest.
### M2 — Proven coordinate conversion and native metric alignment
Acceptance: analytically known transforms, reflection rejection, nonfinite/degenerate failures and robust noisy/outlier recovery; raw/optimized poses remain separate.
Validation: native geometry ctest and desktop conversion tests.
### M3 — End-to-end sparse worker
Acceptance: pinned CPU COLMAP extracts/matches proposed pairs/maps, exports cameras/points/tracks and structured stats; process errors/timeouts/disconnected scenes explicit; config recorded.
Validation: tiny textured nonplanar controlled scan, process-failure tests, real scan experiments.
### M4 — Inspectable aligned output and depth diagnostics
Acceptance: aligned cameras/cloud, capture/raw/aligned trajectories and per-frame position/orientation residuals; calibrated depth fixtures yield expected residuals; failures never claim metric success.
Validation: synthetic end-to-end known scale, depth tests and real output inspection.
### M5 — Acceptance, regression, CI and closure
Acceptance: >95% of objectively eligible frames on suitable reference scan(s), exact metric fixtures, baseline regression, CI, documented reproducible commands, meaningful benchmarks, clean committed/pushed tree. Unsuitable captures retain failed denominator and diagnosis; skill allows diagnostically explained reference failures, but suitable controlled scan must exceed95%.
Validation: tools/validate.sh; dedicated sparse acceptance; full real 31-frame scan and additional scan when useful; GitHub Actions; final status/history.

## Test and validation matrix
Native golden Sim(3); Kotlin ingest/pairs/conversions/depth/process integration; pinned native COLMAP synthetic SIFT smoke; real v1 dataset end-to-end with source hashes before/after; existing domain/store/schema/Android unit/lint/build/native/generation/benchmark regressions; remote emulator JNI/native regression. Hardware capture is unchanged and the completed v0.1 physical evidence is retained.

## Performance / resource budgets
One image decode at a time, configurable process timeout, CPU threads4, feature cap8192 and max image dimension3200 explicit. Store import byte budgets retained. Pair edges O(N(window+loop cap)), bounded spatial candidates per cell. Record stage runtime and graph scaling on1k/10k synthetic inputs. No arbitrary photo-count limit. Heavy generated outputs ignored.

## Migration and compatibility
No raw schema migration. New derived JSON protocol version1, recorded with adapter/config. Unknown original Protobuf fields preserved. New output destination prevents accidental overwrite; failed runs retain logs/status for diagnosis.

## Failure handling
Named validation errors, unsupported codec/model, too few eligible images, no verified overlap/model, partial/disconnected registration, missing priors, rank-deficient alignment, implausible/inconsistent pose/depth evidence, worker nonzero exit/timeout. Emit failure status and diagnostic paths; raw inputs unchanged.

## Risks and mitigations
Long scan contains AR world revisions and low-resolution RGB; robust consensus plus separate orientation/depth evidence will expose inconsistency. Capture scene may lack overlap; inspect all31 frames and verified graph without relabeling failed registrations. COLMAP versions differ: pin3.12.6 and inspect actual API. Camera calibration/image pixel semantics remain measured; no untested rotation/crop.

## Subagent/model plan
Coordinator owns geometry contract, numerical implementation, integration and review. SUBAGENT.md/skill authorizes bounded delegation: Luna high implements ingest/pair glue in separate worktree after fixed interfaces; Luna high implements isolated adapter only after explicit protocol/API contract. Sol xhigh reviews numerical and end-to-end correctness. No Astra planned.

## Progress
- [x] Baseline inspection/regression and real dataset discovery.
- [ ] M1 validated ingest/pair graph.
- [ ] M2 geometry/metric alignment.
- [ ] M3 sparse worker.
- [ ] M4 aligned artifacts/depth/inspection.
- [ ] M5 full acceptance/CI/closure.

## Decisions made during implementation
2026-10-01: use pinned pycolmap3.12.6 out of process; native COLMAP owns SfM, Python is adapter/data tooling, Kotlin owns orchestration, C++ owns metric estimation. Prior use is pairing plus robust alignment; covariance-free v1 poses are not hard constraints in BA.

## Discoveries
v0.1 baseline is newer than memory notes and already includes independent CPU/Camera2 source clocks. Real long scan has31 keyframes and documented world revisions; preserve them for diagnosis.

## Final validation
Pending milestones; initial tools/validate.sh passed (log build/acceptance/v02-baseline.log).

## Outcome
Pending.
