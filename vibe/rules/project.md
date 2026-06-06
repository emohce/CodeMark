# Project Rules

Tool: codex

## Project Profile

- Name: `EzCodeMark`
- Current inferred stack: Code/documentation utility project
- Migration date: 2026-06-06

## Detected Manifests

- `build.gradle.kts`

## Local Rule Policy

- Keep project-specific constraints here; move reusable cross-project rules to CodeNote.
- Do not overwrite existing user work or unrelated business files.
- Before implementation, inspect the relevant source paths and existing docs for the current task.
- For UI work, follow project style first, then CodeNote UI rules.
- For security, data, release, or permission work, apply CodeNote high-risk gates.
- Disable Serena for all project work: do not call Serena tools, read/write Serena memories, or use `.serena/` as context. Use normal file inspection and project-approved commands instead.

## High-Risk Areas

- Treat configuration, credentials, release scripts, generated artifacts, data mutations, and external-service writes as high risk until project-specific rules say otherwise.
- Add concrete high-risk paths here as they are discovered.
## Migrated Project-Specific Constraints

- 修改 IDE action 或 listener 前必须确认触发入口、线程模型和重复触发风险。
- 修改 gutter 或 editor 相关逻辑前必须确认性能和光标上下文。
- 构建产物和 IDE 生成文件不手改。
