# PLANS.md — Myndhamr execution plans

This file defines how to plan **large, multi-hour engineering work** in Myndhamr. It is intentionally lightweight for normal work and intentionally strict for risky work.

An execution plan is called an **ExecPlan** in this repository. An ExecPlan is a living implementation document that a competent coding agent can follow from an unfamiliar checkout to a verified result without relying on hidden conversation history.

Read `AGENTS.md` before using this file. Read `SUBAGENT.md` when delegating any milestone to subagents.

---

## 1. When an ExecPlan is required

Create an ExecPlan before implementation when **any** of these are true:

- expected work is roughly 2+ focused engineering hours,
- the task crosses 3+ modules or process boundaries,
- a persistent schema or wire protocol changes,
- a coordinate-system convention or transform contract changes,
- a new numerical/reconstruction algorithm is introduced,
- GPU compute or a performance-critical native path is introduced,
- storage migration may be destructive or compatibility-sensitive,
- a major dependency/backend is added, replaced, or removed,
- the change spans Android/iOS/desktop/native workers,
- the task contains material research uncertainty,
- a failure could silently corrupt scan data or produce plausible-but-wrong geometry,
- the user explicitly asks for an implementation plan.

Examples that require an ExecPlan:
- AR pose prior integration into SfM,
- RGB/depth/IMU timestamp alignment,
- TSDF room fusion,
- production texture projection/seams,
- mirror reconstruction,
- on-device dense reconstruction,
- LiDAR fusion,
- scan-format v2 migration,
- changing desktop worker IPC.

---

## 2. When an ExecPlan is not required

Do not create process overhead for:
- tiny UI changes,
- isolated bug fixes with known root cause,
- simple renames,
- test-only changes,
- narrow dependency updates with no behavioral impact,
- mechanical schema bindings after a migration contract is already approved,
- one-file cleanup/refactors with objective tests.

A task may begin small and become plan-worthy. If uncertainty or scope expands, stop and create the ExecPlan before continuing.

---

## 3. Location and lifecycle

Store active plans at:

```text
plans/active/YYYY-MM-DD-<slug>.md
```

After completion, move them to:

```text
plans/completed/YYYY-MM-DD-<slug>.md
```

Abandoned experiments may be moved to:

```text
plans/abandoned/YYYY-MM-DD-<slug>.md
```

Do not use ExecPlans as permanent architecture documentation. They record how a specific large change is executed. Durable architectural decisions belong in `docs/adr/` when they meet the threshold in `ADR.md`.

---

## 4. Self-contained requirement

An ExecPlan must be understandable to an engineer/agent who has:
- the current repository,
- the ExecPlan file,
- the repository guidance files (`AGENTS.md`, `ARCHITECTURE.md`, `SUBAGENT.md`),
- no memory of the conversation in which the plan was created.

Do not write:

```text
Implement the approach we discussed earlier.
```

Write the actual approach, including the relevant contracts and reasoning.

If the plan depends on an external API/library behavior, identify the exact API and the relevant version/assumption. If correctness depends on a source, link it.

---

## 5. Plans describe outcomes, not merely code edits

Start with the observable result.

Weak:

```text
Modify FooManager and add BarAdapter.
```

Strong:

```text
After this plan, a scan recorded with ARCore pose priors can be imported by the desktop worker, reconstructed by COLMAP, aligned to the AR metric frame, and verified against a scale fixture within the specified error tolerance.
```

The file/module changes follow from the outcome; they are not the purpose of the plan.

---

## 6. Mandatory ExecPlan structure

Use the following structure unless a section genuinely does not apply.

