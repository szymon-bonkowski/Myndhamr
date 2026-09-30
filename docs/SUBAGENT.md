# SUBAGENT.md — Myndhamr model delegation policy

**Research snapshot:** 2026-09-30  
**Purpose:** spend reasoning budget where judgment changes correctness, and spend cheap tokens where the solution is already decided.

This file defines how Codex work should be split between a **thinking agent** and one or more **implementation agents**. It is deliberately stricter than a generic "use a cheaper model for easy work" rule because Myndhamr contains geometry, sensor timing, native interop, GPU work, and long-running reconstruction pipelines where a superficially plausible implementation can be wrong.

Read `AGENTS.md` first. For work expected to take multiple hours, also read `PLANS.md`. For current model evidence and benchmark notes, read `MODEL_ROUTING.md`.

---

## 1. Core rule

Use the smartest necessary model to decide **what must be true**. Use the cheapest model that can reliably implement **what has already been decided**.

The preferred flow is:

```text
GPT-6.1 Sol high/xhigh
    inspect -> reason -> define contracts -> plan -> acceptance tests
                         |
                         v
GPT-6 Luna high
    implement bounded code exactly to the contract
                         |
                         v
GPT-6.1 Sol high
    review against contracts, invariants and tests
                         |
              pass ------+------ findings
                                  |
                                  v
                          GPT-6 Luna high
                          apply explicit fixes
```

Do **not** hand an ambiguous architectural problem to Luna and hope that a good implementation emerges. Cheap implementation only works when the expensive thinking has produced an executable contract.

---

## 2. Default model roles

### 2.1 Architect / planner

**Default:** `gpt-6.1-sol`, reasoning `high`  
**Upgrade to:** `gpt-6.1-sol`, reasoning `xhigh`

Use `high` for:
- feature decomposition,
- repository/module design under established architecture,
- API and interface design,
- test/validation strategy,
- concurrency/state-machine design,
- difficult integration planning,
- review of a multi-module change.

Use `xhigh` when correctness depends on non-trivial reasoning before code exists:
- coordinate systems and transforms,
- RGB/depth/IMU synchronization architecture,
- calibration and uncertainty propagation,
- pose/depth priors,
- metric scale alignment,
- TSDF formulation and weighting,
- visibility and texture projection,
- mirror geometry,
- LiDAR fusion,
- on-device reconstruction architecture,
- deep cross-layer root-cause analysis.

Do not use `max` by default. Escalate to it only after `xhigh` has a concrete reason to be insufficient.

### 2.2 Explorer / repository mapper

**Default:** `gpt-6-luna`, reasoning `high`

Use for read-heavy, bounded exploration:
- locate relevant modules and call paths,
- map ownership boundaries,
- find tests and fixtures,
- inventory symbols/dependencies,
- compare current code against a written contract,
- produce a concise evidence report without editing.

An explorer may identify uncertainty but must not silently resolve architecture. Return evidence and questions to the planner.

### 2.3 Implementation worker

**Default:** `gpt-6-luna`, reasoning `high`

Use when the task contract already contains:
- exact objective,
- allowed files or subsystem,
- interfaces/signatures or data contracts,
- invariants,
- non-goals,
- error behavior,
- required tests,
- validation commands,
- explicit acceptance criteria.

Good Luna work:
- Kotlin/KMP UI and state plumbing,
- DTO/Protobuf bindings,
- storage adapters,
- networking plumbing after protocol is fixed,
- test fixtures and regression tests,
- bounded refactors,
- CLI glue,
- wrappers around existing libraries,
- applying explicit review findings.

Use `gpt-6-luna medium` only for highly mechanical work: boilerplate, repetitive bindings, renames, fixtures, straightforward migrations, generated adapters, and documentation derived from already-known behavior.

### 2.4 Complex implementation worker

**Default:** `gpt-6.1-sol`, reasoning `high`

Do not force Luna onto implementation whose *local coding decisions are themselves part of the hard problem*. Examples:
- lock-free or subtle concurrent code,
- timestamp synchronization code with several clocks,
- JNI/Swift/C++ ownership and lifetime boundaries,
- camera/pose transform implementation,
- numerical optimization plumbing,
- performance-sensitive native C++,
- Vulkan/Metal compute kernels,
- renderer synchronization,
- recovery logic with complex partial state.

