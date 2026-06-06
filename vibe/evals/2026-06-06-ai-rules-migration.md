# AI Rules Migration Eval

Tool: codex

Project: `EzCodeMark`
Wave: `wave2`
Date: 2026-06-06

## Migration Summary

- Applied CodeNote master-rule adapter structure.
- Created or updated `AGENTS.md` and project `vibe/rules/` entries.
- Preserved pre-migration adapter content under `vibe/knowledge/legacy/adapters/` when an adapter already existed.
- DB workspace created: no.

## Verification

### Audit With Link Fix

```text
AI rule audit: OK
```

### Final Project Audit

```text
AI rule audit: OK
```

### Current Git Status Snapshot

```text
M .codemark/codemark.json
 M .codemark/tree-ui.json
 M AGENTS.md
 M doc/260604-cursor-tree-operations-spec.md
?? vibe/evals/2026-06-06-ai-rules-migration.md
?? vibe/knowledge/
?? vibe/rules/
```

## Remaining Notes

- Default project audit checks AI rule surfaces only. Use `--all-markdown` for broad historical-doc hygiene scans.
- Existing user dirty worktree entries were not reverted.
- Unresolved AI-rule audit issues: none.

## Memory Routing

- Project memory: updated AI rule structure.
- Error archive: not needed.
- ADR: not needed.
- DB memory: not needed.
