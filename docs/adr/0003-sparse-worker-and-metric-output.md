# ADR-0003: Sparse workers and auditable metric output

- Status: Accepted
- Date: 2026-10-01
- Supersedes: none

## Context
v1 capture contracts already preserve AR T_world_camera, unrotated CPU images and independent original clock labels. v0.2 needs desktop SfM without promoting COLMAP storage into the capture schema or treating drifting poses as truth.

## Decision
Kotlin validates/normalizes canonical v1 directories and ZIPs and orchestrates separate processes. Native COLMAP3.13.0 through its pinned Python binding owns sparse reconstruction. A desktop C++20 core owns deterministic robust Sim(3), using unmodified Eigen3.4.0 Core/SVD under EIGEN_MPL2_ONLY. Python adapter/export code handles data conversion; product capture and orchestration remain Kotlin.

Persisted derived JSON uses schemaVersion1 and stays outside raw projects. Every new run retains manifest/image/depth hashes, original frame IDs/timestamps/calibration, explicit configuration, backend options/version, logs, unregistered frames and independent raw/aligned poses. Worker failures and disconnected models remain explicit; components are never silently merged.

Column vectors and T_A_B notation remain unchanged. AR camera axes are right/up/back; optical axes right/down/forward. D=diag(1,-1,-1) converts camera axes and is a proper rotation. COLMAP T_optical_sfm is inverted explicitly, C_sfm=-R^T t. Metric points use sR p+t; camera orientation uses R only. Source and target camera-center consensus must be noncollinear with measurable baseline. Reflection, nonfinite, insufficient-consensus and rank-deficient estimates fail.

Raw AR poses constrain pair proposals and robust alignment. They are not hard-locked in visual BA and are not assigned invented uncertainty covariance. Position and rotation residuals remain separately inspectable. The default5cm position threshold,60% consensus,5cm trajectory radius and20degree median orientation gate are explicit consistency policies, not sensor-accuracy claims.

Depth is a separate sanity check. Use original measured pixel H and optical axial-Z; preserve AR raw versus smoothed provenance, zero-invalid values and confidence. For ARCORE_FRAME/ARCORE_DEPTH from current-frame ARCore APIs, an exact original integer timestamp equality may establish a *derived current-depth association*, following Google's documented current-raw-depth test. Paired ARCORE_DEPTH/ARCORE_DEPTH_CONFIDENCE accepts corresponding paired API evidence with exact timestamp equality. This supplements ADR-0002's conservative rule for this specific provider association; it does not assert global clock equivalence, apply offsets, rewrite labels, interpolate, or compare unrelated clocks. Stale/unknown associations remain skipped with reasons.

2026-10-02 correction after real capture: the equality-only rule above confuses a new raw estimate with a map's current camera pose. ARCore documents that raw maps between estimates are reprojected to the current pose while preserving the estimate timestamp; paired current confidence can have a different timestamp. Normalization now adds explicit raw `frameAssociation` and `confidenceAssociation` only for the recognized v0.1 recorder pattern: original current-frame CPU-image association and clock labels, raw/depth source, saved asset, exact measured mapping/alignment and scaled-texture calibration, and persisted reprojection/paired-API markers. The exporter accepts this documented same-Frame raw reprojection and confidence pairing without altering any original time. Numeric differences remain unverified diagnostics; they are neither calibrated offsets nor measurement age. Generic/unknown paths retain exact timestamp checks; smoothed maps lack the raw reprojection guarantee and remain conservative. This is a correction to derived validation, with no raw schema or clock-contract migration. AR-estimated depth agreement is a consistency check correlated with tracking, not independent physical ground truth.

## Rationale
Separate processes contain third-party crashes and timeouts. Native mature SVD avoids custom linear-algebra foundations. Visual poses remain independent measurements that can expose AR world changes. Fixed eligibility and recorded rejected priors prevent registration-denominator manipulation.

## Alternatives considered
Linking COLMAP into JVM/JNI enlarges the crash and dependency surface. Perfect-pose triangulation would hide AR errors. Soft BA pose constraints require covariance calibration absent from v1 and remain a later measured improvement. A GUI framework is unnecessary for the generated HTML trajectory inspector.

## Consequences
The pinned pycolmap verify_matches API matches imported pairs but uses compiled CPU matching defaults; its thread count is hardware-derived and recorded, while extraction/mapping use configured threads. Heavy engines need a separately installed pinned Python runtime. Source evidence remains immutable; derived outputs can be discarded and rerun.

## Compatibility and migration
No v1 schema change or raw migration. New derived formats are explicitly versioned. Existing inspect/replay/validate remain compatible.

## Validation / evidence
Native exact/noisy/outlier/reflection/source-target-degeneracy tests; explicit pose/axis/depth/clock tests; real CPU COLMAP smoke and analytic ten-camera scene; process timeout/interruption tests. See the v0.2 ExecPlan for measured acceptance and real-capture limitations.

Authoritative contracts: [COLMAP output conventions](https://colmap.github.io/format.html), [pinned matching implementation](https://github.com/colmap/colmap/blob/3.13.0/src/pycolmap/pipeline/match_features.cc), [ARCore depth association](https://developers.google.com/ar/develop/java/depth/raw-depth), [ARCore raw reprojection and paired confidence](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireRawDepthImage16Bits()), [Eigen license](https://gitlab.com/libeigen/eigen/-/blob/3.4.0/COPYING.MPL2).

## Revisit when
Measured pose covariance or source-world revision metadata enables trustworthy soft pose-prior BA; native binding exposes explicit imported-pair thread options; downstream v0.3 requires a durable shared scene contract.
