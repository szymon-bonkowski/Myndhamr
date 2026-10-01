# Reproducible v0.0 research harness

## Status
Completed

## Purpose and user-visible outcome
Android and a desktop CLI build from this repository, share a UI-independent KMP domain foundation, and exchange a versioned representative Protobuf message through real JNI/C++ bindings. Native tests, fixtures, benchmarks and CI establish reproducible foundations for later capture work.

## Scope
Preserve existing Android/iOS/shared UI shells. Add shared/domain, shared/scan-format, native/core and native/bindings with host/NDK builds, a headless desktop harness, tiny fixtures, developer commands, CI and direct dependency inventory.

## Non-goals
No capture, reconstruction, networking or production desktop GUI. No coordinate conversion or timestamp resampling.

## Current state
Clean initial commit be9a621. Android, iOS, shared Compose KMP and desktop Compose templates exist. No native, schema, CI or benchmark infrastructure. Baseline Gradle fails copying a resource directory with a negative filesystem timestamp; repair filesystem metadata, preserving contents. SDK 37, NDK 28.2.13676358, CMake, JDK and an arm64 Android device are available. GitHub origin and authenticated CLI are present.

## Contracts and invariants
Schema ownership belongs to shared/scan-format. Proto package myndhamr.scan.v1, explicit format_version=1; representative FoundationRecord carries project_id, source_timestamp_ns, opaque evidence bytes and repeated sample identifiers. Fields never renumbered/reused; unknown fields survive native round trips. No claim this is the complete scan manifest. Signed nanoseconds are retained exactly without clock conversion. JNI owns copied buffers, rejects malformed input and unsupported versions, and never retains JVM pointers. Input maximum 1 MiB is an interop resource bound, not a capture count limit. Shared domain has no UI/native engine dependency.

## Mathematics / algorithm
No reconstruction math. Synthetic serialization fixtures assert exact integer/byte values.

## Implementation map
Root Gradle integrates native host tasks. Existing shared remains UI owner; new domain is KMP. Scan-format uses generated JVM/Android Protobuf bindings, with future iOS generation explicitly deferred to its platform milestone. Native core is C++20; JNI is a separate shared library. DesktopApp becomes a minimal CLI.

## Milestones
### M1 — Baseline and domain/CLI boundaries
Work: repair baseline metadata, add pure KMP domain, remove desktop GUI dependencies.
Acceptance: Android builds, domain/common tests and desktop compilation pass.
Validation: ./gradlew :androidApp:assembleDebug :shared:jvmTest :shared:domain:jvmTest :desktopApp:build

### M2 — Native/schema vertical slice
Work: pin Protobuf, generate Java/C++, add C++20 core and narrow JNI; add versioned compatibility/failure tests.
Acceptance: host unit tests and Kotlin -> JNI -> C++ -> Kotlin semantic equality, unknown-field and rejection tests pass.
Validation: ./gradlew nativeTest :shared:scan-format:test

### M3 — NDK/device foundation
Work: integrate CMake into Android app and instrumented JNI tests; build native test executable for device.
Acceptance: APK and arm64 native tests build; native unit test executes on available Android device.
Validation: ./gradlew :androidApp:assembleDebug :androidApp:connectedDebugAndroidTest; tools/android-native-test.sh

### M4 — Reproducible harness and CI
Work: add fixture conventions, native serialization benchmark, inventory, developer docs and GitHub Actions.
Acceptance: goldens, smoke benchmark, code generation comparison and local CI equivalent pass; actual CI run when remote access permits.
Validation: tools/validate.sh; ./gradlew verifyProtoGeneration; GitHub Actions workflow.

## Test and validation matrix
Host native unit/failure tests; JVM JNI integration tests; Android instrumented JNI plus standalone native tests; deterministic protobuf goldens and regeneration; domain tests; Android lint; benchmark smoke. iOS compilation requires macOS and is reported separately.

## Performance / resource budgets
Bound bridge input/output to 1 MiB; benchmark uses a tiny fixed record, warmup and measured iterations, emits machine-readable timing with no timing threshold in CI. Build parallelism bounded for reliable local/CI execution.

## Migration and compatibility
No existing persisted scan format. Additive v1 changes retain tags and unknown fields; breaking version changes require new package and migration fixtures. Deterministic serialization is tested for fixed records; not a universal protobuf canonicalization guarantee.

## Failure handling
Reject corrupt bytes, unsupported/missing version and excessive bridge input with explicit exceptions. Surface missing tools/devices as failed prerequisites or clearly recorded environment limitations.

## Risks and mitigations
JNI/NDK Protobuf portability: pin compiler/runtime and exercise both platforms. Build plugin changes: preserve pinned initial versions unless evidence demands change. CI supply chain: pin actions and downloaded source checksum. Do not record credentials.

## Subagent/model plan
Primary owns architecture, native/schema/JNI and integration review. Luna high handles bounded domain/desktop implementation in an isolated worktree. Further bounded documentation/harness tasks delegated after interfaces settle. No Astra needed.

## Progress
- [x] M1 baseline/domain/CLI — baseline Android/shared tests and integrated domain/CLI tests pass.
- [x] M2 native/schema/JNI — host CTest, four JVM JNI tests, golden wire test and independent Java/C++ regeneration pass.
- [x] M3 Android/device — APK/test APK and both JNI ABIs build; lint clean; standalone native tests and benchmark executed successfully on arm64 Android 15. Phone rejects APK installation with INSTALL_FAILED_USER_RESTRICTED, so physical-device instrumented JNI is not executed; CI emulator gate remains required.
- [x] M4 fixtures/benchmark/inventory/CI — local validation and fresh GitHub Actions workflow passed, including 2/2 API 35 emulator JNI tests plus x86_64 native tests/benchmark.

