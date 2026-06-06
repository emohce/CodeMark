# Workflow Rules

Tool: codex

## Commands

- Known commands or command families: project-specific commands
- Prefer existing project scripts and documented commands over inventing new ones.
- Do not run destructive commands or production operations without explicit confirmation.

## Verification

- For small rule-only edits, run the CodeNote project audit.
- For code changes, run the nearest project-approved test or provide the smallest manual verification path.
- For documentation-heavy changes, validate Markdown links and record unresolved links.

## Required AI Rule Audit

```bash
python3 /Users/gdkmjd/work/czz/CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/scripts/audit_ai_rules.py . --mode project --fix-links
python3 /Users/gdkmjd/work/czz/CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/scripts/audit_ai_rules.py . --mode project
```