The planner may still provide a detailed contract, but the implementation requires enough local reasoning that a stronger model is justified.

### 2.5 Reviewer

**Default:** `gpt-6.1-sol`, reasoning `high`  
**Correctness-critical numerical subsystems:** `xhigh`

The reviewer must not rewrite the feature from scratch. Review the implementation against:
1. the task spec / ExecPlan,
2. `ARCHITECTURE.md` unless superseded by a newer explicit decision,
3. invariants,
4. tests and golden data,
5. failure behavior,
6. performance/resource constraints,
7. security and data-integrity requirements.

Review findings must be concrete: file/symbol, violated contract, evidence, expected correction, and regression test where applicable.

### 2.6 Debugger

Start with `gpt-6.1-sol high`.

Escalate to `xhigh` when:
- symptoms cross multiple subsystems,
- coordinate/timing/numerical correctness is involved,
- an earlier hypothesis failed,
- reproduction is intermittent,
- the fix could mask the root cause.

For a bounded bug with a known root cause, hand the actual patch back to Luna high.

### 2.7 Research scientist

**Default first attempt:** `gpt-6.1-sol xhigh`  
**Specialist escalation:** `gpt-6-astra max`

Reserve Astra for genuinely research-like tasks where public evidence still indicates an advantage in scientific troubleshooting and where an established implementation path does not exist. Examples:
- advanced refractive reconstruction,
- novel inverse-rendering/material decomposition,
- new sensor-fusion mathematics,
- research-grade numerical behavior that remains unresolved after a 6.1 Sol xhigh derivation,
- scientific algorithm selection where correctness depends on literature-style reasoning rather than ordinary engineering.

Once the derivation and acceptance tests are fixed, move implementation back to GPT-6.1 Sol high or Luna high where possible.

---

## 3. The handoff contract

Never delegate implementation with a prompt equivalent to "implement feature X" if X requires architectural judgment.

A worker handoff must contain this contract:

```markdown
# Task
[one concrete outcome]

## Context
[why this task exists; relevant modules]

## Inputs
[types, files, APIs, preconditions]

## Required output
[observable result]

## Interfaces
[exact signatures/schema/protocol if fixed]

## Invariants
[conditions that must remain true]

## Error behavior
[what to reject, retry, surface, or preserve]

## Allowed scope
[files/modules the worker may change]

## Non-goals
[things explicitly not to redesign]

## Tests to add/change
[exact cases]

## Validation
[commands, benchmark/golden-data checks]

## Acceptance criteria
[objective pass/fail conditions]
```

If any required field is unknown and affects architecture, return the task to the planner instead of inventing the answer inside the worker.

---

## 4. Delegation gates

### Gate A — Is the decision already made?

If **no**, use GPT-6.1 Sol high/xhigh first.  
If **yes**, continue.

### Gate B — Can the implementation be objectively verified?

If **no**, strengthen the spec/tests before delegating.  
If **yes**, continue.

### Gate C — Does correct coding require non-trivial local reasoning?

If **yes**, use GPT-6.1 Sol high for implementation.  
If **no**, use Luna high.

### Gate D — Is the work mechanical and repetitive?

If **yes**, Luna medium is sufficient.

---

## 5. Two-strike escalation rule

A cheap worker is not allowed to burn unlimited time.

1. First failed attempt: reviewer explains the concrete defect and sends one explicit correction pass.
2. Second failed attempt on the same underlying issue: stop delegating that issue to the same capability tier.
3. Escalate to GPT-6.1 Sol high.
4. Escalate to xhigh if the root problem is architectural, numerical, geometric, or cross-cutting.
5. Escalate to Astra max only for the research-scientist class described above.

Do not repeatedly rephrase the same ambiguous prompt to Luna.

---

## 6. Parallel subagents

Parallelize only work that is truly independent.

Safe examples:
- multiple read-only explorers mapping different subsystems,
- one worker writing tests while another changes a non-overlapping implementation module after the contract is fixed,
- separate benchmark/data analysis and documentation work,
- independent platform adapters behind an already-fixed interface.

Avoid:
- two writers editing the same contract/schema,
- two agents independently deciding coordinate conventions,
- overlapping refactors,
- concurrent edits to generated and source-of-truth representations,
- parallel architectural decisions whose results must agree.

