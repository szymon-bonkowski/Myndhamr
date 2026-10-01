# Metric sparse reconstruction from immutable v1 captures

## Status
Active

## Purpose and user-visible outcome
The desktop CLI validates/imports a v1 capture, reconstructs its manual keyframes with COLMAP, exports cameras and sparse points, robustly aligns them to the metric AR capture frame, and emits numerical diagnostics and a trajectory viewer. Every result records input hashes, configuration, backend version and failures.

## Scope
Directory and ZIP ingest, image decoding, explicit normalized inputs, deterministic bounded pair graph, isolated COLMAP worker, native Sim(3), depth sanity checks, derived artifacts, acceptance fixtures, local/remote CI and developer commands.

## Non-goals
No dense stereo, meshing, automatic keyframes, capture changes, GUI framework, transfer or later milestones.

## Initial state
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
- native/geometry: robust similarity estimator and standalone process boundary with controlled tests; existing JNI contracts are unchanged.
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
Acceptance: >95% of objectively eligible frames on suitable reference scan(s), exact metric fixtures, baseline regression, CI, documented reproducible commands, meaningful benchmarks, clean committed/pushed tree. Unsuitable captures retain failed denominator and diagnosis. The current task requires the controlled and suitable real-reference gates; explanatory diagnostics do not replace >95% real registration acceptance.
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
Long scan contains AR world revisions and low-resolution RGB; robust consensus plus separate orientation/depth evidence will expose inconsistency. Capture scene may lack overlap; inspect all31 frames and verified graph without relabeling failed registrations. COLMAP versions differ: pin3.13.0 and inspect actual API. Camera calibration/image pixel semantics remain measured; no untested rotation/crop.

## Subagent/model plan
Coordinator owns geometry contract, numerical implementation, integration and review. SUBAGENT.md/skill authorizes bounded delegation: Luna high implements ingest/pair glue in separate worktree after fixed interfaces; Luna high implements isolated adapter only after explicit protocol/API contract. Sol xhigh reviews numerical and end-to-end correctness. No Astra planned.

## Progress
- [x] Baseline inspection/regression and real dataset discovery.
- [x] M1 validated ingest/pair graph.
- [x] M2 geometry/metric alignment.
- [x] M3 sparse worker.
- [x] M4 aligned artifacts/depth/inspection.
- [x] M5 independent regression, benchmark, CI, cleanup and stable commits/push.
- [ ] M5 suitable real-reference registration/metric acceptance and milestone closure.

## Decisions made during implementation
2026-10-01: use pinned pycolmap3.13.0 out of process; native COLMAP owns SfM, Python is adapter/data tooling, Kotlin owns orchestration, C++ owns metric estimation. Prior use is pairing plus robust alignment; covariance-free v1 poses are not hard constraints in BA.

## Discoveries
Review found and fixed target-rank degeneracy (source was checked alone), actual ARCore depth label association (specific documented provider mapping now preserves labels), confidence clock checking, orphaned interrupted/TERM-ignoring workers, and top-level failure diagnostics after successful raw SfM. Negative regression tests cover these failures. Final review also reproduced an exporter writing successful or partial diagnostics before timeout: the orchestrator now always records canonical failure and preserves that exact exporter evidence separately. Full CLI timeout probes confirm both cases. Ingest validation now identifies journal/frame IDs for invalid records. Temporal window arithmetic no longer overflows for valid large configured windows, with a bounded small-input regression. Integration schema-key and raw-model-path mismatches were fixed and caught by the complete CLI fixture smoke.
PyCOLMAP3.12/3.13 does not expose imported-pair matching parameters in verify_matches; pin3.13.0 and record its compiled CPU defaults. Matcher hardware-derived threads are an explicit API limit.
v0.1 baseline is newer than memory notes and already includes independent CPU/Camera2 source clocks. Real long scan has31 keyframes and documented world revisions; preserve them for diagnosis.

