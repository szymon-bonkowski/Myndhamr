# ARCHITECTURE.md — Myndhamr

**Status:** reference architecture, not a continuously synchronized dump of the working tree  
**Reference date:** 2026-09-30  
**Audience:** human owner, Codex planners/reviewers, implementers, and future contributors

## 1. Purpose

Myndhamr is a measurement-first 3D capture and reconstruction system for objects, rooms, and larger scenes. It combines modern phone sensors with established reconstruction engines and custom product logic.

The architectural goal is not to build a new photogrammetry engine from first principles. The goal is to make the entire system smarter about:

- what is captured,
- when a frame is worth keeping,
- how camera/depth/IMU data is synchronized,
- how sensor priors constrain reconstruction,
- how confidence propagates,
- how difficult materials are represented,
- where computation should run,
- how raw evidence and reproducibility are preserved.

This file explains the intended system. For a concrete implementation task, current specs, code, tests, and active plans take precedence as described in `AGENTS.md`.

---

## 2. Product modes

### 2.1 Pro / Raw

Pro mode prioritizes fidelity, auditability, and reproducibility.

Allowed operations include:

- lens calibration and distortion correction,
- deterministic frame rejection based on documented quality gates,
- feature matching and bundle adjustment,
- sensor fusion from measured RGB/depth/pose/IMU,
- robust outlier rejection,
- geometry cleanup that is mathematically traceable,
- physically justified materials when the observation supports them,
- confidence reporting.

Pro mode must not silently:

- generate unseen geometry,
- invent texture for unobserved surfaces,
- replace uncertain measured geometry with a plausible neural guess,
- beautify or regularize an object in a way that changes its measured shape.

Every meaningful derived artifact should be traceable to source captures, pipeline version, configuration, and stage outputs.

### 2.2 Smart / User-Friendly

Smart mode aims for the best practical user result while keeping provenance available. It may:

- classify mirrors, windows, TVs, glass, glossy metal, foliage, dynamic objects,
- route a region to a more suitable representation,
- suppress known reflection-induced geometry artifacts,
- choose physically plausible materials,
- fill small topological holes under bounded rules,
- choose mesh versus splat versus special reflective/refractive representation,
- guide the user during capture and prevent avoidable bad scans.

Smart mode must keep the raw scan unchanged and preserve confidence for decisions.

### 2.3 Experimental

Experimental mode contains features that are scientifically interesting or visually strong but not yet predictable enough for Pro guarantees. Examples:

- reflected virtual cameras from mirrors,
- view-dependent mirror appearance,
- advanced refraction-aware reconstruction,
- neural material decomposition,
- hybrid per-region mesh/3DGS/neural appearance,
- generative completion where explicitly enabled.

Experimental output must be clearly distinguishable from measured Pro output.

---

## 3. Scan classes

### 3.1 Object scan

Primary goal: high-detail geometry and texture for a bounded object.

```text
RGB keyframes
+ intrinsics
+ AR pose prior
+ optional metric depth
+ quality/confidence
        |
        v
pose-aware SfM / bundle adjustment
        |
        v
MVS / dense depth
        |
        v
dense surface / mesh
        |
        v
cleanup + normals + UV
        |
        v
high-resolution texture baking
        |
        v
GLB / OBJ / PLY
```

Depth is primarily a prior, scale source, validation signal, and possible dense aid. RGB photogrammetry remains responsible for fine detail.

### 3.2 Room scan

Primary goal: metric, stable room geometry with immediate capture feedback.

```text
RGB + metric depth + pose + confidence
        |
        v
live weighted TSDF / voxel fusion
        |
        +--> coarse mesh for guidance
        |
        v
final TSDF / geometry cleanup
        |
        +--> optional photogrammetry refinement
        |
        v
semantic/material pass + texture
```

Hardware depth such as iPhone LiDAR should receive a sensor-specific uncertainty model rather than being treated as identical to estimated depth.

### 3.3 Scene scan

For larger places such as gardens, facades, halls, or outdoor structures. It may mix:

- sparse/dense photogrammetry,
- depth-assisted local fusion,
- tiled processing,
- scene graph partitioning,
- mesh for geometry and Gaussian Splatting for appearance.

### 3.4 Appearance scan

Primary goal: photorealistic rendering from observed views rather than a perfect editable mesh.

```text
registered cameras + RGB + optional depth/mesh priors
        |
        v
3D Gaussian initialization
        |
        v
training / optimization
        |
        v
splat representation + viewer/export
```

---

## 4. System topology

