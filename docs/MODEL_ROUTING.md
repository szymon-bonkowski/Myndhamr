# MODEL_ROUTING.md — Myndhamr Codex model policy

**Research snapshot:** 2026-09-30  
**Scope:** model selection and reasoning effort for developing Myndhamr with Codex.  
**Important:** re-evaluate this file when models, Codex usage accounting, or the project's own evals materially change.

## 1. Executive policy

Use model capability where it changes correctness. Use cheaper capability where the task is already decided.

Default routing:

- **GPT-6.1 Sol `high`** — default for meaningful engineering work that still requires judgment.
- **GPT-6.1 Sol `xhigh`** — difficult architecture, 3D math, multi-stage debugging, reconstruction design, critical review.
- **GPT-6.1 Sol `medium`** — ordinary planning/review and integration when requirements are already clear.
- **GPT-6 Luna `high`** — default subagent/worker for bounded implementation, exploration, fixtures, mechanical changes, and high-volume tasks.
- **GPT-6 Luna `medium`** — boilerplate, migrations, repetitive edits, generated adapters/tests with strong acceptance criteria.
- **GPT-6 Astra `max`** — targeted escalation for genuinely research-grade scientific/numerical problems where public evidence still shows a material advantage. It is not the routine "hard coding" default.

Do not select `max` merely because a task is important. Public task-specific benchmarks show that more reasoning compute is not strictly monotonic on every workload. Choose the lightest model/effort that reliably clears the quality bar.

---

## 2. Why GPT-6.1 Sol changes the previous policy

OpenAI describes GPT-6.1 Sol as delivering near-Astra performance at lower cost for complex coding, computer use, and professional work. Its API model supports `low`, `medium`, `high`, `xhigh`, and `max` reasoning effort, has a 1.05M-token context window, and uses the same $2/M input and $10/M output list rates as GPT-6 Sol while reducing cached-input price to $0.10/M.

Artificial Analysis independently reports a very small aggregate intelligence gap between GPT-6.1 Sol max and GPT-6 Astra max, while the measured task cost differs much more.

### 2.1 Artificial Analysis aggregate snapshot

| Model / effort | AA Intelligence Index | AA cost per Index task | Output speed |
|---|---:|---:|---:|
| GPT-6.1 Sol `low` | 42 | $0.13 | 71.2 tok/s |
| GPT-6.1 Sol `medium` | 48 | $0.21 | 60.7 tok/s |
| GPT-6.1 Sol `high` | 50 | $0.32 | 65.4 tok/s |
| GPT-6.1 Sol `xhigh` | 51 | $0.39 | 63.6 tok/s |
| GPT-6.1 Sol `max` | 52 | $0.72 | 66.8 tok/s |
| GPT-6 Astra `max` | 53 | $3.26 | 57.0 tok/s |

The AA Intelligence Index version on the cited pages is v4.3.2 and aggregates ten evaluations including agentic work, coding/terminal use, science, professional document reasoning, knowledge reliability, and long-context reasoning.

Interpretation for Myndhamr:

- `high` is already extremely capable and materially cheaper in task-level token consumption than `max`.
- `xhigh` gains only one aggregate index point over `high` in the current AA snapshot, so it should be reserved for tasks where deeper reasoning is plausibly useful.
- `max` gains another point but roughly doubles AA cost/task versus `xhigh`; it should be exceptional, not the default.
- Astra's aggregate advantage over 6.1 Sol max is small, but its price/cost profile is dramatically higher.

These are benchmark observations, not guarantees about Codex quota accounting or Myndhamr tasks.

### 2.2 Artificial Analysis component-by-component comparison at max

The aggregate score hides a meaningful split. On the v4.3.2 component results read on 2026-09-30, the max-effort comparison is approximately:

| AA evaluation | GPT-6.1 Sol max | GPT-6 Astra max | Edge |
|---|---:|---:|---|
| AA-Briefcase v1.1 | 1564.2 Elo | 1568.9 Elo | Astra, very small |
| GDPval-AA v2.1 | 1575.1 Elo | 1541.9 Elo | **6.1 Sol** |
| AutomationBench-AA | 0.649 | 0.685 | Astra |
| Terminal-Bench 4.0 | 0.561 | 0.591 | Astra |
| SciCode | 0.542 | 0.565 | Astra |
| Humanity's Last Exam | 0.529 | 0.547 | Astra |
| GDP.pdf | 0.310 | 0.310 | tie at shown precision |
| CritPt | 0.317 | 0.317 | tie at shown precision |
| AA-Omniscience | 41.5 | 43.4 | Astra |
| AA-LCR v1.1 | 0.830 | 0.807 | **6.1 Sol** |

That is not "6.1 Sol is Astra but cheaper." At this snapshot Astra leads six components, 6.1 Sol leads two, and two are tied at the shown precision. The routing implication is narrower:

- **Professional long-context/spec work:** 6.1 Sol is extremely compelling; it wins GDPval-AA and AA-LCR here and is essentially tied on GDP.pdf.
- **Terminal/agent execution:** Astra keeps an edge on Terminal-Bench 4.0 and AutomationBench-AA at max, so use strong review/validation for autonomous multi-tool work rather than assuming aggregate parity.
- **Scientific code:** Astra leads SciCode in this snapshot, and GPT-6.1 Sol also regresses versus GPT-6 Sol on SciCode in the same AA report; this supports keeping an escalation path for research-grade numerical/scientific work.
- **Knowledge reliability:** 6.1 Sol improves sharply over GPT-6 Sol on AA-Omniscience and hallucination rate, but Astra still leads the max comparison.

### 2.3 Effort is task-dependent, not monotonic

Separate benchmark charts reinforce that a higher reasoning setting is not automatically better:

- DeepSWE v1.1: GPT-6.1 Sol peaks at **75.2% on `high`** in the published launch measurements, then falls at `xhigh`/`max`.
- GDP.pdf: GPT-6.1 Sol peaks at **32.0% on `high`**, versus 31.8% xhigh and 31.0% max in the same published comparison.
- AutomationBench and Terminal-Bench Science improve as effort rises toward max in the cited launch measurements.

Therefore this project chooses effort based on task shape: `high` for ordinary serious coding/spec work, `xhigh` when deeper cross-cutting reasoning is intrinsically needed, and `max` only when evidence says the extra search is useful.

### 2.4 Vendor benchmark triangulation

OpenAI's launch-era benchmark comparisons tell the same non-uniform story:

| Evaluation | GPT-6.1 Sol | GPT-6 Astra | Myndhamr interpretation |
|---|---:|---:|---|
| DeepSWE v1.1, high | 75.2% | 73.2% in the matched high comparison | strong reason to make 6.1 Sol the normal coding thinker |
| GDP.pdf, high | 32.0% | 31.0% | strong for reading specs/docs and professional artifact reasoning |
| AutomationBench 1.0.6, max | 36.1% | 41.4% | Astra retains an autonomous-workflow edge |
| OSWorld 2.0 offline, max | 71.4% | 73.5% | near, but not identical, computer-use performance |
| Terminal-Bench Science 0.1, max | 57.0% | 68.1% | meaningful Astra advantage on scientific workflows |

OpenAI's system-card addendum also shows Astra ahead on specialized scientific troubleshooting: e.g. TroubleshootingBench 63.46% vs 47.96% for 6.1 Sol and Tacit Knowledge/Troubleshooting 92.55% vs 88.50%. These are why Astra remains a **research escalation**, not why it should be used for every hard C++ task.

---

## 3. Do not route from one aggregate score

The project specifically must not reduce model choice to the Artificial Analysis Intelligence Index.

### 3.1 Professional document/spec reasoning can be non-monotonic

On Artificial Analysis GDP.pdf All-pass, the current leaderboard reports:

- GPT-6 Astra `xhigh`: **32.2%**,
- GPT-6.1 Sol `high`: **32.0%**,
- GPT-6.1 Sol `xhigh`: **31.8%**.

That is a practical reminder that `xhigh` is not guaranteed to beat `high` on every task. For reading a long design/spec and producing a precise implementation plan, start at 6.1 Sol `high` unless the task has genuine architecture uncertainty; use `xhigh` when the task benefits from deeper cross-cutting reasoning, not because the label is larger.

### 3.2 Astra still has a research/science niche

Artificial Analysis Terminal-Bench-Science 0.1 contains 70 expert-curated scientific terminal workflows and reports GPT-6 Astra `max` as the current leader at **63.3% pass@1** in its published snapshot.

OpenAI's GPT-6.1 Sol system-card addendum also shows remaining Astra advantages on specialized scientific troubleshooting. Example: TroubleshootingBench reports **47.96%** for GPT-6.1 Sol versus **63.46%** for GPT-6 Astra in the cited evaluation.

Therefore keep Astra as an escalation for work that resembles scientific research more than ordinary software engineering, such as:

- deriving and validating a novel refractive reconstruction model,
- difficult numerical optimization with no established implementation path,
- new sensor-fusion math whose correctness cannot be reduced to straightforward code translation,
- research-grade inverse rendering/material decomposition,
- a persistent scientific/geometry failure where 6.1 Sol `xhigh` has already produced an inadequate derivation.

Astra is not automatically required for Vulkan, C++, concurrency, COLMAP integration, or a complicated bug. GPT-6.1 Sol should get the first attempt on normal engineering problems.

---

## 4. Codex-specific evidence

OpenAI's current subagent documentation explicitly recommends:

- start with `gpt-6.1-sol` for demanding agents that need ambiguous multi-step planning, tool use, validation, and follow-through,
- use `gpt-6-luna` for fast, narrowly scoped, clear, repeatable, or high-volume subagent work,
- configure `model` and `model_reasoning_effort` per custom agent when needed.

The same docs use examples with Luna `high` for a read-only explorer and targeted code fixer, and GPT-6.1 Sol for review/debug roles. That closely matches Myndhamr's planner/worker split.

---

## 5. Reasoning effort policy

### GPT-6.1 Sol `low`

Use for:

- fast sanity checks,
- narrow code questions,
- tiny edits when using Sol is convenient,
- straightforward review after all decisions are already fixed.

Prefer Luna instead if the task is truly mechanical and quota efficiency is the goal.

### GPT-6.1 Sol `medium`

Use for:

- repository/module planning with clear requirements,
- normal feature review,
- API/library integration where architecture is fixed,
- comparing two bounded implementation options,
- resolving moderate bugs.

This is the default effort for v0.0-style infrastructure planning and ordinary non-critical orchestration.

### GPT-6.1 Sol `high`

**Default serious engineering effort.**

Use for:

- Camera2/ARCore/ARKit lifecycle and timing,
- concurrency and state machines,
- JNI/Swift/native interop,
- C++ integration,
- network resume/recovery logic,
- difficult test failures,
- code review for correctness,
- geometry implementation whose derivation is already specified,
- multi-module feature work with clear architecture.

### GPT-6.1 Sol `xhigh`

Use when the model must materially reason before implementation:

- coordinate-system design and transform correctness,
- sensor synchronization/calibration architecture,
- pose/depth priors and metric scale,
- TSDF design and uncertainty weighting,
- texture visibility/projection/seam strategy,
- surface-router evidence fusion,
- mirror geometry and virtual camera transforms,
- LiDAR fusion design,
- on-device reconstruction architecture,
- deep root-cause analysis that crosses several layers,
- final review of a correctness-critical numerical subsystem.

### GPT-6.1 Sol `max`

Use sparingly. Candidates:

- a very large, ambiguous, high-stakes architecture problem where `xhigh` has not been sufficient,
- a final independent reasoning pass on a foundational mathematical change,
- an extremely hard bug with poor observability after normal escalation.

Do not make `max` the default for a whole milestone. The public cost/quality curve does not justify that.

### GPT-6 Luna `medium`

Use for:

- boilerplate,
- repetitive schema bindings,
- simple migration plumbing after the migration is specified,
- routine tests/fixtures,
- documentation generated from known behavior,
- renames and mechanical refactors.

### GPT-6 Luna `high`

**Default implementation subagent once the work is fully specified.**

Use for:

- writing Kotlin/C++/Swift code from explicit interfaces and invariants,
- codebase exploration and call-path mapping,
- implementing bounded UI/network/storage work,
- adding test coverage from explicit test cases,
- applying a reviewer's concrete findings,
- repetitive device-compatibility fixes.

Do not ask Luna to decide architecture, derive unknown 3D math, or resolve a contradictory spec. Escalate the thinking first.

### GPT-6 Astra `max`

Reserve for the specialist scenarios in section 3.2. Prefer `max` only because the tasks routed to Astra are already selected for deep research reasoning. If the task becomes ordinary implementation after the derivation is done, hand it back to GPT-6.1 Sol or Luna.

---

## 6. Planner / implementer / reviewer split

The preferred cost-aware pattern is:

```text
GPT-6.1 Sol high/xhigh
  analyze + design + write exact task contract
                |
                v
GPT-6 Luna high
  implement bounded code + focused tests
                |
                v
GPT-6.1 Sol high
  review against spec + inspect failures
                |
      pass ------+------ findings
                         |
                         v
                 GPT-6 Luna high
                 apply explicit fixes
```

For a math-heavy subsystem:

```text
GPT-6.1 Sol xhigh
  derive / verify invariants
        |
        v
GPT-6.1 Sol high or Luna high
  implement depending on local reasoning required
        |
        v
GPT-6.1 Sol xhigh
  numerical + contract review
```

For a genuine research problem:

```text
GPT-6.1 Sol xhigh
  first-principles attempt + literature/code inspection
        |
   insufficient evidence
        v
GPT-6 Astra max
  specialist scientific derivation / independent critique
        |
        v
GPT-6.1 Sol high
  turn accepted derivation into implementation spec
        |
        v
Luna high / 6.1 Sol high
  implementation
```

---

## 7. Escalation rules

Escalate **task definition before model size** whenever possible.

A worker stops and escalates when:

- the spec contradicts repository behavior,
- a required invariant is absent,
- it must choose between incompatible coordinate/units conventions,
- it must invent a persisted schema change,
- tests fail for a reason outside the assigned change boundary,
- the requested algorithm is mathematically underspecified,
- a dependency/license choice is required,
- two implementation attempts fail for different unexplained reasons.

### Two-strike rule

For a Luna implementation worker:

1. first failure: inspect diagnostics and make one bounded correction if the cause is clear,
2. second failure or unclear root cause: stop and return evidence to GPT-6.1 Sol `high`,
3. if the root problem is architectural/numerical, move to 6.1 Sol `xhigh`,
4. only move to Astra `max` when the problem matches the research/science criteria.

Do not burn quota by repeatedly asking the same weakly specified prompt to a larger model.

---

## 8. Task routing matrix

