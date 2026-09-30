---
name: v0-8-mobile-viewer
description: "Implement or review Myndhamr v0.8 mobile 3D viewer and preview reconstruction using Filament, live point/mesh data, low-resolution fusion, confidence overlays, and model inspection. Use for capture-time preview, render lifecycle, and quality visualization on mobile."
---

# v0.8 — Mobile Viewer and Preview Reconstruction

## Purpose

Give immediate feedback about what the scanner has measured without pretending the preview is the final high-quality reconstruction.

## Read before editing

- `AGENTS.md` for repository-wide constraints and precedence.
- `ARCHITECTURE.md` for the relevant system contracts and mathematical conventions.
- `SUBAGENT.md` before delegating work.
- `MODEL_ROUTING.md` if model choice is being reconsidered.
- `PLANS.md` and an active ExecPlan when the work meets the multi-hour/risk threshold.
- The current task spec, tests, schemas, and actual repository state; these outrank stale reference prose.

## Scope

- Filament viewer lifecycle.
- live point cloud/coarse mesh stream.
- low-resolution depth fusion preview.
- confidence/coverage overlays.
- camera/model inspection controls.
- resource throttling and cancellation.

## Explicit non-goals

- run the full desktop pipeline on every phone.
- hide missing data with invented geometry.
- make preview representation a persistent truth source.

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

- **Planner / thinker:** GPT-6.1 Sol high.
- **Implementation:** GPT-6 Luna high for UI/viewer glue; GPT-6.1 Sol high for render/native synchronization.
- **Reviewer:** GPT-6.1 Sol high.

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

- preview updates without blocking capture pipeline.
- renderer resources are released across lifecycle changes.
- confidence/coverage overlays correspond to stored measurement state.
- quality can be reduced under thermal/memory pressure without altering raw data.
- viewer opens final GLB in correct scale/orientation.

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
