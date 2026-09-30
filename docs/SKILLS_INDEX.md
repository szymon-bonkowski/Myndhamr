# SKILLS_INDEX.md — Myndhamr repository skills

Repository skills live in `.agents/skills/<skill-name>/SKILL.md`. Each skill is intentionally narrow enough that Codex can load the relevant workflow without pulling the full project blueprint into context.

All 30 `SKILL.md` files were validated with the OpenAI skill-creator validator on 2026-09-30.

## Version skills

| Version | Skill | Primary scope | Default thinker |
|---|---|---|---|
| v0.0 | `v0-0-repo-foundation` | repo/build/CI/schema/test foundations | GPT-6.1 Sol medium |
| v0.1 | `v0-1-capture-recorder` | Android Camera2/ARCore/IMU/depth recorder | GPT-6.1 Sol high |
| v0.2 | `v0-2-sparse-reconstruction` | desktop ingest, COLMAP sparse, metric alignment | GPT-6.1 Sol xhigh |
| v0.3 | `v0-3-object-mesh` | dense object reconstruction and export | GPT-6.1 Sol high |
| v0.4 | `v0-4-guided-capture` | keyframes, quality gates, capture guidance | GPT-6.1 Sol high |
| v0.5 | `v0-5-production-texturing` | UV, visibility, source selection, seams | GPT-6.1 Sol xhigh |
| v0.6 | `v0-6-room-scan` | depth/TSDF room reconstruction | GPT-6.1 Sol xhigh |
| v0.7 | `v0-7-desktop-companion` | trusted resumable phone↔desktop workflow | GPT-6.1 Sol high |
| v0.8 | `v0-8-mobile-viewer` | Filament preview/inspection | GPT-6.1 Sol high |
| v0.9 | `v0-9-pro-stabilization` | provenance/reproducibility/Pro guarantees | GPT-6.1 Sol high |
| v0.10 | `v0-10-smart-surface` | semantic surface interpretation | GPT-6.1 Sol xhigh |
| v0.11 | `v0-11-mirror-system` | mirror plane/reflection/fallbacks | GPT-6.1 Sol xhigh |
| v0.12 | `v0-12-gaussian-desktop` | desktop gsplat/3DGS | GPT-6.1 Sol high |
| v0.13 | `v0-13-glass-materials` | thin glass/specular/PBR handling | GPT-6.1 Sol xhigh |
| v0.14 | `v0-14-on-device-reconstruction` | mobile reconstruction, memory/thermal/GPU | GPT-6.1 Sol xhigh |
| v0.15 | `v0-15-ios-lidar` | iOS + baseline LiDAR integration | GPT-6.1 Sol xhigh |
| v0.16 | `v0-16-robustness-beta` | long-scan/device/recovery hardening | GPT-6.1 Sol high |
| v1.0 | `v1-0-stable-release` | stable Pro+Smart release gating | GPT-6.1 Sol high |
| v1.1+ | `v1-1-advanced-research` | post-v1 research and expansion | GPT-6.1 Sol xhigh |

## Heavy-task skills

| Skill | Why it is split out |
|---|---|
| `sensor-sync-calibration` | timing/calibration errors can create plausible but wrong reconstruction |
| `pose-priors-scale-alignment` | transform/Sim(3)/prior weighting needs explicit mathematical review |
| `keyframe-coverage-engine` | selection quality controls both reconstruction reliability and cost |
| `texture-projection-seams` | projective visibility and photometry are error-prone and quality-critical |
| `tsdf-room-fusion` | numerical depth integration and sensor weighting need dedicated fixtures |
| `resumable-transfer` | multi-GB corruption/reconnect/idempotency requires fault-injection tests |
| `surface-router` | central Smart-mode multi-signal decision logic |
| `mirror-view-dependent` | non-standard view-dependent/virtual-camera representation |
| `refractive-research` | explicitly research-grade optics; isolated from stable thin-glass handling |
| `mobile-gpu-reconstruction` | Vulkan/Metal correctness, resource lifetime and profiling |
| `lidar-fusion` | hardware depth needs its own calibration/noise/weighting treatment |

## Loading guidance

- Load the **version skill** for milestone-wide work.
- Also load the **heavy-task skill** when the task enters that subsystem.
- `AGENTS.md` remains the short repository contract.
- `ARCHITECTURE.md` contains the system explanation and mathematics.
- `PLANS.md` governs multi-hour ExecPlans.
- `SUBAGENT.md` governs planner/worker/reviewer delegation.
- `MODEL_ROUTING.md` contains the research snapshot behind model choices.

Do not load every skill into every task. Progressive, task-specific context is the point of this layout.
