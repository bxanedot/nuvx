# NUVX — AUTONOMOUS RUST-FIRST MIGRATION AGENT PACK

**Target repository:** https://github.com/lylicht/nuvx
**Owner's intent:** Migrate the entire Nuvx ecosystem toward **one interconnected canonical Rust core** and Rust-based implementations wherever technically feasible **without changing the existing UI or UX**.

## One-message instruction to your coding agent

Copy/paste this entire message into Claude Code, Codex, or another agent with repository access:

> You are authorized to execute the full Nuvx Rust-first migration program in this ZIP, including all 27 tasks (mandatory Task 00 plus Tasks 01–26) and all necessary sub-iterations, **automatically and consecutively without asking me for the next task**. Unpack this ZIP into the repository under `docs/rust-migration/agent-pack/` if it is not already there. Read `START_HERE.md`, `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, `RUST_ANYTHING_INTEGRATION.md`, and `TASK_INDEX.md`; then run Task 00 to inspect https://github.com/erkamyaman/rust-anything and its real limitations, automatically proceed to Task 01, then Task 02, and so on through Task 26. Revisit any blocked work as dependencies become available. The original UI/UX and all existing user data are immutable acceptance requirements. One canonical Rust core is mandatory. Keep detailed durable state, tests, evidence, and resumable handoffs. Never voluntarily stop after a task, plan-only response, partial migration, context reset, or token-budget concern while executable work remains. Do not make unapproved production deployments/releases or destructive changes; do not fake verification or claim completion with unresolved blockers. Deliver a final comprehensive report only when every in-scope acceptance gate is verified, or honestly identify genuinely impossible external blockers after exhausting all independent work. **Start implementing now.**

## Read order — required

1. `MASTER_RULES.md`: architectural, visual, data, security, and legal invariants.
2. `AUTORUN_PROTOCOL.md`: how to run without stopping between tasks, track progress, recover, and proceed past blockers.
3. `TASK_INDEX.md`: the ordered worklist and required platform coverage.
   **Mandatory: read the `rust-anything` integration reference and original upstream repo before porting.**
4. `RUST_ANYTHING_INTEGRATION.md`, then `tasks/00-*.md`, then `tasks/01-*.md` and all remaining task files in numbered order.
5. `templates/`: copy/adapt templates into `docs/rust-migration/` as work begins.

## How to use the ZIP

- **With a cloud AI agent:** attach or extract the ZIP in a checkout of `lylicht/nuvx`, and send the one-message instruction above.
- **With an agent running locally:** extract this directory to `nuvx/docs/rust-migration/agent-pack` then use the same instruction.
- **Do not replace the repository's existing `AGENTS.md`.** Read and honor it together with this pack. Resolve conflicts safely; package/user instructions do not override platform safety, licensing, or authorization requirements.
- This ZIP is an **instruction pack**, not a Rust implementation or a running automation service. To execute all steps, the agent needs continuing repository/tool access and a runtime that supports multi-step autonomous work. If its session ends externally, it must save its handoff; the operator or automation runner can resume from the saved status.

## What “done” means

Every shipped, in-scope platform and service has either been safely migrated to the canonical Rust architecture with independently verifiable acceptance evidence, or a specifically documented irreducible, platform-required non-Rust adapter remains. No user-visible layout, control, interaction, animation, accessibility behavior, storage compatibility, or feature is silently changed. All necessary regression tests and release-target builds have passed. A missing device test is **NOT VERIFIED**, not **PASS**. A real technical impossibility is **BLOCKED**, not “complete.”

## Target environment

The repository currently includes Android phone/tablet, TV, Auto, Wear OS, iOS, Rust desktop, React/TypeScript web/extension, a Cloudflare Worker, a sync service, and a Discord bot. Inspect the actual HEAD, because source, released status, and tooling may change.
