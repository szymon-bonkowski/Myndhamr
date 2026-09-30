# AGENTS.md — Myndhamr

## Mission

Myndhamr is a measurement-first 3D scanning system for objects, rooms, and larger scenes. The mobile app captures RGB, camera calibration, pose, depth when available, confidence, IMU, timing, and quality data. Reconstruction may run on-device for suitable workloads or in a desktop companion for heavier photogrammetry, meshing, texturing, TSDF, and Gaussian Splatting.

The project is **spec-driven**. The human decides product behavior, architecture, algorithms, mathematical conventions, and acceptance criteria. Codex implements, validates, and reports against those instructions. Do not invent a new architecture merely because a local implementation shortcut looks attractive.

## Read only what the task needs

Start with this file. Then load the smallest relevant set of project guidance:

- `ARCHITECTURE.md` for system boundaries, data contracts, coordinate conventions, math, reconstruction flows, and invariants.
- `SUBAGENT.md` before delegating meaningful work or choosing a model for a subagent.
- `PLANS.md` for multi-hour work, cross-module changes, research-heavy algorithms, or significant refactors.
- `ADR.md` only when the task may require a durable architectural decision.
- `MODEL_ROUTING.md` when selecting models/reasoning effort or evaluating whether Astra is justified.
- the matching `.agents/skills/<name>/SKILL.md` for a roadmap milestone or major workstream.
- `3d_scanner_architecture_roadmap.md` as a deep reference and historical blueprint, not as a continuously synchronized description of the working tree.

Do not load every project document into context by default. Prefer targeted reads.

## Authority and conflict order

When instructions disagree, use this order:

1. The user's current explicit request and the current task specification.
2. The actual repository state: code, tests, schemas, fixtures, benchmarks, and platform constraints.
3. The active ExecPlan for this task.
4. Accepted ADRs that define durable contracts.
5. `ARCHITECTURE.md`.
6. `3d_scanner_architecture_roadmap.md`.

A reference document is not a reason to revert intentional newer code. If a real conflict affects correctness or a public contract, report it explicitly instead of silently choosing one side.

## Repository shape

The intended repository boundaries are approximately:

```text
apps/
├── mobile-android/
├── mobile-ios/
└── desktop/                 # may begin as CLI; GUI stack is not a hard dependency

shared/
├── domain/                  # KMP domain/state
├── scan-format/             # versioned scan/package contracts
├── networking/              # transfer/discovery abstractions
├── project-store/           # project metadata/storage abstractions
└── ui-shared/               # Compose UI where sharing is useful

platform/
├── android-capture/         # Camera2 + ARCore
├── ios-capture/             # AVFoundation + ARKit + LiDAR when available
├── android-gpu/
└── ios-gpu/

native/
├── core/                    # C++20 geometry/reconstruction primitives
├── geometry/
├── tsdf/
├── texture/
├── renderer-bridge/
└── bindings/                # JNI / Swift interop

desktop-workers/
├── reconstruction-core/
├── colmap-adapter/
├── open3d-adapter/
└── splat-worker/            # Python/PyTorch/gsplat when needed

specs/
plans/
docs/adr/
.agents/skills/
```

Treat this as a reference topology. Inspect the working tree before assuming a path exists.

## Hard product invariants

Do not violate these without an explicit, current requirement:

- Preserve raw capture data. Smart processing may add derived assets; it must not overwrite the original evidence.
- Do not impose an arbitrary photo-count limit. Resource limits must be based on storage, memory, thermals, compute, or selected quality profile.
- Pro/Raw mode must not fabricate unseen geometry or texture. Deterministic correction and measured-sensor fusion are allowed when auditable.
- Smart mode may classify and reinterpret difficult surfaces, but uncertainty and provenance must remain available.
- Experimental features must be isolated from Pro guarantees.
- Metric scale and coordinate transforms are correctness-critical. Never silently change coordinate handedness, axis conventions, units, pose direction, or matrix layout.
- Timestamps are data. Do not convert or resample them casually. Preserve source clocks and conversion metadata.
- Depth is not a single interchangeable signal. Preserve source type, confidence, calibration, resolution, timing, and sensor-specific uncertainty.
- iPhone LiDAR is a first-class depth source, not a separate product. It feeds the same scan/reconstruction contracts through an iOS-specific capture backend.
- The final high-quality model must be reproducible from stored inputs and versioned processing configuration where Pro mode promises reproducibility.

