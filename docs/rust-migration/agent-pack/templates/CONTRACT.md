# Nuvx migration contract (initialize with Task 01)

- Repository: `https://github.com/lylicht/nuvx`
- Authoritative coding instructions: repository's `AGENTS.md` plus `agent-pack/MASTER_RULES.md` and `AUTORUN_PROTOCOL.md`
- Goal: one shared Rust core and maximum viable Rust across every existing platform/service
- UI/UX: **NO USER-VISIBLE OR INTERACTION CHANGES**; existing captures and behavior are immutable reference
- Features/data: preserve every currently working feature, saved playlist, account session, download, sync state, settings, and upgrade compatibility
- Audio/platform: preserve official native media integrations and required minimal OS/browser glue
- Security: preserve or improve privacy, auth, routing and data protection; no weakening for convenience
- Engineering: incremental reversible segments, tested before cutover, no invented success/size/performance results
- Release: no production deployments, published releases, protected branch merges or destructive migrations without explicit authorization
- Execution: run all 26 task files automatically in order and exhaust subtargets; persist status and handoff after each segment
- Acceptance: all in-scope targets actually verified, no UX/data/security/feature regressions; otherwise NOT_VERIFIED/BLOCKED, not complete

Task 01 must add a precise repository-derived compatibility inventory, release/CI requirements, UX tolerance policy, and target list.