```text
+--------------------------- MOBILE ----------------------------+
| UI / ScanController                                            |
|       |                                                        |
|       +--> PlatformCaptureAdapter                              |
|       |      +-- RGB / camera metadata                         |
|       |      +-- pose / tracking                               |
|       |      +-- depth / confidence                            |
|       |      +-- IMU                                           |
|       |                                                        |
|       +--> SensorSynchronizer                                  |
|       +--> QualityAnalyzer                                     |
|       +--> KeyframeSelector                                    |
|       +--> CoverageEngine                                      |
|       +--> PreviewReconstruction                               |
|       +--> ScanProjectStore                                    |
|       +--> TransferClient                                      |
+------------------------------+---------------------------------+
                               |
                   LAN / USB / exported package
                               |
                               v
+--------------------------- DESKTOP ---------------------------+
| CLI / optional future GUI                                     |
|       |                                                        |
|       +--> ProjectManager / validation                         |
|       +--> ReconstructionOrchestrator                          |
|                 |                                              |
|                 +--> COLMAP worker                             |
|                 +--> Open3D / TSDF worker                      |
|                 +--> mesh / texture worker                     |
|                 +--> gsplat worker                             |
|                 +--> export / packaging                        |
|                                                                |
|       +--> ResultViewer / diagnostics                          |
+----------------------------------------------------------------+
```

Heavy engines should be isolated behind explicit adapters and, on desktop where practical, separate processes. A third-party crash should not necessarily destroy the entire project session.

---

## 5. Logical repository boundaries

The exact working tree may differ, but dependencies should flow in this direction:

```text
platform capture / UI
        |
        v
shared domain + scan contracts
        |
        v
native/public reconstruction interfaces
        |
        v
backend adapters / workers
        |
        v
third-party reconstruction libraries
```

Recommended areas:

- `apps/mobile-android`: Android application shell and Compose UI.
- `apps/mobile-ios`: iOS application shell and native integration.
- `apps/desktop`: CLI and optional future UI; not the heavy reconstruction implementation itself.
- `shared/domain`: KMP domain types, scan state, user-facing state machines.
- `shared/scan-format`: persisted project contracts and versioning.
- `shared/networking`: transport-independent commands/messages and client abstractions.
- `shared/project-store`: project metadata and local project operations.
- `shared/ui-shared`: reusable Compose UI where platform parity is beneficial.
- `platform/android-capture`: Camera2 + ARCore implementation.
- `platform/ios-capture`: AVFoundation + ARKit + LiDAR implementation.
- `native/core`: C++20 stable interfaces and common types.
- `native/geometry`: transforms, camera math, geometry algorithms.
- `native/tsdf`: depth fusion and mesh extraction interfaces.
- `native/texture`: visibility, image selection, baking, color adjustment.
- `native/bindings`: JNI and Swift/Kotlin Native interop boundaries.
- `desktop-workers`: process workers and third-party engine adapters.

Avoid circular dependencies. UI must not own reconstruction math. Third-party engine types must not leak deeply into shared domain types.

---

## 6. Core data model

### 6.1 `ScanProject`

A scan project is a versioned container of raw observations, derived artifacts, processing metadata, and results.

Conceptually:

```text
ScanProject
├── project_id
├── format_version
├── created_at
├── scan_type
├── capture_device
├── calibration_set
├── frames[]
├── sensor_streams[]
├── masks[]
├── derived/
├── pipeline_runs[]
├── audit_log[]
└── exports[]
```

Large assets remain file-backed. SQLite/SQLDelight may index metadata; do not store multi-megabyte images/depth blobs as ordinary database rows unless a later benchmark proves it beneficial.

### 6.2 `ScanFrame`

A keyframe is more than an image:

```text
ScanFrame
├── frame_id
├── timestamp
├── clock_domain
├── rgb_asset
├── image_size
├── intrinsics
├── distortion_model
├── pose
├── pose_tracking_state
├── exposure
├── exposure_time
├── iso
├── focal_length_metadata
├── white_balance metadata
├── depth_ref?             -> DepthFrame
├── imu_window_ref?
├── quality_metrics
├── capture_decision
└── provenance
```

### 6.3 `DepthFrame`

```text
DepthFrame
├── timestamp
├── clock_domain
├── depth_asset
├── confidence_asset?
├── width / height
├── intrinsics
├── T_rgb_depth
├── source
├── unit_scale
├── valid_range
├── latency metadata
└── noise_model metadata
```

Suggested depth source enumeration:

```text
NONE
MONOCULAR_ESTIMATED
MULTIVIEW_ESTIMATED
AR_DEPTH_ESTIMATED
HARDWARE_TOF
HARDWARE_LIDAR
EXTERNAL_DEPTH_SENSOR
```

Do not make downstream code infer sensor trust from the device model name.

### 6.4 Provenance

Every derived artifact should be able to record:

- source frame IDs or source artifact IDs,
- algorithm/backend name and version,
- configuration digest,
- model version for ML-derived masks,
- creation time,
- deterministic/non-deterministic marker,
- confidence/quality metrics.

This becomes important for Pro reproducibility and debugging.

---

## 7. Scan package format

Use a versioned internal format, conceptually `.scan3d`. It may be a directory during development and a packaged container for transfer/export.

Example:

```text
scan.scan3d/
├── manifest.pb
├── frames/
│   ├── rgb/
│   ├── depth/
│   ├── confidence/
│   └── masks/
├── sensors/
│   └── imu.pb.zst
├── calibration/
├── derived/
│   ├── sparse/
│   ├── dense/
│   ├── mesh/
│   ├── texture/
│   └── splat/
├── runs/
└── checksums/
```