| Task | Planner / thinker | Implementer | Reviewer |
|---|---|---|---|
| Rename / mechanical refactor | Luna `medium` | Luna `medium` | Luna `high` if needed |
| Fixtures / generated test data | Luna `high` | Luna `medium/high` | 6.1 Sol `medium` only if critical |
| UI from exact design | 6.1 Sol `medium` | Luna `high` | 6.1 Sol `medium` |
| KMP domain/storage | 6.1 Sol `medium/high` | Luna `high` | 6.1 Sol `high` |
| Networking/resume | 6.1 Sol `high` | Luna `high` or 6.1 Sol `high` | 6.1 Sol `high` |
| Camera lifecycle | 6.1 Sol `high` | 6.1 Sol `high` / Luna for leaf code | 6.1 Sol `high` |
| Timestamp synchronization | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| Coordinate transforms | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| COLMAP adapter | 6.1 Sol `high` | Luna `high` | 6.1 Sol `high` |
| Pose priors / BA integration | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| TSDF integration | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| Texture projection/visibility | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| Mirror plane + virtual camera | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| Smart surface ML runtime | 6.1 Sol `high` | Luna `high` | 6.1 Sol `high` |
| Surface-router logic | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh` |
| Vulkan/Metal known kernel port | 6.1 Sol `xhigh` | 6.1 Sol `high/xhigh` | 6.1 Sol `xhigh` |
| Novel GPU algorithm | 6.1 Sol `xhigh` first | 6.1 Sol `xhigh` | Astra `max` only if research uncertainty remains |
| Advanced refraction reconstruction | 6.1 Sol `xhigh` first | 6.1 Sol `high` after derivation | Astra `max` specialist review/derivation |
| LiDAR platform glue | 6.1 Sol `high` | Luna `high` | 6.1 Sol `high` |
| LiDAR uncertainty/fusion math | 6.1 Sol `xhigh` | 6.1 Sol `high` | 6.1 Sol `xhigh`; Astra `max` only for research novelty |
| Release hardening | 6.1 Sol `high` | Luna `high` | 6.1 Sol `high` |

---

## 9. Version-level routing

This table answers “which model should own the thinking for this version?” Implementation should still be delegated according to section 6.

| Version | Primary reasoning model | Default effort | Notes |
|---|---|---|---|
| v0.0 Repo foundation | GPT-6.1 Sol | `medium` | Architecture mostly defined; Luna can implement most scaffolding. |
| v0.1 Capture Recorder | GPT-6.1 Sol | `high` | Camera lifecycle/concurrency; use `xhigh` for sensor sync/calibration design. |
| v0.2 Sparse reconstruction | GPT-6.1 Sol | `xhigh` | Coordinate correctness, priors, Sim(3), COLMAP integration. |
| v0.3 Object Mesh MVP | GPT-6.1 Sol | `high` | Mostly mature backend integration; escalate numerical failures. |
| v0.4 Guided Capture | GPT-6.1 Sol | `high` | Use `xhigh` for keyframe/coverage algorithm tuning. |
| v0.5 Production Texturing | GPT-6.1 Sol | `xhigh` | Visibility/projection/photometric decisions are correctness-sensitive. |
| v0.6 Room Scan | GPT-6.1 Sol | `xhigh` | TSDF, uncertainty, coverage; Astra only for novel scientific fusion issues. |
| v0.7 Desktop Companion | GPT-6.1 Sol | `high` | Networking/recovery; implementation heavily delegable to Luna. |
| v0.8 Mobile Viewer/Preview | GPT-6.1 Sol | `high` | Rendering lifecycle + live reconstruction integration. |
| v0.9 Pro Stabilization | GPT-6.1 Sol | `high` | Determinism, auditability, confidence/reproducibility. |
| v0.10 Smart Surface v1 | GPT-6.1 Sol | `xhigh` | Multi-signal routing is custom product logic. |
| v0.11 Mirror System | GPT-6.1 Sol | `xhigh` | Reflection geometry and representation; Astra only for research fallback. |
| v0.12 Gaussian Desktop | GPT-6.1 Sol | `high` | gsplat is established; `xhigh` for coordinate/init bugs. |
| v0.13 Glass/Materials | GPT-6.1 Sol | `xhigh` | Practical thin glass in 6.1; advanced refraction can escalate to Astra `max`. |
| v0.14 On-device Reconstruction | GPT-6.1 Sol | `xhigh` | Performance architecture/GPU/memory. Astra only for novel algorithmic research. |
| v0.15 iOS + LiDAR | GPT-6.1 Sol | `xhigh` | ARKit/LiDAR timing, frames, fusion; Luna handles platform glue. |
| v0.16 Robustness/Beta | GPT-6.1 Sol | `high` | Lots of diagnosis and repetitive fixes; Luna does bounded patches. |
| v1.0 Stable Pro + Smart | GPT-6.1 Sol | `high` | Stabilization first; use `xhigh` for final critical subsystem review. |
| v1.1+ Advanced Research | GPT-6.1 Sol | `xhigh` | First choice; targeted Astra `max` for scientific/research-heavy modules. |

---

## 10. Private Myndhamr model evals

Public benchmarks should start the policy, not freeze it.

Create a small internal eval set once enough repository exists. Target 20–50 representative tasks split into categories:

1. KMP feature implementation from a strict spec.
2. C++ geometry implementation from equations/tests.
3. coordinate-system bug diagnosis.
4. Camera2/ARCore concurrency bug.
5. COLMAP adapter integration.
6. TSDF numerical bug.
7. texture projection/visibility bug.
8. mirror virtual-camera derivation/review.
9. LiDAR alignment/fusion diagnosis.
10. Vulkan/Metal kernel port.
11. large refactor with strict non-goals.
12. PR review for hidden correctness regressions.

For each run, record:

- model and effort,
- task completion/pass status,
- tests passed,
- human correction count,
- wall-clock time,
- Codex usage/credits if visible,
- number of tool calls/retries,
- diff size and unrelated-change rate.

Choose routing based on **successful task cost**, not token price or benchmark rank alone.

Re-run a representative subset after major model releases.

---

## 11. Prompting implications

Stronger models do not benefit from a larger pile of vague instructions. Keep context lean:

- root `AGENTS.md` contains durable repo rules,
- the relevant skill contains milestone/workstream procedure,
- the active spec contains exact behavior,
- an ExecPlan contains the multi-hour execution state,
- `ARCHITECTURE.md` is loaded selectively for required invariants.

Before assigning implementation to Luna, explicitly provide:

- objective,
- allowed files/modules,
- interface/signature expectations,
- invariants,
- error behavior,
- acceptance tests,
- validation commands,
- non-goals.

If the prompt still requires the worker to decide “what the system should do,” the task is not ready for the cheap worker.

---

## 12. Source notes

Research checked on 2026-09-30:

### OpenAI

- GPT-6.1 Sol model page: https://developers.openai.com/api/docs/models/gpt-6.1-sol
- Model selection: https://developers.openai.com/api/docs/guides/model-selection
- GPT-6 model guidance: https://developers.openai.com/api/docs/guides/latest-model
- Codex subagents: https://developers.openai.com/codex/subagents
- Work and Codex model availability: https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex
- GPT-6.1 Sol system-card addendum: https://deploymentsafety.openai.com/gpt-6-1-sol/respecting-auto-review

### Artificial Analysis

- GPT-6.1 Sol max: https://artificialanalysis.ai/models/gpt-6-1-sol
- GPT-6.1 Sol xhigh: https://artificialanalysis.ai/models/gpt-6-1-sol-xhigh
- GPT-6.1 Sol high: https://artificialanalysis.ai/models/gpt-6-1-sol-high
- GPT-6.1 Sol medium: https://artificialanalysis.ai/models/gpt-6-1-sol-medium
- GPT-6.1 Sol low: https://artificialanalysis.ai/models/gpt-6-1-sol-low
- GPT-6 Astra max: https://artificialanalysis.ai/models/gpt-6-astra
- GDP.pdf leaderboard: https://artificialanalysis.ai/evaluations/gdp-pdf
- Terminal-Bench-Science: https://artificialanalysis.ai/evaluations/terminal-bench-science

Benchmark values are snapshots. Artificial Analysis can revise harnesses, scores, providers, and index versions. Treat this file's numbers as dated evidence, not permanent facts.