```markdown
# [Outcome-oriented title]

## Status
Draft | Active | Blocked | Completed | Abandoned

## Purpose and user-visible outcome
What capability exists after this change? Why does it matter?

## Scope
What is included.

## Non-goals
What must not be redesigned or expanded in this plan.

## Current state
What the repository does now. Name relevant files/modules/symbols.

## Contracts and invariants
Data contracts, APIs, coordinate conventions, ownership/lifetime rules,
state transitions, error behavior, compatibility constraints.

## Mathematics / algorithm
Only where relevant. Define symbols, frames, units, equations,
assumptions, numerical tolerances, and degeneracies.

## Implementation map
Which modules change and how responsibilities are split.

## Milestones
### M1 — [executable outcome]
Work:
- ...
Acceptance:
- ...
Validation:
- exact commands/tests/fixtures/benchmarks

### M2 — ...
...

## Test and validation matrix
Unit, integration, golden, hardware, benchmark and manual validation.

## Performance / resource budgets
Latency, memory, storage, bandwidth, thermals, GPU/CPU constraints if relevant.

## Migration and compatibility
Schema/protocol/cache/project compatibility and rollback strategy.

## Failure handling
Known failure modes and how they are surfaced or recovered.

## Risks and mitigations
Concrete risks; avoid generic prose.

## Subagent/model plan
Who thinks, who implements, who reviews; model + reasoning effort.

## Progress
- [ ] M1 ...
- [ ] M2 ...

## Decisions made during implementation
Append concise decisions that materially change the plan.

## Discoveries
Unexpected facts learned from code/tests/hardware.

## Final validation
Commands, datasets, devices, expected results.

## Outcome
Fill on completion: what shipped, deviations, remaining work.
```

---

## 7. Milestone sizing

Each milestone should be small enough that one focused agent loop can:
1. inspect the relevant state,
2. implement a coherent slice,
3. run validation,
4. either pass or stop with a specific defect.

A milestone is too large if its validation says only "run all tests at the end".

Good milestone boundaries in Myndhamr tend to be contracts:
- frame schema and migration,
- timestamp synchronizer,
- pose conversion + golden transforms,
- pair selector,
- sparse registration,
- metric alignment,
- TSDF integrator,
- mesh extraction,
- texture visibility,
- seam blending,
- transfer resume state,
- renderer integration.

---

## 8. Every milestone needs acceptance and validation

Do not accept vague completion statements such as:
- "works",
- "looks good",
- "implemented",
- "tests pass" without naming the tests.

Examples:

### Numerical feature

```text
Acceptance:
- world->camera->world round-trip max translation error < 1e-6 m in fixture tests,
- quaternion orientation error < configured epsilon,
- handedness and image-axis convention documented and covered by goldens.

Validation:
- ./gradlew :shared:scan-format:test
- ctest --test-dir build/native -R transform
- tools/validate_fixture ...
```

### Transfer feature

```text
Acceptance:
- a 20 GB synthetic scan resumes after disconnect without re-uploading verified chunks,
- corrupted chunk is detected before commit,
- final manifest hash matches sender.
```

### Device feature

```text
Acceptance:
- 15-minute capture on supported device without crash,
- timestamps monotonic in normalized clock domain,
- no unbounded queue growth,
- missing depth is represented explicitly rather than synthesized.
```

---

## 9. Stop-and-fix rule

When a milestone fails its acceptance criteria:
- do not continue stacking later milestones on top,
- record the failure and evidence,
- identify whether the contract, implementation, fixture, or assumption is wrong,
- fix or revise the milestone,
- rerun validation,
- continue only after the relevant gate passes.

This prevents long agent runs from turning one subtle geometry/timing bug into a large pile of dependent code.

---

## 10. Research uncertainty

For experimental work, the plan must separate **research questions** from **implementation commitments**.

Example:

```markdown
## Research gate R1 — mirror classification signal
Question: Does depth inconsistency + planar geometry provide sufficient precision
without a dedicated semantic class on our mirror dataset?

Experiment:
- dataset: fixtures/mirrors/v1
- compare A/B/C signal sets
- metrics: precision, recall, false-positive rate on dark TVs/windows

Decision threshold:
- if precision >= X and recall >= Y, continue with deterministic route;
- otherwise add semantic segmentation signal.
```

Do not implement a production subsystem before the research gate that determines its architecture has produced evidence.

---

## 11. Mathematical work

For geometry/numerics, an ExecPlan must define:
- coordinate frames,
- handedness,
- units,
- matrix/vector convention,
- transform direction,
- timestamp clock domains,
- depth convention,
- camera projection model,
- uncertainty/confidence meaning,
- degenerate cases,
- tolerance used by tests.