Use Protobuf for durable structured binary contracts that cross Kotlin/C++/workers. Use lightweight Kotlin serialization for UI-only ephemeral configuration when it does not become a persisted compatibility contract.

Required format properties:

- explicit `format_version`,
- forward-compatible unknown-field handling where possible,
- checksums for transferred assets,
- no dependence on file ordering for semantic identity,
- stable frame IDs,
- explicit units and coordinate semantics,
- migration tests for breaking persisted changes.

---

## 8. Coordinate systems and transform notation

This area must be explicit because silent convention bugs can produce plausible but wrong reconstructions.

### 8.1 Canonical internal convention

Choose one canonical internal convention and keep adapters responsible for conversion. Recommended reference convention:

- right-handed 3D world,
- meters for linear units,
- column vectors,
- homogeneous transforms as 4x4 matrices,
- transform notation `T_A_B`: maps coordinates expressed in frame B into frame A.

Therefore:

\[
\mathbf{p}_A = T_{A\leftarrow B}\,\mathbf{p}_B
\]

or in code naming:

```text
T_world_camera * p_camera = p_world
```

A transform consists of rotation `R` and translation `t`:

\[
T = \begin{bmatrix} R & t \\ 0 & 1 \end{bmatrix}
\]

The inverse is:

\[
T^{-1} = \begin{bmatrix} R^T & -R^T t \\ 0 & 1 \end{bmatrix}
\]

Never rely on ambiguous variable names such as `cameraPose` in native math code. Encode direction in the type/name.

### 8.2 Adapter conversions

ARCore, ARKit, COLMAP, OpenGL/Vulkan/Metal rendering, OpenCV, and glTF may differ in axes, camera forward direction, UV origin, matrix layout, or pose semantics. Convert at module boundaries and test each conversion with known fixtures.

Required tests include:

- identity,
- +X/+Y/+Z translation,
- 90-degree rotations,
- camera looking at a known target,
- projection/unprojection round trip,
- AR pose -> canonical -> AR pose round trip,
- COLMAP pose -> canonical -> COLMAP pose round trip.

---

## 9. Camera model

### 9.1 Pinhole projection

For a 3D point in camera coordinates:

\[
\mathbf{p}_c = (X,Y,Z)^T
\]

normalized coordinates are:

\[
x = X/Z, \qquad y = Y/Z
\]

and pixel coordinates for a basic pinhole model are:

\[
u = f_x x + c_x, \qquad v = f_y y + c_y
\]

with intrinsic matrix:

\[
K =
\begin{bmatrix}
f_x & 0 & c_x \\
0 & f_y & c_y \\
0 & 0 & 1
\end{bmatrix}
\]

Real devices require a distortion model and potentially per-camera/per-resolution calibration. Store the actual calibration metadata rather than rebuilding it from marketing focal length.

### 9.2 Unprojection with depth

Given pixel `(u,v)` and metric depth `z` under the defined depth convention:

\[
X = (u-c_x)z/f_x
\]

\[
Y = (v-c_y)z/f_y
\]

\[
Z = z
\]

Then convert from depth-camera frame to RGB/world through calibrated transforms.

Depth convention must specify whether the value is axial Z depth or Euclidean range along a ray. Do not mix them.

---

## 10. Time and sensor synchronization

### 10.1 Why it matters

At phone motion speed, a few milliseconds of offset can create a meaningful pose/depth/RGB mismatch. Synchronization is part of reconstruction correctness.

### 10.2 Design

The capture backend should preserve:

- original sensor timestamps,
- clock domain/source,
- known platform timestamp conversion,
- estimated offsets and uncertainty,
- dropped/missing sample state.

The synchronizer constructs an observation bundle around an RGB exposure time. It may interpolate pose/IMU only when the interpolation model is explicit and bounded.

### 10.3 Pose interpolation

For translations, linear interpolation is acceptable over short intervals:

\[
t(\alpha)=(1-\alpha)t_0+\alpha t_1
\]

For rotations, use quaternion SLERP, not element-wise matrix interpolation:

\[
q(\alpha)=\operatorname{SLERP}(q_0,q_1,\alpha)
\]

Record the interpolation interval and reject data when the gap exceeds a calibrated threshold.

### 10.4 Rolling shutter

A single camera pose may be insufficient during fast motion. Early versions should prefer rejecting high-motion frames. A future Pro path may model row-time pose for rolling-shutter-aware bundle adjustment, but it is not a default MVP requirement.

---

## 11. Quality analysis

Run cheap quality analysis before accepting a keyframe.

Candidate signals:

- blur/sharpness,
- exposure clipping,
- motion/angular velocity,
- AR tracking state,
- depth valid-pixel ratio,
- depth confidence,
- overlap with recent accepted frames,
- viewpoint novelty,
- baseline relative to target/object,
- semantic occlusion/dynamic content,
- storage/thermal state.

Store raw metrics and the decision reason. Do not store only `accepted=true`.