When parallel writes are useful, use separate worktrees/branches and give every agent explicit ownership boundaries.

---

## 7. Validation ownership

A worker does not finish when code compiles.

The implementation worker must run the validation it can run. The reviewer must independently inspect:
- test coverage of new behavior,
- golden/reference output where applicable,
- invariant preservation,
- failure paths,
- serialization compatibility,
- resource/performance regressions relevant to the task.

For camera, depth, GPU and device-specific work that cannot be fully validated in the current environment, state exactly what remains to be run on real hardware. Do not replace unavailable validation with confidence language.

---

## 8. Model-budget policy

The goal is **quality per unit of scarce reasoning**, not the cheapest possible request.

Prefer this ordering:
1. Luna medium for mechanical work.
2. Luna high for bounded implementation/exploration.
3. GPT-6.1 Sol medium for ordinary planning/review.
4. GPT-6.1 Sol high for serious engineering.
5. GPT-6.1 Sol xhigh for architecture/geometry/numerics.
6. GPT-6.1 Sol max only exceptionally.
7. GPT-6 Astra max only for selected research problems with evidence that the stronger scientific reasoning is useful.

The release of GPT-6.1 Sol materially changes the old policy: Astra is no longer the routine "hard task" model for Myndhamr. See `MODEL_ROUTING.md` for the benchmark rationale.

---

## 9. Suggested custom Codex agent profiles

These are templates, not mandatory filenames. Keep actual `.codex/agents/*.toml` settings in sync with whatever model IDs and fields the installed Codex version accepts.

### `planner.toml`

```toml
model = "gpt-6.1-sol"
model_reasoning_effort = "high"
```

For geometry-heavy tasks, temporarily use `xhigh` rather than maintaining a separate universal planner.

### `explorer.toml`

```toml
model = "gpt-6-luna"
model_reasoning_effort = "high"
```

Treat as read-only by instruction.

### `worker.toml`

```toml
model = "gpt-6-luna"
model_reasoning_effort = "high"
```

Use only after a handoff contract exists.

### `reviewer.toml`

```toml
model = "gpt-6.1-sol"
model_reasoning_effort = "high"
```

Promote to `xhigh` for correctness-critical math/geometry.

### `researcher.toml`

Start with:

```toml
model = "gpt-6.1-sol"
model_reasoning_effort = "xhigh"
```

Use Astra max only as an explicit escalation, not as the permanent default.

---

## 10. Milestone-specific overrides

Each version skill in `.agents/skills/` may narrow this policy. A more specific skill may say, for example, that v0.2 pose/scale work requires a 6.1 Sol xhigh planner and 6.1 Sol high implementer even though most bounded implementation normally goes to Luna.

Specific overrides must explain **why the task requires more reasoning**. They must not upgrade models just because a milestone is important.

---

## 11. Private Myndhamr evals supersede public intuition

Public benchmarks guide initial routing; they do not prove what is best for this repository.

Build a small private eval set containing real tasks such as:
- correct camera/world transform derivation,
- timestamp-domain bug diagnosis,
- pose-prior integration review,
- texture visibility bug,
- TSDF weighting bug,
- resumable-transfer corruption edge case,
- mirror plane/reflected-camera math,
- Vulkan resource-lifetime bug,
- KMP/native boundary task.

For each candidate model/effort, record:
- pass/fail against a hidden rubric,
- number of repair iterations,
- wall-clock time,
- usage/credits if observable,
- regression rate,
- reviewer severity.

Change this routing policy when repo-specific evidence justifies it.

---

## 12. References checked for this policy

- OpenAI GPT-6.1 Sol model documentation: https://developers.openai.com/api/docs/models/gpt-6.1-sol
- OpenAI Codex subagents documentation: https://developers.openai.com/codex/subagents
- OpenAI Codex AGENTS guidance: https://developers.openai.com/codex/guides/agents-md
- OpenAI ExecPlans cookbook: https://developers.openai.com/cookbook/articles/codex_exec_plans
- Artificial Analysis model/evaluation pages: https://artificialanalysis.ai/
- Detailed benchmark snapshot and routing rationale: `MODEL_ROUTING.md`
