# ADR-0001: Versioned Protobuf foundation and owned JNI bytes

- Status: Accepted
- Date: 2026-10-01
- Supersedes: none
- Superseded by: none

## Context
The v0.0 gate requires a real Kotlin/C++ serialization round trip. There is no existing persisted scan schema. UI and reconstruction engines must evolve independently; raw evidence and source timestamps must survive the boundary exactly.

## Decision
Own schemas in `shared/scan-format/src/main/proto`, with package `myndhamr.scan.v1` and explicit `format_version`. The small `FoundationRecord` is a toolchain probe, not a scan manifest. Version 1 permits additive fields; never renumber or reuse tags. Retire fields with `reserved`. Breaking changes require a new package/version and tested migrations before adoption. Unsupported versions fail explicitly rather than being interpreted as v1.

Use generated Protobuf Java bindings on JVM/Android and C++ bindings generated from the same schema. Pin compiler and C++ runtime together in `native/dependencies.cmake`; Java's artifact major differs but release suffix matches. Host-built protoc generates both languages, including for NDK cross-compilation. Generated output remains ignored under build directories.

The initial JNI API takes a copied byte array and returns a newly owned byte array. It never retains JVM memory or exposes third-party C++ types. It bounds input/output to 1 MiB, rejects invalid bytes and preserves unknown fields, integer timestamps, unsigned IDs and opaque evidence. This bound applies only to the representative bridge call; it imposes no scan/photo-count policy.

## Rationale
A single generated wire contract avoids independent handwritten serializers. Coarse byte calls match the architecture's native boundary and have straightforward ownership and failure behavior. A pure KMP domain module remains available to iOS without tying common types to Java bindings.

## Alternatives considered
### Independent Kotlin and native serialization tests
Would not demonstrate the configured JNI path and cannot meet the interoperability gate.

### Handwritten multiplatform wire codec
Would duplicate solved infrastructure and broaden the compatibility/failure surface.

### Full scan schema and native handle API now
Would prematurely decide v0.1 capture contracts and lifetime complexity without requirements.

## Consequences
### Positive
- One schema/compiler pin; host and Android exercise the actual bridge.
- Unknown-field and corruption behavior are covered by automated tests.
- UI has no reconstruction implementation.

### Negative / constraints introduced
- The current generated-binding module supports JVM/Android. iOS wire integration must be added when its capture backend is specified.
- Host builds compile protoc and its dependencies on first use.
- Deterministic serialization means repeatability for a fixed runtime/schema, not universal canonical wire bytes.

## Compatibility and migration
No existing scan files are migrated. The golden fixture freezes representative v1 wire values. New breaking schemas need old/new fixtures, explicit conversion and preservation of original evidence.

## Validation / evidence
`nativeTest`, `:shared:scan-format:test`, `verifyProtoGeneration` and Android native/instrumented tests verify this boundary. Source contract: [Protobuf runtime guarantees](https://protobuf.dev/support/cross-version-runtime-guarantee/). See the v0.0 ExecPlan for executed results.

## Revisit when
The v0.1 scan contract or iOS capture integration needs a wider representation; larger workloads require a specified owned-buffer/handle API rather than silently increasing this probe's budget.