A weighted score may be used for ranking, but hard gates should remain explicit for known-invalid states such as lost tracking or severe motion blur.

---

## 12. Keyframe selection

The objective is not to maximize frame count. It is to maximize geometric and photometric information while controlling compute.

### 12.1 Candidate policy

A frame becomes a reconstruction keyframe when it passes quality gates and adds sufficient information relative to already accepted frames.

Example factors:

- translation baseline,
- angular baseline,
- estimated parallax,
- coverage gain,
- feature/texture richness,
- blur/exposure quality,
- surface visibility,
- depth quality.

The selector may keep separate classes:

- geometry keyframes,
- texture-only high-resolution frames,
- diagnostic frames,
- discarded frames whose metadata remains useful.

This allows 500 captured images without forcing dense MVS to process all 500 at full resolution.

### 12.2 Pair graph

Do not perform naive all-pairs matching for large ordered captures.

Use candidate edges based on:

- temporal neighborhood,
- pose-space proximity,
- view-direction similarity,
- expected overlap,
- loop-closure candidates.

Then let feature matching validate the proposed relationships.

---

## 13. Coverage engine

### 13.1 Object coverage

Represent expected viewpoints around an estimated object center as bins on a sphere or another discretized visibility surface. Update bins from accepted camera poses and observed object masks.

Coverage is not merely camera position. A bin should account for:

- view direction,
- actual visible object region,
- distance,
- incidence angle,
- occlusion,
- quality/confidence.

The UI reports missing areas instead of only a percent.

### 13.2 Room coverage

For rooms, maintain voxel/surface visibility and depth support. Coverage should distinguish:

- observed free space,
- observed surface,
- unobserved volume,
- low-confidence observed surface,
- areas visible but insufficiently viewed for texture/photogrammetry.

---

## 14. Sparse reconstruction / SfM

COLMAP is the default desktop sparse backend.

### 14.1 Feature extraction and matching

Use mature feature extraction/matching first. The product advantage comes from better pair selection, priors, capture quality, and validation, not from rewriting SIFT-like foundations in v1.

### 14.2 Pose priors

ARCore/ARKit poses are priors, not unquestionable ground truth. They may drift.

Use them to:

- initialize camera relationships,
- reduce matching search space,
- establish rough trajectory and scale,
- reject absurd hypotheses,
- help recover weakly textured sequences.

Do not hard-lock inaccurate AR poses unless a profile explicitly requests it.

### 14.3 Bundle adjustment

For observed 3D point `X_j`, camera `i`, projection function `pi`, and measured pixel `x_ij`, classical reprojection error is:

\[
r_{ij}=x_{ij}-\pi(K_i,R_i,t_i,X_j)
\]

Optimize robustly:

\[
\min_{K,R,t,X}\sum_{i,j}\rho(\|r_{ij}\|^2)
\]

where `rho` is a robust loss such as Huber/Cauchy depending on the stage.

Sensor priors can add soft residuals, for example pose translation/rotation residuals weighted by confidence. Those weights must be calibrated and testable rather than arbitrary permanent constants.

---

## 15. Metric scale alignment

Pure monocular SfM has a scale ambiguity. Metric depth, known calibration geometry, AR trajectory, or known dimensions can provide scale.

To align two corresponding 3D point sets, estimate a similarity transform:

\[
p' = sRp+t
\]

where `s` is scale, `R` rotation, and `t` translation. Use a robust Sim(3) estimation and reject outliers.

Report scale confidence and residual statistics. Never claim millimeter accuracy simply because output units are meters.

---

## 16. Dense reconstruction

### 16.1 Desktop object path

Default flow:

```text
registered cameras
   -> selected geometry keyframes
   -> stereo/depth estimation
   -> depth filtering
   -> fusion / dense cloud
   -> surface reconstruction
   -> cleanup/refinement
```

COLMAP dense paths may be used where they are strong. Open3D and custom components can own downstream geometry processing.

### 16.2 Working resolutions

Preserve high-resolution originals but do not force every stage to run at sensor resolution.

Example policy:

- 3–6 MP for sparse/geometry preprocessing,
- 6–12 MP for selected dense refinement on high-end profiles,
- original/high-resolution frames as texture sources.

Actual values must be benchmarked by device and scene type.

---

## 17. TSDF depth fusion

TSDF is the default reference for fast room fusion and low-resolution live preview.

A voxel stores a truncated signed distance `D` and accumulated weight `W`. For a new observation with signed distance estimate `d` and weight `w`:

\[
D_{new}=\frac{WD+wd}{W+w}
\]

\[
W_{new}=W+w
\]

Clamp signed distance to truncation distance `mu` and cap or normalize accumulated weights as needed.

### 17.1 Sensor-aware weighting

A conceptual per-observation weight is:

\[
w = w_c \cdot w_d \cdot w_\theta \cdot w_m
\]

where:

- `w_c` derives from sensor confidence,
- `w_d` models distance-dependent noise,
- `w_theta` penalizes grazing incidence,
- `w_m` represents motion/tracking quality.