Never allow implementation to infer these from variable names.

When an equation is implemented, name the source/derivation and add a synthetic fixture whose correct answer is analytically known where possible.

---

## 12. Data/schema/protocol work

A plan that changes `.scan3d`, Protobuf, DB schema or network protocol must include:
- versioning strategy,
- forward/backward compatibility decision,
- unknown-field behavior,
- migration path,
- partial/corrupt data behavior,
- stable identifiers,
- hashing/checksums where relevant,
- recovery/rollback,
- test fixtures from old and new versions.

Raw captured evidence must not be silently rewritten by Smart processing.

---

## 13. Performance work

A performance plan must begin with a measurement.

Include:
- representative device/desktop hardware,
- representative scan sizes,
- baseline wall time,
- peak RAM/VRAM,
- throughput,
- thermal behavior for mobile,
- profiling method,
- target budget,
- correctness metric that may not regress.

Do not merge an optimization whose only evidence is "seems faster".

---

## 14. Subagent/model section

Follow `SUBAGENT.md`.

A typical plan should say, for example:

```markdown
## Subagent/model plan

- Planner: GPT-6.1 Sol xhigh — owns transform contract and milestone design.
- Explorer: GPT-6 Luna high — maps existing pose/depth call paths; read-only.
- Worker A: GPT-6 Luna high — implements schema/plumbing after interfaces are fixed.
- Worker B: GPT-6.1 Sol high — implements numerical alignment routine.
- Reviewer: GPT-6.1 Sol xhigh — checks transform math and golden tests.
- Astra: not planned; escalate only if the numerical model itself remains unresolved.
```

Model selection must follow task characteristics, not prestige.

---

## 15. Decision logging inside a plan

Plans are living documents while active. Update them when implementation discovers a fact that changes the route.

Use entries like:

```markdown
## Decisions made during implementation

### 2026-10-04 — Normalize ARCore and ARKit into a right-handed internal world frame
Reason: ...
Consequences: ...
Supersedes: earlier draft assumption in M2.
```

Do not turn the section into a minute-by-minute diary. Record only decisions that help a future implementer understand why the final route differs from the initial plan.

If the decision is durable across many future features, consider an ADR after the work stabilizes.

---

## 16. Progress discipline

Check boxes reflect **validated outcomes**, not code written.

Wrong:

```text
[x] Added TsdfIntegrator.cpp
```

Better:

```text
[x] M3 — deterministic synthetic depth sequence fuses into expected plane and cube meshes within tolerance
```

Keep the plan readable. If a milestone produces dozens of microtasks, track those in issue/task tooling or a local checklist, not as permanent noise in the ExecPlan.

---

## 17. Completion

A plan can be marked completed only when:
- all required milestone acceptance criteria pass,
- the final validation section is executed as far as the environment permits,
- unavailable hardware validation is explicitly listed rather than implied complete,
- docs/specs that define public contracts are updated,
- migrations and rollback/recovery paths are verified where applicable,
- `Outcome` describes deviations from the original plan,
- remaining non-blocking work is captured separately.

Then move the file from `plans/active/` to `plans/completed/`.

---

## 18. Relationship to the reference blueprint

`3d_scanner_architecture_roadmap.md` and `ARCHITECTURE.md` are reference maps, not a demand to constantly synchronize prose with every experiment.

For a concrete implementation task, precedence is:
1. explicit current task/spec,
2. validated current code/contracts/tests,
3. active ExecPlan,
4. accepted ADRs that apply,
5. `ARCHITECTURE.md` / project blueprint as the default intended direction.

If a plan intentionally departs from the reference architecture, state the departure. Do not automatically "fix" working code back to the blueprint merely because the blueprint is older.

---

## 19. Reference

This policy is adapted to Myndhamr from OpenAI's Codex ExecPlans guidance, which recommends plans for complex features/significant refactors and emphasizes self-contained, outcome-oriented plans with validation:

https://developers.openai.com/cookbook/articles/codex_exec_plans