## Platform boundaries

### Android

Use native Android capture where hardware access matters:

- Camera2 for controlled capture.
- ARCore for tracking, camera pose/intrinsics, Raw Depth/Depth, and confidence when available.
- Kotlin/KMP for product/domain orchestration where practical.
- C++20 through a narrow binding surface for heavy native core work.

Do not hide platform-critical capture semantics behind a generic abstraction before their timing and calibration behavior are understood.

### iOS

Use:

- AVFoundation for camera capture.
- ARKit for tracking, pose, depth, scene depth, and LiDAR-backed depth where supported.
- raw `sceneDepth` for reconstruction-oriented data when appropriate.
- `smoothedSceneDepth` primarily for preview/guidance unless a specification says otherwise.
- Core ML for platform inference paths when useful.
- Metal for platform GPU paths when justified.

Compose Multiplatform may provide shared screen bodies. Native SwiftUI/UIKit chrome is allowed and encouraged when it materially improves iOS behavior such as system navigation, tabs, or Liquid Glass. Do not duplicate the full UI in SwiftUI without a concrete product reason.

### Desktop

The first desktop deliverable may be CLI-only. Do not force a GUI architecture early.

Heavy reconstruction runs in process-isolated workers where practical. A future Compose Desktop UI is allowed, but the reconstruction engine must not depend on the GUI framework.

## Reconstruction boundaries

Use mature libraries instead of reimplementing solved foundations unless a spec explicitly asks for research:

- COLMAP: feature matching/SfM/bundle adjustment and selected dense reconstruction paths.
- Open3D: point clouds, TSDF/VoxelBlockGrid, geometry utilities.
- OpenCV: image/CV primitives, masks, warps, calibration helpers, optical flow, RANSAC helpers.
- Ceres + Eigen: numerical optimization and linear algebra.
- xatlas: UV unwrapping/atlas generation.
- meshoptimizer/gltfpack: mesh and glTF optimization.
- Filament: primary PBR mesh viewer.
- gsplat + PyTorch: desktop Gaussian Splatting worker.

OpenMVS is **not** a default production dependency for a proprietary build because of AGPL-3.0. It may be used for research/benchmarking or in a licensing model that explicitly permits it.

Audit third-party licenses before introducing a new production dependency.

## Code ownership by language

Prefer:

- Kotlin/KMP: product state, shared domain, UI where useful, project management, transfer orchestration, configuration.
- C++20: performance-sensitive geometry, reconstruction primitives, TSDF, projection/visibility, native algorithms.
- Swift/Objective-C interop: iOS-only capture/AR/Metal/Core ML integration.
- Python: offline training, experiments, gsplat worker, data tooling; do not put product-critical low-latency mobile runtime in Python.

Use the language that owns the platform/runtime requirement. Do not force cross-platform sharing when it weakens measurement fidelity or performance.

## Before editing

1. Inspect the current files and tests that own the behavior.
2. Identify the applicable spec/skill/ExecPlan.
3. State or infer the smallest safe change boundary.
4. Identify contracts that cannot move: schema, units, coordinates, timestamps, public APIs, persisted formats.
5. For a change that will likely take multiple hours or cross several subsystems, follow `PLANS.md` before implementation.
6. If work is delegable, follow `SUBAGENT.md`; do not send an ambiguous architecture problem to a cheap implementation worker.

## Implementation discipline

- Make the smallest complete change that satisfies the current specification.
- Keep architecture decisions separate from mechanical implementation when possible.
- Do not perform broad opportunistic refactors inside a feature task.
- Do not rename public persisted fields, protocol messages, coordinate semantics, or file formats without migration handling.
- Do not add dependencies merely to save a small amount of code.
- Prefer explicit interfaces around third-party engines so backends can be benchmarked or replaced.
- Keep platform adapters thin enough to test domain behavior without hardware, but do not abstract away sensor-specific facts.
- Keep deterministic stages deterministic unless nondeterminism is required and documented.
- Preserve provenance for derived geometry, masks, material decisions, and confidence.
- Avoid silent fallbacks that make a bad scan look successful. Return structured failure/insufficient-data states with actionable causes.

