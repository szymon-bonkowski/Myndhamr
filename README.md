# Myndhamr

Myndhamr is a measurement-first 3D scanning system. The repository includes Android capture validated on a physical SM-S948B and CLI desktop ingest, sparse reconstruction, robust metric alignment and trajectory diagnostics.

## Current layout

- `androidApp/` records Camera2/ARCore shared-camera sessions, manual I420 keyframes, optional AR estimated depth/confidence and raw IMU. `iosApp/` remains a platform app shell. `shared/` contains the current Compose Multiplatform UI module.
- `shared/domain/` is pure Kotlin Multiplatform domain code, with no UI dependency.
- `shared/scan-format/` owns versioned Protobuf contracts and generated Java bindings. Its Kotlin/JVM wrapper crosses the JNI boundary into `native/`.
- `shared/project-store/` provides streaming immutable evidence, interrupted-write recovery and validated ZIP export/import.
- `desktopApp/` provides headless `inspect`, `replay`, `validate`, `ingest` and `reconstruct` commands. Reconstruction remains independent of a GUI.
- `desktop-workers/colmap-adapter/` isolates pinned COLMAP sparse reconstruction; `desktop-workers/reconstruction-core/` exports metric products and calibrated depth diagnostics using the C++20 similarity estimator in `native/geometry/`.
- `native/core/`, `native/bindings/`, `native/tests/`, and `native/benchmarks/` contain the C++20 foundation, JNI bridge, host tests, and smoke benchmark.
- `tests/fixtures/` contains reviewed golden data shared across language boundaries.

Dense reconstruction, meshing and transfer remain deferred. v0.1 acceptance includes a15min38s moving capture; see the [completed capture ExecPlan](plans/completed/2026-10-01-v0-1-capture-recorder.md) and [capture contracts](docs/adr/0002-capture-evidence-and-clock-contract.md). See [sparse reconstruction commands and acceptance](docs/SPARSE_RECONSTRUCTION.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for system boundaries and invariants, and [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) for the direct dependency and license inventory.

## Build prerequisites

- Full JDK 21 with JNI headers. Android Studio's bundled JBR may not include the headers needed for host JNI builds; set `JAVA_HOME` to a full JDK.
- CMake 3.24 or newer, a C++20 compiler, Python 3.11 or newer, and network access for the first dependency-fetching build.
- Android SDK platform 37, NDK `28.2.13676358`, and CMake `3.30.5`; set `ANDROID_HOME` to the SDK root. Android native builds target `arm64-v8a` and `x86_64`.
- iOS build and device validation require macOS with Xcode; they are not available on the current Linux host.

## Validation commands

Run from the repository root:

```sh
./gradlew :androidApp:assembleDebug :shared:jvmTest :shared:domain:jvmTest :desktopApp:build
./gradlew nativeTest :shared:scan-format:test verifyProtoGeneration benchmarkSmoke
./gradlew :shared:scan-format:generateProto
./gradlew :desktopApp:run --args=--help
python3 tools/check-inventory.py
tools/validate.sh
tools/android-native-test.sh
tools/android-jni-test.sh
ANDROID_SERIAL=SERIAL tools/android-capture-test.sh
```

`tools/android-native-test.sh` and `tools/android-jni-test.sh` require a configured SDK and attached compatible device/emulator. On a host with the prerequisites above, the native layer can also be built and tested directly:

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel 4
ctest --test-dir build/native --output-on-failure
```

`tools/validate.sh` runs the local CI equivalent. GitHub Actions also executes Android JNI and native tests on an x86_64 API 35 emulator. Benchmark output is diagnostic JSON; its wall time has no CI pass threshold.

## Capture and inspect

Grant Camera permission, start a scan, move slowly across textured surfaces, and press **Manual keyframe** to save the next fully tracked exposure with matching calibration and Camera2 metadata. Stop before export. **Export scan** uses Android's document picker; **Reopen last project** validates a saved scan or recovers a complete interrupted prefix. Backgrounding ends a scan as interrupted; a new scan gets a new AR world/session. Raw committed assets remain immutable.

```sh
./gradlew :desktopApp:installDist
desktopApp/build/install/desktopApp/bin/desktopApp inspect /absolute/path/to/project.scan3d
desktopApp/build/install/desktopApp/bin/desktopApp validate /absolute/path/to/export.scan3d.zip
desktopApp/build/install/desktopApp/bin/desktopApp replay /absolute/path/to/project.scan3d
```

`inspect` emits quantitative JSON; `replay` streams one record per line. Invalid projects return a nonzero exit status. Manual keyframes store unrotated I420 CPU YUV; per-frame metadata and raw IMU remain continuous. Depth assets are manual-keyframe evidence, while acquisition availability/timestamps are recorded on every admitted camera exposure. Source clocks are preserved; numeric cross-clock differences do not establish clock equivalence.

Development acceptance uses an explicit USB serial and debug-only, DUMP-permission-protected ADB broadcasts (unavailable in a release build):

```sh
adb -s SERIAL shell am start -n io.github.szymonbonkowski.myndhamr/.MainActivity
adb -s SERIAL shell am broadcast -a io.github.szymonbonkowski.myndhamr.CAPTURE_COMMAND -p io.github.szymonbonkowski.myndhamr --es capture_command start
# Commands: start, keyframe, stop, export, reopen.
tools/capture-pull.sh SERIAL scans/PROJECT.scan3d build/acceptance/PROJECT
```

tools/android-capture-test.sh requires an ARCore supported physical phone and fails if the capture test is skipped or missing. tools/android-jni-test.sh selects the JNI regressions explicitly and runs on CI emulators. Pose/depth/moving-session acceptance requires real camera movement; unit tests do not substitute for it.
