---
name: v0-15-ios-lidar
description: "Implement or review Myndhamr v0.15 iOS support with AVFoundation, ARKit pose/intrinsics, LiDAR sceneDepth/confidence on supported devices, smoothed preview depth, KMP shared contracts, Core ML, Metal paths, and Android/iOS parity. Use for all iOS capture and baseline LiDAR integration."
---

# v0.15 — iOS and LiDAR

## Purpose

Make iOS a first-class capture platform and use hardware LiDAR as a higher-quality depth source without creating a separate incompatible scanner architecture.

## Read before editing

- `AGENTS.md` for repository-wide constraints and precedence.
- `ARCHITECTURE.md` for the relevant system contracts and mathematical conventions.
- `SUBAGENT.md` before delegating work.
- `MODEL_ROUTING.md` if model choice is being reconsidered.
- `PLANS.md` and an active ExecPlan when the work meets the multi-hour/risk threshold.
- The current task spec, tests, schemas, and actual repository state; these outrank stale reference prose.

## Scope

- AVFoundation RGB capture.
- ARKit tracking/intrinsics.
- sceneDepth plus confidence capture.
- smoothedSceneDepth for preview where appropriate.
- common DepthFrame/depth-source conversion.
- LiDAR-assisted object priors/validation.
- LiDAR room TSDF input.
- Core ML/Metal platform adapters.
- cross-platform scan-format parity.

## Explicit non-goals

- treat LiDAR depth as high-resolution replacement for RGB photogrammetry.
- use smoothed preview depth as unquestioned final measurement.
- fork scan package format into an iOS-only format.

## Required workflow

1. Inspect the current implementation, tests, schemas, recent failures, and any active plan before proposing edits.
2. Write or confirm the task contract: inputs, outputs, interfaces, coordinate/timestamp conventions where relevant, invariants, failure behavior, non-goals, tests, and acceptance criteria.
3. If the change is multi-hour, cross-cutting, numerical, schema-affecting, or research-like, create/update an ExecPlan following `PLANS.md` before implementation.
4. Separate architecture/derivation from code generation. Let the designated thinker settle ambiguous decisions before assigning bounded code to a cheaper worker.
5. Implement in the smallest validated vertical slices. Preserve raw scan evidence and keep derived artifacts/versioned metadata separate.
6. Run the narrowest relevant tests after each slice. Stop on a failed invariant instead of stacking later work on top.
7. Run end-to-end/golden/hardware/benchmark validation required by the acceptance criteria. State hardware validation that could not be executed.
8. Review the implementation against the written contract. Apply explicit findings; do not ask a worker to redesign the feature implicitly.
9. Report what changed, validation performed, remaining risks, and any deliberate departure from the reference architecture.

## Model routing

- **Planner / thinker:** GPT-6.1 Sol xhigh.
- **Implementation:** Luna high for Swift/KMP platform plumbing; GPT-6.1 Sol high for calibration/fusion/lifetime-sensitive code.
- **Reviewer:** GPT-6.1 Sol xhigh; Astra max only for unresolved novel sensor-fusion research.

Follow the two-strike escalation rule in `SUBAGENT.md`: after two failed attempts on the same underlying issue at a cheaper tier, stop and escalate rather than rephrasing the same ambiguous request.

## Engineering rules

- Prefer explicit typed contracts over hidden conventions.
- Keep measured/raw data immutable; write corrections, masks, optimized poses, confidence, and Smart interpretations as derived/versioned data.
- Never hide a failed prerequisite by fabricating plausible geometry, depth, pose, material, or texture.
- Preserve cancellation, recovery, and diagnostic information for long-running work.
- Do not introduce a new foundational dependency without checking license, platform support, determinism/reproducibility implications, and failure surface.
- Add tests for the bug/edge case before or together with the fix when reproducible.
- For numerical code, define units, frames, transform direction, tolerance, degeneracies, and a synthetic fixture with an analytically known answer where possible.
- For performance code, measure a baseline and a correctness metric before claiming improvement.

## Acceptance gate

- same scan-format contract represents Android estimated depth and iOS hardware depth with explicit source metadata.
- LiDAR sceneDepth/confidence are time/calibration aligned to RGB/pose.
- room fusion consumes LiDAR through common depth abstraction with sensor-specific weighting.
- object pipeline can use LiDAR for metric/coarse prior/outlier evidence without suppressing RGB detail.
- non-LiDAR iPhones degrade explicitly and remain supported where product requirements allow.

A milestone is not complete merely because the code compiles. All applicable acceptance items must be backed by tests, recorded measurements, deterministic fixtures, or real-device validation.

## Stop and escalate when

- the current spec contradicts repository contracts or another accepted decision;
- a coordinate/timestamp/calibration convention is ambiguous;
- a third-party API behaves differently from the documented assumption;
- tests show plausible-looking but numerically inconsistent output;
- the implementation would require silently changing raw-data semantics;
- the worker needs to make an architectural decision that was intentionally delegated away from it;
- required hardware validation is unavailable and the result cannot be proven another way.

On escalation, return evidence: failing test/fixture, relevant logs, current hypothesis, attempted fixes, and the smallest unresolved decision. Do not return only "it does not work".