Each depth source gets a calibrated noise model. Hardware LiDAR should not share the same trust curve as monocular/AR-estimated depth merely because both arrive as a float depth map.

### 17.2 Extraction

Extract a triangle mesh from the zero crossing using a stable method such as marching cubes or the chosen Open3D path. Keep the coarse live mesh separate from the final high-quality mesh so UI constraints do not dictate final geometry.

---

## 18. iPhone LiDAR architecture

LiDAR is a first-class iOS capture capability, not a separate reconstruction product.

### 18.1 Inputs

On supported devices capture:

- RGB/photo stream through AVFoundation as required,
- ARKit camera pose/intrinsics,
- `sceneDepth`,
- depth confidence map,
- optional `smoothedSceneDepth` for preview,
- IMU/tracking state,
- calibration/timestamps.

### 18.2 Use by scan type

For room scans, LiDAR depth can be a primary geometric observation for TSDF fusion.

For object scans, use LiDAR as:

- metric-scale evidence,
- coarse geometry prior,
- outlier/consistency validator,
- low-texture aid,
- coverage/live-preview source.

Do not assume phone LiDAR has enough spatial resolution to replace RGB photogrammetry for fine surface detail.

### 18.3 ARKit mesh

ARKit scene mesh may be used for:

- live occlusion,
- guidance,
- coarse sanity checks,
- initial region/plane hypotheses.

Do not make it the only source of final Pro geometry unless a future benchmark explicitly justifies that profile.

---

## 19. Mesh processing

After dense reconstruction:

- remove isolated components using evidence-aware thresholds,
- repair only bounded holes under profile rules,
- orient normals consistently,
- simplify with error limits tied to scene scale,
- preserve high-detail source before decimation,
- compute LODs where useful,
- preserve semantic regions/material boundaries where possible.

Do not use aggressive cleanup to hide reconstruction failure. Mark low-confidence regions.

---

## 20. UV and texture pipeline

### 20.1 UV

Use xatlas or equivalent permissive tooling for UV charts and packing. The UV stage must be independent from image selection and baking.

### 20.2 Visibility

For each candidate surface sample/triangle and source camera:

1. transform point to camera coordinates,
2. require positive camera-space depth,
3. project with calibrated intrinsics/distortion policy,
4. require the projected coordinate to lie in the valid image region,
5. compare projected depth against geometry/depth buffer to reject occlusion,
6. apply masks for mirror/glass/dynamic/invalid regions,
7. score the remaining view.

A conceptual view score can combine:

\[
S = w_a S_{angle} + w_r S_{resolution} + w_q S_{quality} + w_e S_{exposure} + w_c S_{confidence}
\]

Do not bake reflected mirror pixels into ordinary diffuse wall geometry.

### 20.3 Photometric normalization

Overlapping source images may differ in exposure, white balance, shading, and lens response. Estimate pairwise/region corrections only from reliable overlapping non-specular regions.

Maintain bounded corrections to avoid turning lighting differences into fake albedo.

### 20.4 Seam blending

Choose source views to minimize seams and apply multi-band or equivalent blending where needed. Evaluate:

- texture coverage,
- seam energy,
- blur/sharpness,
- color discontinuity,
- reprojection/visibility confidence.

High-resolution originals may be used here even if geometry used downscaled images.

---

## 21. Smart Surface system

The Smart layer must not rely on a single semantic classifier.

### 21.1 Evidence channels

Potential inputs include:

- semantic segmentation probabilities,
- depth confidence/invalidity pattern,
- planar geometry evidence,
- multi-view inconsistency,
- optical-flow/parallax inconsistency,
- specular highlights across views,
- color/intensity cues,
- object/category context,
- device sensor type.

### 21.2 Surface router

The router combines evidence and assigns a region to a representation with confidence:

```text
ordinary opaque surface -> triangle mesh + PBR
mirror                  -> reflective plane + mirror appearance policy
window/thin glass       -> planar/thin glass material + masked geometry policy
TV/dark glossy screen   -> glossy dielectric plane/material
highly view-dependent   -> view-dependent or splat representation in Smart/Experimental
uncertain region        -> preserve/flag; do not silently fabricate in Pro
```

Keep classification probability separate from geometric confidence and material confidence.

---

## 22. Mirror system

### 22.1 Problem

Classical multi-view reconstruction assumes an observed image feature corresponds to a stable 3D surface point. A planar mirror shows a view-dependent reflected ray, so naive reconstruction can create a phantom room behind the mirror.

### 22.2 Detection

Fuse evidence from:

- semantic mirror mask,
- planar boundary/geometry,
- AR/depth invalidity or inconsistent confidence,
- multi-view feature inconsistency,
- reflective appearance.

Fit candidate planes robustly, e.g. with RANSAC on frame/edge/depth geometry where available.

Plane equation:

\[
n^T x + d = 0
\]

with unit normal `n`.

### 22.3 Reflecting a point/camera

For point `p`, reflected point across the plane is:

\[
p' = p - 2(n^T p + d)n
\]

A virtual camera can be created by reflecting its position and orientation consistently across the mirror plane. Do not reflect only translation; the camera basis must also be transformed.

