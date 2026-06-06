# EzCodeMark AI Adapter

Tool: codex

Read first:
- [../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md](../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md)
- [vibe/rules/README.md](vibe/rules/README.md)

Hard constraints:
- Keep project-specific rules in `vibe/rules/`; do not copy the CodeNote master into this repository.
- Preserve existing behavior and user changes; do not touch unrelated business code.
- Serena is disabled for this project. Do not invoke Serena tools, read/write Serena memories, or use `.serena/` as an AI context source.
- High-risk actions require confirmation: DB writes, deletes, production changes, credentials, publish/deploy, or external service writes.
- Write Markdown links relative to the target document location.
- Final replies must include verification status and memory/process-document status.
