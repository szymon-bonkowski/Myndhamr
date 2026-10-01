# Myndhamr

Myndhamr is a measurement-first 3D scanning system. The repository is being built in small, verifiable stages; the modules below describe what exists today, while capture backends and reconstruction workers remain future work until their implementations are owned and tested.

## Current layout

- `androidApp/` and `iosApp/` are platform app shells. `shared/` contains the current Compose Multiplatform UI module.
- `shared/domain/` is pure Kotlin Multiplatform domain code, with no UI dependency.
- `shared/scan-format/` owns versioned Protobuf contracts and generated Java bindings. Its Kotlin/JVM wrapper crosses the JNI boundary into `native/`.
- `desktopApp/` is a headless CLI entry point. It can grow into a desktop product without making the reconstruction engine depend on a GUI.
- `native/core/`, `native/bindings/`, `native/tests/`, and `native/benchmarks/` contain the C++20 foundation, JNI bridge, host tests, and smoke benchmark.
- `tests/fixtures/` contains reviewed golden data shared across language boundaries.

Camera capture, transfer, dense reconstruction, and desktop worker modules are deferred until their implementation and contracts exist. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for system boundaries and invariants, and [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) for the direct dependency and license inventory.

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
./gradlew :androidApp:connectedDebugAndroidTest
```

`tools/android-native-test.sh` and the connected Android test require a configured SDK and attached compatible device/emulator. On a host with the prerequisites above, the native layer can also be built and tested directly:

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel 4
ctest --test-dir build/native --output-on-failure
```

`tools/validate.sh` runs the local CI equivalent. GitHub Actions also executes Android JNI and native tests on an x86_64 API 35 emulator. Benchmark output is diagnostic JSON; its wall time has no CI pass threshold.
