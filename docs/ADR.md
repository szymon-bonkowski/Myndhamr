# ADR.md — Myndhamr architecture decision records

Architecture Decision Records are **optional, lightweight records for durable decisions**. They are not a gate in front of normal development and they are not a requirement to keep the reference blueprint synchronized with every experiment.

Use an ADR only when a decision is expensive to reverse, affects many future tasks, or would otherwise be repeatedly re-litigated.

---

## 1. When to create an ADR

Create an ADR for decisions such as:
- the canonical internal coordinate/handedness convention,
- the `.scan3d` format/versioning strategy,
- raw-data immutability/provenance rules,
- process boundaries between app and reconstruction workers,
- FFI/IPC architecture,
- changing the primary SfM/MVS/TSDF backend,
- introducing a dependency with important licensing implications,
- security/pairing/trust model for phone↔desktop,
- choosing a long-lived renderer/scene representation boundary,
- adopting a cross-platform database/storage abstraction,
- a compatibility policy that constrains future releases,
- replacing a foundational architecture decision with a new one.

An ADR is especially useful when the answer to "why is it built this way?" would otherwise require reconstructing months of context.

---

## 2. When not to create an ADR

Do not create ADRs for:
- routine refactors,
- implementation detail inside one module,
- temporary experiments,
- tuning thresholds,
- ordinary library version bumps,
- bug fixes,
- UI polish,
- a one-off benchmark parameter,
- decisions already local and fully explained by code/tests,
- every deviation from `ARCHITECTURE.md`.

For multi-hour implementation details use an ExecPlan (`PLANS.md`). For temporary research findings, keep them in the relevant plan/spec/benchmark notes.

---

## 3. Location and naming

Store accepted/proposed ADRs at:

```text
docs/adr/
├── 0001-internal-coordinate-convention.md
├── 0002-scan-package-versioning.md
└── 0003-desktop-worker-process-boundary.md
```

Use zero-padded monotonically increasing IDs. Do not renumber historical ADRs.

---

## 4. Status values

Use one of:
- **Proposed** — under active consideration; not yet the durable default.
- **Accepted** — current architectural decision.
- **Superseded** — replaced by a newer ADR; keep for history.
- **Rejected** — evaluated but intentionally not adopted.

An accepted ADR can be superseded without rewriting history. Add a link in both records.

---

## 5. Required template

```markdown
# ADR-NNNN: [decision title]

- Status: Proposed | Accepted | Superseded | Rejected
- Date: YYYY-MM-DD
- Owners: [optional]
- Supersedes: ADR-NNNN | none
- Superseded by: ADR-NNNN | none

## Context
What durable problem or constraint requires a decision? Include facts,
not a transcript of the discussion.

## Decision
State the chosen rule precisely enough that future work can apply it.

## Rationale
Why this option is preferred for Myndhamr.

## Alternatives considered
### Option A
Benefits / costs / reason not chosen.

### Option B
...

## Consequences
### Positive
- ...

### Negative / constraints introduced
- ...

## Compatibility and migration
What existing data/code/users are affected? How is migration handled?

## Validation / evidence
Benchmarks, prototypes, docs, tests, licensing evidence, or measurements
that support the decision.

## Revisit when
Concrete conditions that should cause this decision to be reconsidered.
```

Keep ADRs concise. Prefer a precise two-page record over an essay.

---

## 6. Decision quality rules

An ADR must distinguish:
- **facts** — measured behavior, API constraints, license terms,
- **assumptions** — expected but unverified behavior,
- **trade-offs** — costs accepted intentionally,
- **decision** — the actual durable rule.

If the decision depends on benchmark results, record:
- benchmark version/date,
- dataset/workload,
- hardware,
- configuration,
- measurement uncertainty where relevant.

For external APIs, link authoritative documentation rather than relying only on memory.

---

## 7. Important Myndhamr-specific ADR candidates

Do not create these automatically merely because they are listed. Create them once the corresponding choice is stable enough to be durable.

Likely early ADRs:
1. **Internal coordinate convention** — handedness, axes, units, transform naming/direction.
2. **Scan package/provenance** — raw immutable assets + derived products + versioning.
3. **Desktop worker boundary** — reconstruction runs out-of-process from UI/orchestrator.
4. **Permissive production dependency policy** — e.g. why AGPL OpenMVS is not linked into a closed production app.
5. **Phone↔desktop trust model** — pairing identity, TLS, reconnect/resume semantics.
6. **Depth-source abstraction** — hardware LiDAR/ToF, AR estimated depth, and confidence represented under one contract without pretending they are statistically equivalent.

Later candidates:
- primary mobile GPU compute backend boundaries,
- portable mirror representation/fallback,
- 3DGS scene/export strategy,
- compatibility policy for v1 scan projects.

---

## 8. Relationship to ExecPlans

An ExecPlan answers:
> How will we safely implement this large change now?

An ADR answers:
> What durable architectural decision should future work continue to respect, and why?

A large feature may have an ExecPlan and produce zero ADRs. It may produce one ADR if it stabilizes a long-lived decision.

Do not block implementation on an ADR when experimentation is still needed. Research first, decide when evidence is sufficient, then record the durable result.

---

## 9. Relationship to the reference architecture

`ARCHITECTURE.md` and `3d_scanner_architecture_roadmap.md` are durable maps/instructions, not continuously synchronized source-of-truth files.

If an accepted ADR conflicts with an older reference statement, the ADR is evidence of the newer deliberate choice for the scope it covers. The reference document can be refreshed later during a cleanup pass; it does not need to be immediately rewritten before code can proceed.

---

## 10. Agent behavior

When Codex encounters an ADR:
- follow accepted ADRs relevant to the current scope,
- do not silently supersede them during unrelated work,
- if implementation evidence shows an ADR is no longer viable, report the conflict clearly,
- propose a replacement ADR only if the decision meets the durability threshold above,
- do not create process paperwork for ordinary code changes.
