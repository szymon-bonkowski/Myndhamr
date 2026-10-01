# ADR-0002: Capture evidence, source clocks and recoverable scan projects

- Status: Accepted
- Date: 2026-10-01
- Supersedes: none
- Superseded by: none

## Context
v0.1 captures auditable Android observations for future desktop ingest. ADR-0001 already owns the versioned Protobuf boundary. Camera2, ARCore and IMU timestamps are not interchangeable: ARCore documents an undefined frame time base and exposes a separate Android camera timestamp. Real SM-S948B testing also returned repeated Camera2 exposures while ARCore incremented its frame timestamp by 1ns. No reconstruction convention may silently reinterpret these observations.

## Decision
Add capture messages under myndhamr.scan.v1 without changing FoundationRecord. A project directory ending .scan3d contains manifest.pb, four delimited Protobuf journals under metadata/ (frames, imu, camera, events), immutable assets/ and preserved recovery tails. Final manifests reference journal SHA-256 hashes and byte sizes; image/depth/confidence references carry their own hashes and encoding. Export is a validated ZIP snapshot; importer rejects unsafe paths, malformed records, missing/corrupt assets and configured resource-budget excess. Format version1 rejects unknown incompatible versions while retaining Protobuf unknown fields.

Each original timestamp keeps integer nanoseconds, a source-domain identifier and observed callback arrival elapsed realtime. CAMERA_REALTIME is identity-compatible with Android elapsed realtime only when CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE reports REALTIME. CAMERA_UNKNOWN retains no claimed cross-sensor conversion. ARCORE_FRAME is an undefined original clock; ARCORE_DEPTH is recorded separately. Numeric inter-clock deltas are measured diagnostics and explicitly unverified mappings, not permission to normalize or interpolate. Exact Camera2 association uses Frame.androidCameraTimestamp and Camera2 SENSOR_TIMESTAMP. CPU Image.timestamp retains its independent ARCORE_CPU_IMAGE domain; current Frame.acquireCameraImage() supplies the provider frame association, recorded explicitly as ARCORE_CURRENT_FRAME_ACQUIRE_CAMERA_IMAGE. Acquisition and calibration snapshots occur before the next Session.update; dimensions, full crop and YUV format are checked. This guarantees frame correspondence without claiming clock equivalence. Legacy records without image_association still require exact same-clock camera/image timestamps. Unknown association values are rejected. Zero/unavailable or repeated Android exposures are not new capture frames; counts remain in session diagnostics. Raw IMU stays in Android device axes, accelerometer m/s² and gyroscope rad/s; no resampling.

T_world_camera is right-handed ARCore world/camera, metres, column vectors, column-major4x4. Camera axes +X right,+Y up,-Z forward. Valid poses exist only for TRACKING. CPU calibration is unrotated image pixels (+X right,+Y down), with optical axes obtained by diag(1,-1,-1) from camera coordinates. Store actual ARCore CPU-image intrinsics; preserve Camera2 lens calibration/distortion/crop metadata separately. No undistortion is silently applied.

ARCORE_RAW and ARCORE_SMOOTHED explicitly identify AR-estimated depth variants, never hardware LiDAR/ToF. Depth is little-endian unsigned16 axial-Z millimetres, zero invalid, unit_meters=0.001; confidence is U8. Preserve dimensions, source timestamp and texture-view intrinsics scaled to depth resolution, plus a frame-measured CPU-pixel to depth-pixel homography from ARCore transformCoordinates2d; depth may crop the CPU image, so width/height scaling of CPU intrinsics is invalid. Repeated raw depth timestamps may represent reprojection; do not fabricate freshness. Unsupported/temporary absence is explicit. Non-keyframe depth metadata has asset_omitted_by_policy; manually requested tracked keyframes save acquired RGB/depth/confidence assets. RGB preserves packed I420 CPU YUV without JPEG conversion. There is no automatic keyframe policy or photo-count limit.

One sequential worker owns project writes. Assets commit via fsync+rename; journals sync periodically and on finish. Interrupted projects recover only complete validated journal records, preserving incomplete tails. Recovered capture is finalized INTERRUPTED rather than pretending continuity across a new AR world origin. Raw committed assets remain immutable. Queues enforce task/byte budgets and preserve terminal-control capacity; critical overflow stops capture explicitly.

## Rationale
Independent source observations allow future synchronization audits and backend changes. Keeping full-rate metadata/IMU but manual image assets bounds ordinary storage without arbitrary photo limits. Journals avoid loading an entire scan and permit recoverable prefixes. SQLite indexing is deferred until queries require it, an intentional narrow departure from the suggested reference storage topology.

## Alternatives considered
### Assume all nanoseconds share one clock
Incorrect per Google API contracts; can conceal offsets and stale associations.
### Own the camera independently from ARCore
Competes for the same device. SharedCamera supplies wrapped ownership and capture callbacks.
### Store complete scans in one manifest or database blobs
Requires unbounded memory or weakens incremental evidence preservation.

## Compatibility and migration
The additive image_association field (tag21) preserves existing field semantics and legacy validation. Real moving test exposed CPU137849012053824 versus Camera2137848998457227: their13596597ns numeric difference is preserved, never subtracted or treated as a calibrated offset. Earlier rejected-image scans remain unchanged. Older validators fail closed on new provider-associated unequal-clock keyframes despite additive wire compatibility; upgraded readers retain the legacy validation branch. FoundationRecord fixtures and JNI stay compatible. Additive fields retain tags; breaking schemas require a new version and migration fixtures. Capture assets never use the FoundationRecord JNI bridge's1MiB budget.

## Validation / evidence
Domain fixtures cover mapping overflow/sign/order, one-to-one association, rigid transforms, optical conversion, intrinsics and bounded SLERP. Project-store tests cover unknown fields,100k IMU samples, interruption, root aliases, nested-symlink rejection, corruption and ZIP roundtrip. Physical SM-S948B tests passed real Camera2/ARCore coexistence, original CPU-image association, calibration, IMU, Raw/Smoothed depth/confidence, repeated lifecycle, recovery, UI/system export and a15min38s moving capture. Actual observations and source-world pose discontinuities are retained in the [completed v0.1 ExecPlan](../../plans/completed/2026-10-01-v0-1-capture-recorder.md).

Sources: [ARCore Frame](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame), [SharedCamera](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera), [Camera2 timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE), [ARCore raw depth](https://developers.google.com/ar/develop/java/depth/raw-depth).

## Revisit when
iOS capture adds its native clock/calibration backend, durable indexing queries are required, or a breaking scan-format migration is specified.