## Final validation
- Full current-code tools/validate.sh passed (build/acceptance/v02-final-regression.log): domain37 JVM+37 Android host,scan-format4,project-store16,desktop24,Android17 unit tests; existing shared JVM/Android host tests, build/lint, native foundation/geometry, codegen, inventory and benchmark passed. Worker8 and metric3 Python tests passed; textureless/no-overlap, disconnected models, clock association, invalid data and process failure/interruption/timeout included.
- Actual end-to-end controlled scene:10/10 registered (100%), one component,4676 points,30116 observations, maximum camera residual0.000769689m; median orientation residual0.0395293412deg (read final diagnostics for exact measured values). Independent raw-depth median scale ratio0.999850400 (0.014960% error), median absolute residual 0.00321455614m. Native exact Sim(3) tests recover known transform within1e-10 relative scale/rotation/translation bounds; noisy/outlier test recovers within injected tolerances. Latest artifact root build/sparse-acceptance-jRINDp/run; earlier inspected output build/sparse-acceptance-UYWSkK/run has the same measured metrics. Native multithreading can change final point counts slightly; no bitwise-SfM determinism claim.
- Trajectory HTML visually inspected in in-app browser; XY/XZ plane and capture/aligned visibility controls passed. Metrics use common capture-world axes; raw panel separate arbitrary units.
- Benchmark (Java21.0.11):1k frames0.0452s/14940edges/76092candidate checks;10k0.2538s/154589edges/2035844candidate checks. Edge/candidate budgets asserted, no quadratic all-pairs construction.
- Real directory and exported package produce identical normalized31-keyframe inputs. All raw source entries compared byte-for-byte with the accepted v0.1 ZIP; no raw edits. Source ZIP SHA25603754391c451f44a60f0054fb82dea3a336a1580ee357dd02e1bb7afb107bb78.
- Real long default graph:5/31 (16.129%), two sparse components [5,4],110points,360observations; verified graph9components. Broader diagnostic matching across all31 small-scan frames and1.5degree initialization:11/31 (35.484%), components[11,5],371points, verified graph6components [23,3,2,1,1,1]. Default metric alignment fails; diagnostic thresholds0.05/0.1/0.2/0.5m all fail60% consensus. No failed frame was excluded. Paths build/acceptance/v02-real-long-run and v02-real-long-expanded.
- Additional real short capture ingest passes but only2 eligible frames; explicit insufficient-data failure. Other locally available scans have0 keyframes. Documents/final-system-export.scan3d.zip is identical to the already-tested long export. The user confirmed there are no additional scans beyond the31-keyframe capture; the connected phone contains only that project. Suitable real reference acceptance remains externally blocked by capture coverage/data consistency; no claim that the capture lifecycle tests prove SfM quality.
- Remote CI run36927255950 on1c7de00 failed only the immediate descendant-state assertion: Linux reported a runnable child with SIGKILL already pending after asynchronous destroyForcibly. Java21 explicitly permits delayed termination. Independent review confirmed a test observation race; the assertion now polls up to3s for actual absence/zombie state, never accepts a merely pending signal, and retains strict failure for a running child. Targeted six process tests pass locally. Remote rerun36929519603 on a69bcb7 passed all stages. The failed run's skipped emulator stages are superseded by this actually executed successful rerun.

- Remote successful run: https://github.com/szymon-bonkowski/Myndhamr/actions/runs/36929519603. Code revision a69bcb7d4641787979fdb07dc6d0d63f26417c57. Desktop24, Android17 unit, domain37 JVM+37 Android host, scan-format4 and project-store16 tests all show zero errors/failures/skips in downloaded reports. Native host2/2, Python worker8 and metric3 pass. Remote controlled scene10/10 (100%),4674points,max camera residual0.000741835441m,median depth ratio0.999828230555 (0.017177% error); native scheduling explains small differences from local measurements within the declared tolerances. Android JNI gate confirms2 executed tests without failures/skips; Android native foundation and device benchmark passed on API35 x86_64 emulator. Evidence build/acceptance/v02-remote-ci-success.log and v02-ci-success-reports.
- Final audit against sensor-sync-calibration confirms explicit preserved clocks, provider association/rejection, measured pixel mapping, units/axes and controlled source/confidence tests. No new physical accuracy claim; suitable real metric validation remains unavailable. Probe processes, temporary worktrees/browser/server are removed. Repository code is committed/pushed; the remaining final commit records only these validation results.

