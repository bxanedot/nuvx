# Migration execution handoff

Update after EACH coherent segment and before any session boundary. The next agent must act on this file instead of re-planning the entire migration.

## Current state
- Branch/worktree:
- HEAD commit:
- Dirty/changed files:
- Next numbered task and subtarget:
- Last passed checkpoint:
- Latest failed or unavailable tests:

## Progress since last checkpoint
- Implemented:
- Tests executed and exact results:
- Visual/UX parity evidence paths:
- Data compatibility evidence paths:
- Benchmark evidence paths:

## Unresolved blockers
| ID | Affected task/subtarget | Evidence | Attempts | External dependency | Retry trigger |
|---|---|---|---|---|---|

## Next exact executable actions
1. Read `docs/rust-migration/agent-pack/AUTORUN_PROTOCOL.md`.
2. Inspect current HEAD and `docs/rust-migration/STATUS.json`.
3. Continue the next ready segment; do NOT wait for the user to say next.

## Non-negotiable safeguards
The old UI/UX stays identical. No user data loss. One canonical Rust core. No unauthorized release/deployment.