## Decisions made during implementation
Generation tracks source schemas, compiler/pin and generator script; owned output is recreated to remove obsolete classes. CMake requires >=3.24 and extracted dependency timestamps use build-time values so upgrades rebuild dependents. Strict Android executed-test-count validation prevents an instrumentation startup failure from looking successful.
Retain existing app paths; introduce useful boundaries rather than wholesale monorepo path migration. Preserve iOS/shared UI templates. Replace generated desktop GUI with headless harness per explicit v0.0 request. Generated wire types are JVM/Android initially; pure domain supports iOS.

## Discoveries
AndroidX ext-junit supplies the JUnit adapter but not AndroidJUnitRunner itself. Explicit runner dependency is required; without it AGP reported success with zero tests after instrumentation startup crashed. The executed-test-count guard exposed this and now passes 2/2 on a local emulator.
Baseline metadata had negative timestamps on several checkout directories; repaired metadata and baseline Android/shared tests passed. Android Studio JBR has no JNI headers; Gradle nativeConfigure uses the full daemon JDK. Protobuf 36.2/Java 4.36.2 with Abseil 20250512.1 and bundled utf8_range selected from verified upstream release/source; source archive hashes pin downloads.

## Final validation
Executed: baseline `:shared:jvmTest :androidApp:assembleDebug`; integrated `:shared:domain:jvmTest :desktopApp:test`; CMake host Release build/CTest; `nativeTest :shared:scan-format:test verifyProtoGeneration benchmarkSmoke` all pass. Benchmark initial host smoke: 1000 round trips, checksum 38000; timing is diagnostic only. Android APK/test APK/lint passed; shared/domain Android host tests passed; standalone Android native test and benchmark passed (1000 round trips/checksum 38000). Phone instrumentation produced zero tests despite Gradle success; direct adb install confirmed INSTALL_FAILED_USER_RESTRICTED. Added explicit executed-test-count check. First remote fresh host/Android build passed; strict emulator gate caught a missing AndroidJUnitRunner runtime dependency. Added explicit test-only runner 1.7.0. Local x86_64 API 37.1 emulator now executes both JNI tests successfully. Corrected full remote run passed on API 35, including 2/2 JNI tests and standalone x86_64 native tests/benchmark. Run: https://github.com/szymon-bonkowski/Myndhamr/actions/runs/36798401898 (implementation commit a0559d2).

## Acceptance record
| Requirement | Status / evidence |
| --- | --- |
| Clean configuration | Pass: Gradle configuration/cache and CMake host/NDK configure |
| Android build | Pass: app/test APK, arm64-v8a and x86_64 JNI |
| Desktop/JVM foundation | Pass: domain tests, CLI tests/build/run; no GUI dependency |
| Host C++20 core | Pass: Release build with project warnings as errors |
| Host native unit tests | Pass: CTest foundation |
| Android C++ build | Pass: both JNI ABIs and standalone arm64 test/benchmark |
| Android native unit execution | Pass: physical Android 15 arm64 phone |
| Reproducible Protobuf generation | Pass: independent Java/C++ outputs compared; stale generation regression |
| Real Kotlin/C++ Protobuf round trip | Pass: four JVM JNI tests including unknown fields, extreme timestamps, rejection and golden |
| Benchmark smoke | Pass: host and Android serialization JSON, correctness checksum 38000 |
| Golden/synthetic fixtures | Pass: reviewed wire fixture with exact integer/raw bytes |
| Local CI equivalent | Pass: tools/validate.sh incl. Android host tests and lint |
| Dependency/license check | Pass: catalog/pins/license inventory checker; complete transitive shipping audit deferred |
| Remote CI and emulator JNI | Pass: full GitHub Actions run 36798401898; API 35 x86_64 emulator JNI 2/2 and native tests/benchmark |
| Physical-device instrumented JNI | Not executed: INSTALL_FAILED_USER_RESTRICTED; zero tests detected explicitly |
| iOS build/tests | Not executed: Linux lacks Xcode/macOS; iOS targets/shell preserved |

## Outcome
Completed v0.0: UI-independent KMP domain, headless JVM CLI, C++20 host/NDK core, owned-buffer JNI bridge, versioned generated Protobuf framework, synthetic golden fixtures, reproducible generation, smoke benchmarks, dependency/license inventory, developer commands and CI. All attainable acceptance criteria passed locally and in a fresh remote build. No capture/reconstruction/networking or other v0.1 behavior was added.

Important decisions: preserve existing app/UI paths and shells; isolate pure domain and generated wire ownership; build host protoc from the same pinned source as the C++ runtime; use coarse copied byte calls with explicit failure and resource bounds; retain unknown fields and original timestamps/evidence. Generated bindings currently support JVM/Android; iOS wire integration stays with the iOS milestone, while existing iOS app and KMP targets remain available.

Validation limits: physical phone refuses APK installation, so physical-device instrumented JNI was not executed. Physical Android native tests passed; local API 37.1 and remote API 35 emulator JNI tests passed. iOS compilation/tests were not executed because this host is Linux. The direct dependency inventory passes; a complete transitive/distribution notice audit remains a release responsibility.

Implementation commits: f116a71, 04c3c6a, c7979c1, ae97fca, 8e6127b, f9789eb, a0559d2. The closure commit updates this plan only and skips a redundant CI run after the implementation passed. Temporary worker checkouts and the task-created local AVD were removed. Next milestone: v0.1 Capture Recorder, with its own specification and ExecPlan.