## Outcome
Implementation and all deterministic/controlled gates are complete. Real-data ingest/failure diagnosis is complete; >95% real reference registration and meaningful real metric validation are NOT passed. Keep this plan active until suitable existing/new physical capture evidence passes the unchanged gate. Do not move to completed or label v0.2 complete merely because the software/controlled fixture works. Independent cleanup, stable commits/push and remote CI are complete. This validation-only final plan update does not change executable code tested at a69bcb7. The remaining external input is a suitable real capture with overlapping viewpoints, usable translational baseline and internally consistent measured poses; v0.3 must wait for the unchanged v0.2 gate.

## Resumed real-reference acceptance — 2026-10-02
The user authorizes a new reconstruction-specific physical capture on the same phone, continuing this plan and branch. Read the completed v0.1 report and supplementary chat v0.1 (01a0f7ca-521f-79a1-9e1f-3ee201208b81). Preserve the old 31-keyframe scan and both failed reconstruction runs. The default sequence window already includes neighbors independently of pose gating; broad matching still leaves disconnected models and no metric consensus, consistent with sparse overlap and documented source-world revisions. A new high-overlap reference is required; this does not change eligibility or the >95% threshold.

Phone SM-S948B is authorized via adb-R3GL60EQ96H-2jO5lO._adb-tls-connect._tcp, with194GB free. Saved hashes for all118 old phone-project files before any capture. Rebuilt the current debug APK and installed with adb install -r, preserving app data; installed SHA256 matches local54edd9b7c12fe596cf11e9b77890405d468c8a3e73da658124875d4120ff285d. Capture sources remain the completed v0.1 implementation at82f0af9. No instrumentation cleanup or raw-project edits are performed.

Acceptance helper tools/capture-keyframes.py uses the existing debug-only CAPTURE_COMMAND receiver and manual keyframe path. It records host commands/status, waits for one actual persisted-keyframe count increment per request, refuses to interrupt an existing active scan, and stops/finalizes its new project on completion or failure. It performs no image scoring or production automatic selection. Test with5 requests at1s, then collect a90-request controlled scan at1s while the user slowly translates around a static textured subject in two passes. Logs/raw collection/derived diagnostics live outside Git under build/acceptance/v02-real-capture-20261002. Validate integrity, decode all images, inspect original clocks/calibration/pose discontinuities and depth availability before default reconstruction. Diagnose any failure before choosing a software fix or targeted physical repeat.

Initial unattended startup test0f0df41e-2404-48c4-8bc7-8b7cabf0972d finalized cleanly after29.82s,863 frames,0 keyframes, all PAUSED (62 initializing/NONE,801 INSUFFICIENT_LIGHT). Integrity/replay pass; no frame was eligible for an RGB request. Helper explicitly failed instead of claiming success. The phone's current view is the physical prerequisite: asked the user only to aim at a well-lit textured static subject and signal readiness. The five-image path test remains pending until tracking is possible; real capture has not started. Post-install hashes confirm all118 old source files unchanged.