### 22.4 Rendering policies

Native viewer priority:

1. use true planar reflection from known reconstructed scene when the reflected content exists,
2. use view-dependent captured appearance if scene geometry is incomplete and Smart/Experimental allows it,
3. portable GLB fallback uses best available approximate material/texture because standard glTF cannot encode the complete native mirror behavior.

### 22.5 Reconstruction masks

Mirror interior pixels are excluded from ordinary surface geometry and diffuse texture estimation unless an experimental mirror-as-camera algorithm explicitly consumes them as reflected observations.

---

## 23. Glass and refraction

### 23.1 v1 practical glass

For common windows/glass doors, first solve representation, not full inverse optics:

- detect/mask glass region,
- fit/support a plausible plane or thin surface,
- prevent reflections/transmitted content from becoming phantom surface geometry,
- assign PBR transmission, roughness, IOR, and volume parameters where export/runtime supports them,
- keep geometry behind glass as a separate reconstruction problem.

Typical dielectric IOR can be initialized around common glass values but must never be presented as measured material science without evidence.

### 23.2 Refraction model

For advanced work use Snell's law:

\[
n_1\sin\theta_1=n_2\sin\theta_2
\]

The observed ray no longer follows the standard pinhole path through the transparent object. Full refractive reconstruction may therefore require jointly estimating geometry, interface normals/thickness, and refractive index.

That is a research-grade Experimental workstream and should not block v1.

---

## 24. Gaussian Splatting

Desktop appearance path:

```text
registered COLMAP cameras
+ sparse points
+ RGB images
+ optional masks/depth priors
        |
        v
splat initialization
        |
        v
gsplat/PyTorch optimization
        |
        v
quality profiles / pruning
        |
        v
viewer + export
```

The mesh renderer and Gaussian renderer should live behind separate rendering interfaces. Do not force Gaussian data into a triangle-only abstraction.

A future hybrid scene may attach a representation type per region/node.

---

## 25. ML architecture

### 25.1 Training

Use PyTorch for training and experimentation. Dataset/versioning must preserve:

- source scan identity,
- annotation version,
- train/validation/test split identity,
- material/surface taxonomy,
- device/sensor diversity.

Avoid leakage where near-identical frames from the same scan appear in both train and test.

### 25.2 Mobile inference

Prefer compact discriminative models for runtime tasks:

- Android: LiteRT/TFLite or currently selected optimized runtime,
- iOS: Core ML,
- shared outputs via stable semantic mask/probability contracts.

Do not run a huge general-purpose VLM on every captured frame unless a later benchmark proves it necessary.

### 25.3 Confidence

Model probability is one evidence channel, not truth. Calibrate and fuse it with geometry/depth/multi-view evidence in Smart logic.

---

## 26. On-device reconstruction

The mobile pipeline is not a direct desktop port.

Optimize for:

- limited RAM,
- memory bandwidth,
- sustained rather than peak performance,
- thermal throttling,
- battery,
- mobile GPU APIs,
- app lifecycle interruptions.

### 26.1 Quality tiers

A device benchmark/profile may select:

- number of geometry keyframes,
- working image resolution,
- voxel size/truncation,
- concurrent jobs,
- texture resolution,
- local versus desktop recommendation.

Keep the full capture package even when on-device processing uses a reduced working set.

### 26.2 Scheduler

The scheduler observes:

- free/available memory,
- GPU/CPU load where accessible,
- thermal status,
- battery/charging state where appropriate,
- stage parallelism,
- estimated remaining work.

When sustained throughput collapses, reduce concurrency/resolution or pause/cool instead of racing into OOM/thermal failure.

### 26.3 GPU

Android uses Vulkan compute where justified; iOS uses Metal. Implement common math semantics, not identical kernels at all costs.

GPU acceleration must be introduced only after a CPU/reference implementation or numerical fixture exists, so correctness can be compared.

---

## 27. Desktop reconstruction orchestrator

Represent heavy reconstruction as a resumable DAG rather than one opaque command.

Example:

```text
validate
  -> preprocess
  -> sparse
  -> scale_align
  -> dense
  -> mesh
  -> cleanup
  -> uv
  -> visibility
  -> texture
  -> optimize
  -> export
```

Branches can produce TSDF or Gaussian outputs from shared inputs.

Each node has:

- versioned inputs,
- config digest,
- declared outputs,
- resource hints,
- status,
- retryability,
- logs/metrics,
- cache key.

A successful upstream node should not rerun after a downstream failure unless its inputs/config changed.

Use process isolation for third-party workers where practical.

---

## 28. Error model

Do not collapse failures into `Something went wrong`.

Stages return structured outcomes such as:

```text
SUCCESS
RETRYABLE_FAILURE
NON_RETRYABLE_FAILURE
INSUFFICIENT_DATA
UNSUPPORTED_DEVICE
RESOURCE_LIMIT
CORRUPT_INPUT
```

Include machine-readable code plus human-actionable context. Example:

