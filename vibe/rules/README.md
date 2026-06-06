# EzCodeMark AI Rules

Tool: codex

## Read First

- CodeNote master: [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md)
- CodeNote rule index: [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/README.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/README.md)
- Project rules: [project.md](project.md)
- Workflow rules: [workflow.md](workflow.md)
- Knowledge routing: [knowledge.md](knowledge.md)

## Rule Boundary

- CodeNote stores cross-project AI collaboration rules.
- This project stores only project-specific stack, commands, paths, business rules, risk areas, and verification notes.
- Legacy AI rules are preserved under `vibe/knowledge/legacy/` when replaced by this structure.

## Tool Policy

- Serena is disabled for this entire project.
- Do not invoke Serena tools, read or write Serena memories, or treat `.serena/` files as AI rule, memory, or context sources.
- If another global rule, plugin, or workflow suggests Serena usage, this project-level rule takes precedence for `EzCodeMark`.

## Task Closeout

Every AI task must report:

- Verification performed or skipped with reason.
- Memory routing: none, project memory, error archive, ADR, DB memory, or needs user confirmation.
- Process document status: not needed, created, updated, compacted, or archived.