After physical readiness, testc6740806-85a1-4bc8-955a-10c3a20e4835 passed5/5 requested/persisted/decoded images, integrity and ingest; clean COMPLETED state with queue/bytes0 and no pending request. Controlled backpack capture10ff175e-1ee9-4583-ac88-ce95c700c790 then recorded71.579s,2116 frames/2100 tracked poses,68 requested/persisted eligible RGB images and68 maps of each depth source. User accidentally activated Gemini with the power button; expected onPause behavior finalized INTERRUPTED rather than relabeling it COMPLETED. Helper detected state change, preserved the project and reported failure to reach90 requests. The68-image scan independently meets the target60–120 count and1–3min duration; its suitability is assessed through unchanged v0.2 reconstruction, not by pretending the capture helper completed its target. No repeat is requested merely because of interruption.

Collected original directory via capture-pull.sh; integrity and full replay pass,68/68 PNG decodes pass,0 source timestamp regressions, all68 CPU images have current-frame provider linkage and stable640x480 measured intrinsics (fx439.0118103/fy439.5448608). CPU keyframe intervals0.866–1.233s,median0.999574s. Original AR trajectory spans1.022/0.243/1.353m; maximum adjacent tracked step0.14949m/rotation4.77664deg (separate maxima). All images and poses remain eligible. Contact sheet shows continuous overlapping backpack/floor views and two passes; default sequence/spatial graph reconstruction is running. Saved depth includes invalid samples unchanged; raw median valid coverage96.785%,confidence>=128 median46.368%,smoothed100%. Current depth freshness policy rejects all maps because depth timestamps differ from AR Frame.timestamp; reviewing documented provider associations before claiming real depth validation.

Real gate passed with unchanged defaults:68/68 (100%), one component,5118 sparse points,32709 observations. Reprojection mean0.723409px/median0.631951px/p951.616952px. Bounded graph has746 pairs (325 temporal,674 spatial with overlap), all verified, one connected component; no unrestricted matching. Robust Sim(3) scale0.1309895682m per SfM unit,54/68 prior inliers,14 early-prefix outliers (35 through438); inlier position median14.698mm/p9518.720mm/max19.831mm,orientation median1.510deg. All-prior max199.288mm remains reported. Raw world step458→459 is149.490mm over33.304ms. Existing estimator handled this revision without raw filtering or registration exclusions; a new coherent-prefix rigid-world-revision synthetic test proves recovery of the majority known Sim(3) to1e-10.

Depth defect fixed: the equality-only gate confused measurement novelty with current-pose reprojection, and similarly rejected paired confidence whose current timestamp differs from the raw estimate. Read-only sensor review confirmed Google's documented raw-depth/current-confidence semantics. Additive normalized associations are inferred only from the full recognized v0.1 recorder pattern; original raw schema/clocks/times remain unchanged, unknown/generic/stale smoothed paths remain conservative. Rerunning the entire same scan in provider-depth-run yields11735 confident raw observations, median sparse/depth ratio0.996386854 (0.361315% discrepancy), median absolute residual11.765mm/p95103.935mm. This is quantitative AR-estimated consistency, not independent physical ground truth. All68 smoothed maps remain explicitly skipped. New known-camera/crop/unequal-timestamp and missing-provenance regressions pass. Review also caught a helper ownership issue; it now checks the selected project before stop and reports unknown startup ownership. Project-switch mock confirms no foreign stop broadcast.

Current full tools/validate.sh passed:desktop25,Android17,domain37 JVM+37 Android host,scan-format4,store16,shared2+2, all0fail/0skip; native2/2,worker8,metric4,build/lint/codegen/inventory/benchmark. Controlled scan10/10,4675 points,max camera residual0.482211mm,depth median ratio0.999915969 (0.008403% discrepancy). Evidence log final-regression.log and build/sparse-acceptance-tKiSiT. Independent reviewer reran metric4/ingest6/native2 and found no remaining defect. Both277 new source files and118 original endurance-scan files retain pre-reconstruction hashes. Durable copies of old failed raw/diagnostics and new capture/derived results are outside the repository at /home/boniek/Documents/Myndhamr/acceptance/v0.2/2026-10-02-backpack. Final committed-code real rerun and fresh remote CI remain before closure.