```text
stage: dense_mvs
status: INSUFFICIENT_DATA
reason: stereo_baseline_too_low
metrics:
  usable_pairs_ratio: 0.19
suggestion: capture more side-angle views
```

---

## 29. Phone <-> desktop transport

### 29.1 Discovery and pairing

- mDNS/Bonjour for LAN discovery.
- QR-assisted first pairing.
- authenticated session establishment.
- TLS for transport.

Do not design custom cryptography.

### 29.2 Resumable asset transfer

Split large assets into chunks, e.g. a tuned 4–16 MB range rather than assuming one universal size.

For each asset preserve:

- content/asset ID,
- total length,
- chunk index/range,
- checksum,
- transfer state.

Desktop persists received-chunk state. After reconnect, client requests only missing chunks.

Perform integrity verification before marking an asset complete.

### 29.3 Protocol

Use versioned Protobuf messages. Transport can use Ktor/HTTP2 or another selected standard stack. The application protocol should be independent enough to support LAN, USB-tunneled, or future remote workers without rewriting project semantics.

---

## 30. Storage

Use SQLDelight/SQLite for project/index metadata and file-backed assets for large capture/reconstruction blobs.

Suggested compression:

- RGB: JPEG/HEIF or RAW when explicitly captured,
- depth/confidence/masks: lossless or quantized formats with documented precision; Zstd for streams where useful,
- metadata/protocol: Protobuf,
- final GPU textures: KTX2/Basis Universal where appropriate.

Never introduce lossy depth compression without an error budget and regression test.

---

## 31. Rendering

### 31.1 Mesh/PBR

Google Filament is the primary reference renderer for mesh/PBR output because it supports mobile/desktop, glTF, custom materials, and modern PBR.

### 31.2 Special surfaces

Planar mirrors and advanced glass may require custom render passes/materials beyond standard portable glTF.

### 31.3 Gaussian renderer

Keep a separate `IGaussianRenderer` or equivalent backend. A native mobile splat renderer is a later performance project, not a prerequisite for desktop gsplat output.

---

## 32. Export

Primary mesh export: glTF 2.0 / GLB.

Use Khronos material extensions where compatible, such as transmission/volume for glass-like materials. Preserve portable fallbacks when a native effect cannot be represented exactly.

Additional exports:

- PLY for point clouds/debug/interchange,
- OBJ for compatibility only,
- internal/project assets for complete fidelity,
- standardized Gaussian extension/format as ecosystem support stabilizes.

The project format is not the same thing as the final share/export format.

---

## 33. Confidence architecture

Confidence should be multi-dimensional rather than a single magic score.

Examples:

- capture quality confidence,
- tracking/pose confidence,
- depth confidence,
- geometry confidence,
- texture confidence,
- material classification confidence,
- semantic surface confidence.

A region can have strong geometry but uncertain material, or strong semantic mirror detection but weak precise plane extent. Preserve those distinctions.

Confidence feeds:

- capture guidance,
- reconstruction weighting,
- Smart surface routing,
- cleanup policy,
- user diagnostics,
- Pro auditability.

---

## 34. Dynamic objects

Moving people, pets, screens, vegetation, and other dynamic regions can break multi-view assumptions.

Strategy:

- detect temporal/multi-view inconsistency,
- optionally use semantic masks,
- exclude high-confidence dynamic pixels from geometry/texture where appropriate,
- preserve raw frames,
- report large dynamic occlusion as capture quality risk.

Do not silently delete a large scene region based on weak classifier output.

---

## 35. Performance budgets

Every heavy stage should expose measurable budgets:

- wall-clock runtime,
- peak RAM,
- GPU memory where measurable,
- temporary disk use,
- power/thermal behavior on mobile,
- output quality metrics.

Benchmark by representative scan class and device tier. Avoid optimizing against one phone or one perfect object.

For 500-image scans, select expensive geometry keyframes instead of assuming every image needs full dense processing. Preserve remaining frames for texture, validation, or alternative reconstructions.

---

## 36. Threading and process model

### Mobile

- UI thread owns UI only.
- capture callbacks must remain responsive and avoid blocking disk/network/heavy compute.
- bounded queues provide backpressure.
- derived work runs in structured coroutine/native worker scopes.
- project writes are transactional or recoverable.

### Native bindings

Cross JNI/Swift boundaries with coarse, stable calls rather than per-pixel chatter. Prefer explicit owned buffers/handles and documented lifetime.

### Desktop

- orchestrator owns DAG state and worker lifecycle,
- heavy third-party engines may run as child processes,
- logs/progress are structured,
- cancellation must leave project state recoverable.

---

## 37. Security and privacy

Scans may capture private rooms and personal objects. Design for local-first processing.

Defaults:

- no cloud required for core product,
- explicit user action before remote/cloud upload,
- TLS for phone-desktop transfer,
- do not expose an unauthenticated LAN worker,
- no secrets in scan packages,
- clear deletion semantics for local raw captures,
- sanitize logs so they do not unexpectedly include image contents or credentials.

Any future cloud worker needs a separate trust/privacy/security design.

---

## 38. Dependency and license boundaries

