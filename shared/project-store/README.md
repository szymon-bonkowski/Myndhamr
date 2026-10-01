# Raw capture project store (v1)

The JVM module also runs on Android API 24+. It uses the pinned generated Protobuf contract and Java file/ZIP/SHA-256 APIs; there is no database dependency. A sequential persistence worker owns `CaptureWriter`; callbacks must enqueue bounded copies rather than share the writer.

```kotlin
val writer = CaptureProject.create(projectDirectory, recordingManifest)
val image = writer.writeAsset("assets/rgb/000001.jpg", jpegBytes, "jpeg")
writer.appendFrame(frameWithImageReference)
writer.appendImu(rawImuSample)
writer.appendCamera(rawCamera2Observation)
writer.appendEvent(sessionEvent)
writer.sync() // Periodic durability checkpoint, chosen by the caller.
val completed = writer.finish(endElapsedNs, CaptureState.COMPLETED)
CaptureProject.export(projectDirectory, outputScan3d)
```

`CaptureValidation.POSE_CONVENTION` and `IMAGE_CONVENTION` expose the exact required convention strings. Each source timestamp retains its clock domain and callback arrival time. ARCore, Android-camera, and CPU-image timestamps are separate fields. Camera2 observations remain an independent stream when callback ordering prevents immediate exact association. Tracking-limited frames have no pose. Depth absence is explicit. Available non-keyframe depth can omit binary data only with `asset_omitted_by_policy=true`; available keyframe depth must reference a saved asset.

The project contains `manifest.pb`, four length-delimited Protobuf journals in `metadata/` (`frames.pb`, `imu.pb`, `camera.pb`, `events.pb`), and immutable files in `assets/`. Asset commits sync a temporary file before atomic same-directory rename. Journals write directly to disk without buffering the whole session. `sync` and `close` sync every journal, and `finish` closes them before recording size and SHA-256 in an atomic final manifest. The Android caller must choose a periodic sync interval; records after its last sync may be lost on power failure. No claim of portable filesystem directory-fsync durability is made.

`close` without `finish` intentionally leaves a recording project. `recover` opens only a recording project, validates complete journal records, preserves an incomplete trailing record under `recovery/`, and truncates only that incomplete tail before appending. Complete malformed or oversized records fail. An interrupted manifest commit is preserved in recovery too. Recovery cannot recreate evidence that was never durably written. A finalized project cannot be reopened for recording.

`CaptureProject.validate` hashes stream and frame asset references and returns counters, tracking/depth/keyframe counts, maximum within-source IMU gap, timestamp regression count, and bounded diagnostics. `visitFrames`, `visitImu`, `visitCamera`, and `visitEvents` invoke one visitor at a time and preserve unknown fields. The validator holds at most one bounded record, 128 cached asset references, and 64 IMU source keys; diagnostics stop accumulating after 100 errors. The error counter retains the full number of failures.

`export` accepts a valid finalized snapshot and writes a ZIP `.scan3d`. It never overwrites an export. `importPackage` requires a new destination and rejects unsafe paths, symlink traversal, unknown namespaces, duplicate files, and excessive actual expanded bytes or entry counts. Failed imports remove the newly created destination. `StoreLimits` exposes configurable metadata, manifest, expanded-byte, and entry budgets; these are safety/resource budgets, not a photo-count limit. SHA-256 is integrity detection, not authentication.

Validation: `./gradlew :shared:project-store:test`. Tests include exact nanosecond/unknown-field roundtrip, invalid pose/calibration/tracking/keyframe rejection, immutable assets, corrupt and missing references, interrupted-tail preservation, explicit depth omission, ZIP traversal/resource failures, and 100,000 raw IMU records visited and validated incrementally.
