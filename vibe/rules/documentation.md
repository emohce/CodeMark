# Documentation Rules

Tool: codex
Date: 2026-06-22

## Purpose

Define EzCodeMark-specific documentation routing. Cross-project documentation and process rules stay in CodeNote; this file maps those rules onto this repository.

## Authoritative Sources

| Layer | Location | Role |
| --- | --- | --- |
| Global master | [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md) | Cross-project AI workflow, safety, memory, verification, and documentation rules. |
| Process layout authority | [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md#3-project-location) | Task date grouping, task folder layout, and flat-folder repair. |
| DB governance | [../../../CzzProj/CodeNote/DevelopRef/调试工具/db/governance/README.md](../../../CzzProj/CodeNote/DevelopRef/调试工具/db/governance/README.md#5-workspace-shape-and-naming) | AI-DB workspace shape, storage routing, and naming authority. |
| Project adapter | [../../AGENTS.md](../../AGENTS.md) | Short tool routing surface. |
| Project rules | [README.md](README.md), [project.md](project.md), [workflow.md](workflow.md), [knowledge.md](knowledge.md), [documentation.md](documentation.md) | EzCodeMark stack, risk boundaries, verification, and local documentation routing. |
| Process hub | [../specs/PROJECT_STATUS.md](../specs/PROJECT_STATUS.md) | Current focus, active task docs, verification status, and open gates. |
| Project knowledge | [../knowledge/README.md](../knowledge/README.md) | Reusable project facts, ADR/error-memory indexes, and technical details. |

## Project Mapping

- Keep reusable cross-project rules in CodeNote; keep only EzCodeMark-specific stack, commands, paths, and risk boundaries in this repository.
- Task/archive folder date grouping and flat-folder repair are not redefined here; follow the global process layout authority above.
- Legacy or historical docs remain evidence until promoted into [../knowledge/README.md](../knowledge/README.md) or linked from current process docs.
- DB workspace is not configured; if DB/data work becomes active, initialize `vibe/ai-db/` through the DB governance authority above.

## Closeout

- Update [../specs/PROJECT_STATUS.md](../specs/PROJECT_STATUS.md) when current focus, active task docs, verification state, gates, sibling links, or memory routing changes.
- Promote reusable conclusions to [../knowledge/README.md](../knowledge/README.md), ADR/error-memory equivalents, project rules, or DB memory when applicable.
- Report verification, memory routing, and process document status in final delivery.