Permissive-first production stack:

- COLMAP — audit repository/build dependencies; COLMAP itself uses a permissive license.
- Open3D — permissive.
- OpenCV — permissive.
- Ceres / Eigen — permissive.
- xatlas — permissive.
- meshoptimizer — permissive.
- Filament — permissive.
- gsplat — permissive at time of selection; re-check before shipping.

OpenMVS is AGPL-3.0 and therefore not a default proprietary runtime dependency.

CI or release tooling should maintain an inventory of direct and transitive licenses. A new dependency is an architectural cost, not a free convenience.

---

## 39. Testing architecture

### 39.1 Layers

1. pure unit tests for math/domain code,
2. property tests for serialization/transforms where valuable,
3. synthetic camera/depth fixtures with known ground truth,
4. golden scan datasets,
5. backend integration tests,
6. full pipeline reconstruction tests,
7. real-device capture tests,
8. performance/thermal regression tests.

### 39.2 Synthetic geometry suite

Maintain known scenes such as:

- plane,
- cube,
- sphere,
- textured cube,
- two planes with occlusion,
- camera ring around object,
- known mirror plane,
- depth map with controlled noise/outliers.

Ground truth enables quantitative debugging before real-world noise is introduced.

### 39.3 Dataset suite

Keep representative real scans for:

- matte textured object,
- low-texture object,
- glossy object,
- mirror in room,
- window/glass door,
- dark TV screen,
- small room,
- large room,
- mixed indoor lighting,
- outdoor scene,
- dynamic occlusion.

Where licensing/privacy permits, keep frozen input data and expected metric ranges.

---

## 40. Metrics

Useful metrics include:

### Capture
- accepted candidate rate,
- blur/exposure rejection rates,
- tracking-loss time,
- depth valid/confident ratios,
- coverage gain per keyframe.

### Sparse
- registered frame ratio,
- inlier matches,
- reprojection error distribution,
- track length,
- pose-prior residuals.

### Metric alignment
- scale residual,
- known-distance error,
- depth-to-geometry residual.

### Dense/mesh
- completeness,
- point/mesh error where ground truth exists,
- holes/outlier components,
- triangle count,
- peak memory/runtime.

### Texture
- UV utilization,
- texel density,
- source coverage,
- seam/color discontinuity metric,
- invalid/masked percentage.

### Mobile
- sustained throughput,
- peak memory,
- thermal status over time,
- battery impact where measured.

---

## 41. Observability

Each reconstruction run should produce a machine-readable run record:

```text
PipelineRun
├── run_id
├── pipeline_version
├── profile
├── stage records[]
├── timings
├── resource metrics
├── warnings
├── errors
├── config digest
└── outputs
```

Keep verbose engine logs separate from concise structured stage status. This is critical for long scans and user bug reports.

---

## 42. Reproducibility

Pro reconstruction should record enough information to reproduce or explain a result:

- input asset hashes,
- scan format version,
- calibration version,
- selected frame IDs,
- backend/tool versions,
- model versions,
- deterministic seeds where applicable,
- processing settings,
- platform/hardware details when numerical behavior can differ.

Exact bitwise equality may be unrealistic for all GPU paths, but the reproducibility contract should define expected tolerances.

---

## 43. Failure recovery

Capture:

- commit project metadata incrementally,
- avoid losing an entire scan due to one bad frame,
- make interrupted project reopenable.

Transfer:

- resumable chunks,
- per-asset integrity,
- idempotent finalize.

Reconstruction:

- DAG checkpoints,
- cache valid upstream outputs,
- keep temporary artifacts until dependent stages are safely committed,
- do not mark project result complete before export validation.

---

## 44. Model/AI boundaries

AI has two separate meanings in this project:

1. **AI used by developers through Codex** — governed by `SUBAGENT.md` and `MODEL_ROUTING.md`.
2. **ML inside the product** — compact segmentation/material classifiers and optional future neural reconstruction components.

Do not conflate them. A stronger coding model does not justify adding generative runtime behavior to Pro mode.

---

## 45. Version evolution

The roadmap is staged so each version validates a vertical slice before adding more research risk:

```text
foundation
-> capture
-> sparse
-> mesh
-> guidance
-> production texture
-> room TSDF
-> desktop transfer
-> mobile preview
-> Pro reproducibility
-> Smart surfaces
-> mirrors
-> Gaussian desktop
-> glass
-> on-device mesh
-> iOS + LiDAR
-> robustness
-> stable v1
-> advanced research
```

The version numbers are planning labels, not compatibility promises by themselves.

---

## 46. Architecture rules for future changes

A future design may replace major parts of this document. When that happens:

- prove the new path with measurements/tests when feasible,
- preserve persisted compatibility or provide migration,
- isolate third-party backends behind stable project contracts,
- keep raw source observations available,
- keep the distinction between measured, inferred, and generated information,
- avoid global rewrites merely to make the code aesthetically uniform,
- record a durable ADR only when the decision has long-lived cost or compatibility consequences.

The architecture exists to help the project recover from complexity, not to prevent the project from evolving.
