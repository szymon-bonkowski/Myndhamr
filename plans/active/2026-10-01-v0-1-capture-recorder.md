# Auditable Android capture and desktop replay — v0.1

## Status
Active

## Purpose and user-visible outcome
Record real Android RGB/keyframes, tracking/pose/calibration, depth/confidence when supported, and raw IMU. Export a versioned, integrity-checked .scan3d project and inspect metadata on desktop. Complete automated and physical acceptance including approximately 15 minutes of scanning.

## Scope
Camera2/ARCore shared-camera capture, manual keyframes, platform capability reporting, timing diagnostics, bounded persistence, export and headless inspector. Keep v0.0 infrastructure.

## Non-goals
No reconstruction, automatic selection, guidance, networking, mesh/texturing or Smart processing.

## Current state
Baseline 7d4142a on clean main, origin git@github.com:szymon-bonkowski/Myndhamr.git. Existing Android/Compose shell, KMP domain, generated JVM/Android Protobuf, C++/JNI probe and desktop CLI. All capture functionality is absent. Baseline tools/validate.sh running before edits. Physical phone authorized via USB R3GL60EQ96H; wireless transport is the same phone. Samsung SM-S948B, Android 17/API 37, installed ARCore 1.56.262080393, /data ~195 GB free. Camera/depth capabilities pending runtime APIs.

## Contracts and invariants
Preserve FoundationRecord v1 and generated-toolchain contracts. New additive capture.proto in myndhamr.scan.v1 with java_multiple_files. Raw timestamp records include source clock domain and callback elapsedRealtimeNanos. Camera2 REALTIME can map identically to Android elapsed realtime; UNKNOWN remains unidentified unless a measured mapping exists. ARCore/image association uses exact source timestamp equality; no fabricated nearest pose. Never persist valid pose when tracking is limited/lost. Pose T_world_camera maps right-handed camera (+X right,+Y up,-Z forward) to ARCore right-handed world, meters, column vectors, column-major 4x4. RGB intrinsics use unrotated CPU image pixel coordinates (+X right,+Y down); camera-to-optical conversion diag(1,-1,-1). AR depth is estimated axial Z millimeters; preserve raw/smoothed distinction and native timestamps. Depth alignment uses ARCore CPU-image intrinsics scaled to depth dimensions and documented same optical view, no guessed extrinsics. Raw observations immutable; metadata append streams and atomic per-asset commits. Bounded queues; overload explicit, critical IMU loss fails capture. Lifecycle closes images, camera, AR session and sensor registrations. No fixed photo-count limit.

## Mathematics / algorithm
Clock mapping t_target=t_source+offset_ns uses checked integer arithmetic, never epoch doubles. Exact RGB/ARCore/Camera2 association preferred and residuals measured. Nearest sample association is bounded and one-to-one where used, rejects unordered input; no pose interpolation in real recorder. Tested optional interpolation uses bounded translation lerp/quaternion SLERP. Intrinsics scaling uses pixel coordinates consistently. Transform fixtures identity, translations, rotations, inverse and optical conversion.

## Implementation map
shared/domain: pure timing/transform/state invariants. shared/scan-format: additive capture wire contract. shared/project-store: JVM/Android streaming immutable assets, recovery, checksums, package validator. androidApp capture subpackage: shared Camera2 owner, GL/AR frame owner, IMU, coordinator and persistence worker. desktopApp: inspect/replay/validate CLI. tools/CI: host regression and physical acceptance collection.

## Milestones
### M1 — Baseline and capture contracts
Acceptance: baseline green, schema/domain tests preserve exact timestamps and calibration/pose invariants. Validation: tools/validate.sh; :shared:domain:jvmTest :shared:scan-format:test verifyProtoGeneration.
### M2 — Persistent project/export/inspector
Acceptance: incremental reopen/recovery and corruption rejection; synthetic package validates and replays. Validation: project-store unit tests; desktopApp tests and inspect fixture.
### M3 — Real Android vertical slice
Acceptance: supported shared Camera2/ARCore runtime starts, frame metadata and IMU arrive, manual keyframe persists and exports. Validation: assemble/lint/device instrumentation, adb install/launch, real package inspection.
### M4 — Short physical correctness and lifecycle
Acceptance: runtime capabilities measured; timestamp relationships quantitative; calibration/IMU/depth analyzed; repeated capture and background/foreground succeed. Validation: device tests and pulled packages; structured acceptance JSON.
### M5 — Long session and closure
Acceptance: ~900 seconds real capture without crash/ANR/corruption or unbounded growth; full regression, remote CI, committed/pushed clean tree. Validation: prepare short-tested app/logs, human movement, collect scan and inspector validation, tools/validate.sh, GitHub Actions.

## Test and validation matrix
Domain synthetic timing/transform/state tests; protobuf round trips/unknown fields; streaming persistence recovery/export corruption tests; desktop fixtures; v0.0 host/NDK/JNI regressions; Android lint; real Camera2/ARCore/depth/IMU instrumented and interactive acceptance. Unsupported features reported N/A, never passed by mocks.

## Performance / resource budgets
Bounded capture queue and metadata association cache; disk on worker threads. Sensor callbacks copy raw samples into bounded writer queue. Images closed immediately after bounded copy; RGB assets for manual keyframes, per-frame metadata continuous. Storage shortage and queue overflow explicit failures; diagnostic counters and maxima. Long test checks memory/storage/queue metrics.

## Migration and compatibility
Additive v1 capture schema, never change FoundationRecord. Unknown fields preserved. Assets referenced by stable IDs and relative paths with SHA-256. No existing user scan format to migrate. Interrupted projects recover committed journal records/assets without rewriting raw data; export finalized snapshot only.

## Failure handling
Permissions/AR unavailable/camera error reported actionable. Temporarily unavailable depth explicit. Tracking loss has no valid pose. Background pauses/finishes capture safely rather than silently recording no camera. Interrupted writes recover prefix; fatal persistence errors stop capture and preserve earlier measurements. Export errors surface.

## Risks and mitigations
SharedCamera startup/resume callback ordering: follow upstream sample and test physical repeated lifecycle. Timestamp domain mismatch: preserve originals, exact associations, collect residuals. Depth reuse: preserve depth timestamp and distinguish reused estimate. Device secure install restrictions may require one physical action. Human movement required for tracking/depth and long scan.

## Subagent/model plan
Primary owns Android lifecycle/concurrency, platform integration and final acceptance. GPT-6.1 Sol high implements storage/schema (recovery correctness); GPT-6.1 Sol xhigh implements bounded timing/math tests. Luna high later handles fixed CLI/fixtures. Isolated branches/worktrees and explicit file ownership; no independent architecture changes. No Astra planned.

## Progress
- [ ] M1 baseline/contracts
- [ ] M2 persistence/export/inspector
- [ ] M3 Android vertical slice
- [ ] M4 physical correctness/lifecycle
- [ ] M5 long session/final closure

## Decisions made during implementation
Use ARCore SharedCamera to avoid competing camera owners, as documented by Google. No resampling raw IMU. Existing generated JVM/Android module retained. Metadata journals avoid loading entire scan; SQLite index deferred until query requirements justify it, an explicit narrow departure from reference suggested storage.

## Discoveries
To be updated with actual baseline/device evidence and defects/fixes.

## Final validation
Pending. Record executed counts, quantitative timestamp/IMU/depth statistics, real package location, 15-minute duration/memory and remote CI URL.

## Outcome
Pending; do not mark complete until all applicable automated and physical gates pass.
