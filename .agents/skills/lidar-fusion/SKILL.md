---
name: lidar-fusion
description: "Design, implement, calibrate, or review Myndhamr hardware LiDAR fusion for iOS. Use for sceneDepth/confidence alignment, sensor-specific noise/weighting, RGB+LiDAR object priors, room TSDF integration, depth validity, and comparisons against AR-estimated depth."
---

# LiDAR Depth Fusion

## Purpose

Use LiDAR as measured metric evidence with an explicit uncertainty model, never as a generic depth map assumed equivalent to every other source.

## Read before editing

- `AGENTS.md` for repository-wide constraints and precedence.
- `ARCHITECTURE.md` for the relevant system contracts and mathematical conventions.
- `SUBAGENT.md` before delegating work.
- `MODEL_ROUTING.md` if model choice is being reconsidered.
- `PLANS.md` and an active ExecPlan when the work meets the multi-hour/risk threshold.
- The current task spec, tests, schemas, and actual repository state; these outrank stale reference prose.

## Scope

- LiDAR depth/confidence ingestion.
- RGB/depth calibration and timestamp alignment.
- depth-source/noise metadata.
- range/angle/confidence weighting.
- object geometry/scale prior hooks.
- room TSDF weighting.
- hardware fixtures and error characterization.

## Explicit non-goals

- assume confidence enum equals calibrated probability.
- upsample LiDAR and call it RGB-level geometric detail.
- let reflective/glass failure modes pass without confidence/outlier handling.

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
- **Implementation:** GPT-6.1 Sol high.
- **Reviewer:** GPT-6.1 Sol xhigh.
- **Research escalation:** GPT-6 Astra max only if new fusion mathematics is required beyond established methods.

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

- LiDAR and RGB rays align on controlled planar fixtures within declared tolerance.
- source-specific weighting is explicit and separately tunable from AR-estimated depth.
- invalid/low-confidence LiDAR samples do not create phantom geometry.
- room/object paths consume the same common depth contract.
- evaluation reports error vs distance/material/angle on available hardware fixtures.

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