## Numerical and 3D rules

For math-heavy changes:

- Declare frames and transform direction explicitly, e.g. `T_world_camera` versus `T_camera_world`.
- Declare matrix/vector convention before implementing equations.
- Use SI units internally unless a specification says otherwise; store units in external formats where ambiguity is possible.
- Test identity, pure translation, pure rotation, known-scale, and round-trip transform cases.
- Use synthetic fixtures with known ground truth before relying on visual inspection.
- Compare tolerances using scale-aware numeric assertions.
- Validate finite values and reject NaN/Inf at subsystem boundaries.
- Treat image/depth alignment and timestamp synchronization as calibrated transforms, not approximate indexing.

If a derivation is unclear, stop the implementation worker and escalate planning/review according to `SUBAGENT.md`.

## Tests and validation

Testing must verify behavior, not merely mirror implementation.

For every substantive change, run the smallest relevant set first, then broaden only when warranted:

- unit tests for local invariants,
- property tests for transforms/serialization where valuable,
- golden/synthetic reconstruction fixtures for geometry,
- schema round-trip and migration tests for persisted formats,
- integration tests for worker boundaries and transfer/resume,
- device tests for Camera2/ARCore and AVFoundation/ARKit behavior,
- benchmark/regression checks for performance-sensitive stages.

For visual 3D output, include quantitative checks where possible: registration ratio, scale error, reprojection error, depth residuals, mesh error, texture coverage, seam error, memory, runtime, and thermals.

Do not declare success because a model looks plausible in one viewer.

## Long-running work

Use an ExecPlan when the task is multi-hour, cross-module, changes durable contracts, introduces a major algorithm, adds a performance kernel, or contains research uncertainty. Follow `PLANS.md`.

When an ExecPlan is required, create and maintain it yourself under `plans/active/`. After all completion criteria pass, move it to `plans/completed/`. Do not require the user to create, update, move, or otherwise manage ExecPlan files manually.

Each milestone must have observable acceptance criteria and a validation command/procedure. If a milestone validation fails, fix it before proceeding unless the plan explicitly records why the failure is accepted.

## Subagents and model routing

Use `SUBAGENT.md` as the detailed policy. The short version is:

- GPT-6.1 Sol does the important reasoning, planning, architecture, difficult debugging, and review by default.
- GPT-6 Luna is preferred for narrow, well-specified implementation, codebase exploration, fixtures, mechanical refactors, and repetitive work.
- GPT-6 Astra is a targeted escalation for genuinely research-grade scientific/numerical problems, not a routine coding default.
- Reasoning effort is part of routing. Do not use `max` automatically.
- A worker must receive a bounded task with interfaces, invariants, allowed files, tests, and acceptance criteria. If those are missing, improve the task before delegating it.

## Skills

Use the most specific matching repo skill under `.agents/skills/` when a task belongs to a roadmap version or a listed heavy workstream.

A milestone skill is a control plane, not the architecture source. It should point you to the relevant architecture/spec and define scope, workflow, model routing, acceptance, and escalation conditions.

Do not load unrelated skills just because they exist.

## Architectural decisions

Read `ADR.md` before creating an ADR. Use ADRs for expensive, durable decisions such as persisted schemas, coordinate conventions, process boundaries, core production dependencies/licenses, security/trust boundaries, or replacement of a reconstruction backend.

Do not create ADRs for routine implementation choices, short-lived experiments, or every refactor.

## Documentation behavior

Do not require the large blueprint or `ARCHITECTURE.md` to be edited after every code change. Update them when doing so restores their value as a project map or when the user explicitly asks.

For a task-specific change, prefer updating its spec, active ExecPlan, code comments, generated schema docs, or ADR where appropriate.

## Definition of done

A change is done when:

- the requested behavior exists,
- relevant acceptance criteria pass,
- failure behavior is explicit,
- persisted/public contracts remain compatible or have a tested migration,
- measurement/provenance guarantees are preserved,
- resource budgets are respected for the changed path,
- relevant tests and checks pass,
- no unrelated architecture or dependency change slipped into the diff,
- the final report states what changed, what was validated, and what remains uncertain.

If hardware, datasets, credentials, or platform access prevent validation, say exactly what was not validated. Never convert an untested assumption into a claim of success.
