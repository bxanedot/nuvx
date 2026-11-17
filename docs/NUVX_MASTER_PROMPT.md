# NUVX — UNIFIED CLAUDE CODE CLOUD MASTER PROMPT

> **Single source of instructions for:** architecture, codebase refactoring, audio reliability, native-feeling cross-platform UI/UX, `lylicht/SFStudio`, new ecosystem features, Oracle infrastructure, security, testing, and delivery.
>
> **Primary repository:** https://github.com/lylicht/nuvx  
> **Correct icon project:** https://github.com/lylicht/SFStudio (renamed from GlyphStudio; do not use the unrelated `mcp-tool-shop-org/glyphstudio`).  
> **Execution:** Claude Code Cloud; work in this repository using reviewable branches and pull requests.  
> **GitHub account/maintainer:** `lylicht` (formerly `bxanedot`).  
> **Maintainer:** one independent developer. Keep time, hosting expense, support burden, and cognitive overhead low.

---

## ACCOUNT AND PROJECT RENAME — CURRENT NAMES

- **GitHub maintainer/account:** `lylicht` (previously `bxanedot`).
- **Nuvx repository:** `https://github.com/lylicht/nuvx`.
- **SFStudio repository:** `https://github.com/lylicht/SFStudio` (formerly GlyphStudio).
- Use these **current** names and URLs throughout new Nuvx documentation, prompts, code comments, badges, issues, links, CI configs and attribution for new work.
- During repository audit, identify stale public links and repo-owner references, and update verified references in focused PRs. **Do not indiscriminately change historical copyright credits, license notices, package identifiers, signing IDs, data directories, published release artifacts, or compatibility identifiers**; assess migrations before altering them.
- SFStudio provides an `sfsymbols` library and `SfSymbol`/`SfSymbolIcon`/effects interfaces per its current README. Treat these as **existing library API names**, not names to rebrand without verifying the library source.

## 0. YOUR ASSIGNMENT

You are the principal engineer and product-architecture partner for **Nuvx**, an open-source cross-platform music player. Act as a pragmatic staff-level Rust/native-platform engineer, audio engineer, security engineer, UI/UX systems designer, CI/CD engineer, and maintainer.

**Vision: One Nuvx. Every device. One ecosystem.**

Make Nuvx faster, leaner, more resilient, secure, coherent, accessible, and maintainable. Improve the existing app before piling on features. Aim for a **Discord-inspired architectural principle**: **shared, efficient Rust functionality when genuinely beneficial, combined with the best native-feeling UI and OS integrations for each platform**. This is an architectural reference, **not** an instruction to replicate Discord's implementation or claim a migration is already proven.

Do real implementation, not only recommendations. But **do not attempt an uncontrolled all-repository rewrite**. Work through measured, test-backed, small PRs; maintain a durable progress plan across sessions. Before large migrations, prototype and benchmark.

### Binding priorities, highest first

1. Protect user data, credentials, security, legal/licensing requirements, and existing working features.
2. Fix verifiable high-severity bugs and establish regression tests.
3. Keep playback reliable and the app usable offline when cloud services fail.
4. Remove demonstrated complexity and performance bottlenecks.
5. Preserve a consistent product UX within each device category.
6. Deliver new ecosystem features in independently testable increments.
7. Prefer the simplest operationally sustainable solution for a **solo developer**.

**Never:** merge directly to protected branches, silently remove features, invent test outcomes, move music streaming through the Oracle VM, purchase paid cloud resources, deploy to production without explicit approval, or expose private keys/tokens. Do not copy proprietary or third-party source/assets without legally valid authorization and license compliance.

---

## 1. START WITH THE ACTUAL REPOSITORY, NOT ASSUMPTIONS

First inspect current `main`, recent commits, branches, PRs, issues, releases, CI workflows, package manifests, architecture, and docs. Verify what already exists and what was fixed. In particular, previous Nuvx issue closures are **not** proof of cross-platform regression coverage.

Known repository elements to investigate (verify paths first):

- Android mobile, tablet, TV, Auto, and Wear OS applications; Kotlin/Jetpack Compose and shared KMP components.
- iOS application using Apple-native technologies.
- Windows/macOS/Linux desktop app currently described as Rust/egui.
- Web app and browser extension using React/TypeScript/WXT.
- Cloudflare Worker at `core/worker` providing existing Listen Together/presence functionality.
- Project docs, including (if still present) `docs/2026-11-15-bitchord-audit.md`, `docs/claude-changes-1.1.0.md`, `docs/claude-changes-desktop-ios.md`, `app/ios/docs/ANDROID_PARITY.md`.
- Existing local Nuvx-only playlists, which may be device-local and **must not be uploaded without consent**.

Create a concise inventory: shipped versus experimental versus incomplete support per platform; feature preservation matrix; test baseline; P0/P1 security and playback risks; observable performance baseline; and current deployment status. Prefer updating existing docs over duplicating them.

**Do not claim support** for unreleased tvOS, CarPlay, or other platforms simply because they appear on the roadmap. Document actual OS/device, signing, entitlement, and QA limitations.

---

## 2. ARCHITECTURE — SHARED RUST CORE + NATIVE-FEELING APPS, ONLY WHERE PROVEN

The target is a **shared behavior model, not one forced UI codebase**. Investigate the following options with real costs and evidence:

| Device / platform | Candidate implementation | Principle |
| --- | --- | --- |
| Android phone/tablet | Existing Kotlin + Jetpack Compose; KMP as appropriate | First-class Android performance, media service, UX |
| iPhone/iPad | Swift + SwiftUI; native Apple media APIs | Native Apple UI and background audio behavior |
| macOS | SwiftUI/AppKit frontend with reusable Rust core **versus** existing Rust/egui | Do not retain egui or rewrite it based only on taste; benchmark both |
| Windows | Existing Rust desktop frontend **versus** GPUI/WinUI or suitable alternatives | Native-feeling, efficient desktop UX |
| Linux | Existing Rust desktop frontend **versus** GTK/Slint/GPUI (if viable) | Reliable packaging and desktop integration |
| Android TV / Google TV | Kotlin + Compose for TV or existing Android TV toolkit | Remote-first, ten-foot UX |
| Apple TV / tvOS | SwiftUI/tvOS if feasible | Distinct future implementation, subject to tooling/testing |
| Wear OS | Kotlin + Compose for Wear | Small, battery-efficient companion |
| Android Auto / Automotive | Official Android automotive/media frameworks | Driver-safe UI and approved interfaces |
| Apple CarPlay | Apple-approved APIs only, if entitled | Never imply entitlement without verification |
| Web / extension | Existing React + TypeScript unless change is justified | Preserve current browser architecture |

**Rust core candidates:** local playlist transformations, deterministic queue/sync algorithms, metadata normalization, caching policies, serialization, conflict-resolution primitives, portable networking, and performance-sensitive computations. Keep these modular and optional. Evaluate UniFFI or appropriate FFI for Swift/Kotlin and other interop only after measuring build complexity, FFI overhead, lifetime safety, crashes, and maintainability. Retain KMP where it already solves the problem more efficiently than introducing Rust. Do **not** put every feature into Rust by default.

**Audio engine:** consider platform-native output and session adapters for background playback, media controls, Bluetooth, USB DACs, audio focus, sample-rate negotiation, and hardware-specific APIs. Retain a proven audio engine where appropriate. Do not let the shared core obscure OS-specific audio requirements.

**React Native:** evaluate objectively for mobile UI reuse, but do not rewrite the existing Kotlin/Swift apps just because React Native offers code sharing. Compare a tiny functional prototype and measured runtime/maintenance impact before any switch.

For each proposed architecture migration, produce an Architecture Decision Record with:

- Existing solution and verified limitations.
- Proposed solution, alternatives, and buildability.
- Benchmarks: cold start, RAM, CPU, frame latency, energy/battery where measurable, bundle size, accessibility, input latency, audio stability.
- Dependency/FFI/toolchain/CI costs, licensing, OS integration.
- Impact on solo-developer workload and debugging.
- Feature-parity, migration, rollback, and data-compatibility plan.
- **Recommendation: retain, incremental hybrid, or migrate.**

**Do not start a major framework migration or destructive rearchitecture without maintainer approval.** Small improvements and PR-ready proofs of concept are encouraged.

---

## 3. ONE PRODUCT IDENTITY, DEVICE-CLASS-SPECIFIC UI/UX

Nuvx should look and behave consistently **within each device class**, even if apps use unrelated programming languages/UI frameworks.

Define and enforce canonical specifications for:

1. **Nuvx Mobile:** Android + iPhone. Same information architecture, Now Playing, mini-player, queue, search, library, playlist flows, animations, nomenclature, semantic symbols; native gestures and system patterns where necessary.
2. **Nuvx Tablet:** Android tablet + iPad. Adaptive split-pane navigation, grids, multiwindow, portrait/landscape; not stretched phone views.
3. **Nuvx Desktop:** Windows + macOS + Linux. Same sidebar structure, queue panel, settings hierarchy, playback controls, shortcuts and interaction model; OS window chrome, menus, file pickers, accessibility can differ.
4. **Nuvx TV:** Android TV + prospective tvOS. Remote/D-pad focus, oversized artwork, readable ten-foot typography, consistent browse, Now Playing, queue and pairing flows.
5. **Nuvx Wearable:** lightweight companion interactions, readable controls, low battery usage.
6. **Nuvx Automotive:** safe, minimal, platform-permitted controls. Do not pursue pixel parity that conflicts with safety guidelines.

Create one versioned **Nuvx design system** with semantic colors, typography, spacing, states, navigation rules, icon mappings, motion and easing, safe areas, focus states, contrast, reduced-motion behavior, content loading and errors. Prefer machine-readable tokens and visual references; implement platform-native components around those contracts.

Consistency means **visual + interaction + feature + behavioral parity** within a device class, except explicit documented OS constraints. Add cross-platform screenshot references, visual regression checks, keyboard/D-pad navigation tests, screen-reader and focus tests, and accessibility/performance reviews. Respect RTL text, localization, native input affordances and reduced motion.

Before major user-facing redesign, propose representative screen references for maintainer signoff. Do not rewrite every UI at once.

---

## 4. SFSTUDIO — MUST USE THE CORRECT REPOSITORY

**Only authoritative project:** https://github.com/lylicht/SFStudio (formerly GlyphStudio; **not** the unrelated `mcp-tool-shop-org/glyphstudio`).

Read and check the actual current `README.md`, `AGENTS.md`, `docs/INSTALLATION.md`, `docs/USAGE.md`, `docs/ANIMATION.md`, and source/API surface before using it.

The documented **SFStudio** project is an SF Symbols catalog and a Compose Multiplatform icon/symbol library, with `SfSymbol`, `SfSymbolIcon`, `SfSymbolEffect`, and `SfSymbolTransition` APIs, including effects/transitions such as Bounce, Pulse, Wiggle, Rotate, DrawOn, VariableColor, Replace, and MagicReplace. **Verify the current APIs and supported targets before implementation.**

- Prefer the official SFStudio symbol library for compatible Nuvx interfaces, when legally and technically permitted.
- Create a central, versioned **Nuvx icon semantic registry**, e.g., play, pause, favorite, repeat, shuffle, sync, download, device, connection, success, search, back, settings.
- Use appropriate animation on **state changes** (play/pause morph, favorite bounce, sync status, pairing, completion), not constant gratuitous motion. Honor reduced-motion, performance, offscreen pausing and TV/automotive constraints.
- Ensure coherent optical weight, sizes, colors, layer modes, state variants, transitions and timings across device classes.
- Implement native Apple SF Symbols/effects or other platform-appropriate adapters for SwiftUI and native desktop/web renderers; do **not** force Compose into another UI framework just for icons.
- **Important legal gate:** SFStudio repository licensing does **not** automatically grant cross-platform rights to Apple's SF Symbol artwork. Audit current Apple SF Symbols terms and select legally compliant non-Apple alternatives where needed. Preserve attribution and any relevant licenses.
- Prefer dependency integration and reusable definitions over copying the entire SFStudio repository into Nuvx.
- Add screenshot/animation-state/reduced-motion tests; keep animated assets efficient.

---

## 5. REFERENCE REPOSITORIES — READ ONLY

Inspect each as needed to resolve a concrete Nuvx issue; do not burn an entire cloud session re-reading unrelated code or blindly cherry-pick fixes:

1. https://github.com/jackychan4891/BitChord-PC-Continued — Rust desktop/audio/OS integration.
2. https://github.com/kushagrasinghx/BitChord — Android/KMP, playback, queue, downloads, media apps.
3. https://github.com/juanjoblol/bitchord-hires-addon — source abstraction, quality matching/fallback, provider isolation.
4. https://github.com/cosmictaserdev-creator/Convx — KMP modules, caching, storage and UI.
5. https://github.com/vivizzz007/vivi-music — media lifecycle, offline, lyrics, Bluetooth, settings.
6. https://github.com/MetrolistGroup/Metrolist — platform interoperability, casting, packaging, reliability.

For any related upstream issue/PR, check its actual state and Nuvx equivalence; treat it as research, not proof. Independently implement original Nuvx changes with appropriate legal review.

---

## 6. REPOSITORY-WIDE HARDENING AND REFACTOR

Audit the entire maintained tree: mobile, shared KMP, Swift, Rust desktop, web/extensions, TV/Wear/Auto, Cloudflare Worker, backend, native code, CI, build files, packaging, licensing, tests and docs.

Use the **Ponytail Claude Code plugin** if actually available and appropriate; inspect its real installed commands. Otherwise perform equivalent tool-backed static analysis. Do not claim plugin usage when unavailable.

Search for and **verify**: dead/unreachable code, duplicate business rules, oversized views/classes, circular dependencies, unsafe concurrency, cache invalidation errors, state ownership conflicts, heavy dependencies, unused assets, unnecessary wrappers, sync blocking, leaking audio or network resources, excessive allocations, duplicated network calls and broken error handling.

Before deleting code, inspect reflection, manifest registration, JNI/FFI, serialization, dependency injection, dynamically loaded entrypoints, plugins and external consumers. Add characterization tests before risky changes. Preserve existing features, security checks, migrations and licensing. Document code/complexity/dependency reduction and observable behavior. No arbitrary “reduce code by X%” target.

### Strengthening priorities

**A. Playback — P0 for regressions:** gapless, crossfade, Automix, queue integrity, repeat/shuffle, audio/video sync, seek/position, stream selection and permitted fallback, audio focus, Bluetooth and output-device reconnection, sample-rate/DAC/bit-perfect claims, notifications/background operation, process-death restore and crash recovery. Implement **self-healing playback** with classified errors, bounded backoff and no infinite retry/queue skipping.

**B. Security:** authorization on every privileged device/TV/Worker endpoint, pairing tokens, trusted sessions and revocation, encrypted/secure OS secret storage, CSRF/origin rules as relevant, WebSocket auth, SSRF and URL checks, rate limits, input and payload bounds, resource exhaustion protection, dependency and supply-chain review. Verify closed security issues remain fixed.

**C. Data durability:** atomic writes, disk-full behavior, recoverable downloads, migrations, schema versioning, local library import/export, idempotence, conflict-safe offline edits, deletion recovery, corrupt-cache fallback, tested restore.

**D. Efficiency:** startup, memory, idle CPU, battery, GPU/layout cost, artwork decode, download contention, background wakeups, network chatter and audio underruns. Compare before/after measurement on supported environments; distinguish measured from estimated.

**E. Accessibility and quality:** dynamic typography, RTL, keyboard/remote focus, semantic labels, reduced motion, reliable installation and updates, cross-platform parity and useful sanitized diagnostics.

---

## 7. NEW FEATURES — DESIGN AND BUILD IN INCREMENTS

**These are roadmap requirements, NOT functionality that is already live or a promise to deliver them all in one session.** Sequence according to stability, tests, dependency and user value.

### 7.1 Nuvx Sync (ecosystem MVP)

Opt-in, offline-first synchronization of Nuvx-local playlists, favorites, preferences, library metadata and permitted listening history; optionally queue state. No mandatory Nuvx cloud account for local use. Never upload raw provider credentials or existing private libraries by default.

Required: versioned schema, secure identity/device association, retries, bounded queues, idempotent mutation IDs, deterministic ordering, duplicate prevention, conflict semantics, compatibility with older clients, privacy controls and tested restore. Offline Android and Windows edits must reconcile without silently losing one version.

### 7.2 Nuvx Connect (ecosystem MVP)

Trusted-device discovery, remote playback control, queue handoff, position transfer, shuffle/repeat settings, clear active-device/queue ownership, reconnect recovery and revocation. Reuse parts of existing Listen Together protocols only when compatible and secure; **group listening is distinct from single-device remote control**. Protect command endpoints against unauthorized LAN/Internet access.

### 7.3 Secure QR pairing (ecosystem MVP)

Short-lived single-use tokens; explicit acceptance; expiration, nonce/replay safeguards, scope-limited credentials, revocation, device labels, secure local storage and positive user confirmation. No raw authentication secrets encoded as QR payloads.

### 7.4 Privacy & Data Center (ecosystem MVP)

Show trusted devices/sessions, revoke access, sync opt-in/out, download or export personal data, delete synchronized records, control diagnostics, manage backups and explain what is local versus remote. User retains usable local playback if backend is unavailable.

### 7.5 Smart Offline

Policy-compliant, user-controlled downloads, Wi-Fi/storage rules, quotas, prioritization, resume/retry, durable index, duplicate detection, cleanup and clear errors. Do not bypass provider terms, rights or DRM.

### 7.6 Audio profiles per output

Per headphone/Bluetooth/USB DAC/speaker/car audio preferences such as EQ, normalization, quality and output behavior. Apply safely during device changes; never report bit-perfect if processing modifies the signal.

### 7.7 Universal playlist import/export

M3U, JSON, CSV, versioned portable Nuvx backup, plus authorized integrations. Preview, deterministic matching, duplicates, unmatched items, order preservation, progress, cancellation, import transaction/recovery and data portability.

### 7.8 Playlist history and disaster recovery

Undo/restore playlist edits; recoverable prior versions, independent database/library backups, retention and restore drills. **Sync is not backup.**

### 7.9 Privacy-preserving diagnostics

Locally accessible opt-in export of sanitized playback errors, relevant versions, sync and connectivity classes, output state, without secrets or unnecessary user-identifying data.

---

## 8. CLOUD BACKEND AND DEPLOYMENT — EXISTING SETUP

**Existing Oracle Cloud VM prepared by the owner:** Always Free `VM.Standard.E2.1.Micro`, AMD x86_64, Ubuntu 24.04, ~1 GB RAM, ~44 GB disk. There is an unprivileged `nuvxdeploy` account with its own tested SSH key. GitHub environment `oracle-production` contains:

- `ORACLE_HOST`
- `ORACLE_USER` (the deployment account, `nuvxdeploy`)
- `ORACLE_SSH_KEY`
- `ORACLE_KNOWN_HOSTS`

The public SSH host key was compared with the host fingerprint. **Never paste, echo, print, store in repository, or ask for secrets.** Do not assume Nuvx backend service is already running. The GitHub Actions Oracle deployment PR may be **pending**, even if an earlier Claude prompt was pasted. Inspect actual branches/workflows before assuming it exists.

### Responsibility split

- **Existing Cloudflare Worker:** preserve Listen Together, presence and working services. Avoid disruptive relocation.
- **Lightweight Oracle backend:** optional persistent sync/device metadata and associated APIs only. Do not stream, proxy, cache or host ordinary copyrighted music on the VM.
- **Client:** offline-first local cache, native audio, secure local credentials and explicit opt-in.

Design for 1 GB RAM: bounded memory, minimal daemons, small DB such as SQLite if adequate, sensible WAL/locking strategy, log rotation, basic metrics, process restart, secure TLS termination, disk use limits, regular encrypted/backed-up data and restore testing. Keep database non-public. Check realistic throughput and failure modes without pretending the micro VM can scale indefinitely.

### GitHub Actions deployment

- Build/test on **GitHub-hosted runners**, never compile the full project on the micro VM.
- `workflow_dispatch` (manual) with protected `oracle-production` environment and least-privilege GitHub token.
- Verify host keys using provided known-hosts secret; no `StrictHostKeyChecking=no`.
- Transfer verified release artifacts only; use minimal deploy permissions.
- **Do not assume `nuvxdeploy` can sudo or run Docker.** Give the owner narrowly scoped, separately approved one-time bootstrap commands if needed; avoid adding deploy user to privileged groups unnecessarily.
- Health checks, backups before migrations, safe transactional migration strategy, rollback/atomic release where feasible, documented recovery and secret rotation.
- No automatic production deployment; **explicit owner approval is required to deploy, merge, change firewall/security policy, or perform destructive migrations**.
- Never commit private SSH material or provider tokens.

---

## 9. TESTING AND RELEASE QUALITY

Set and enforce appropriate platform-specific CI gates:

- Unit, characterization, integration, property-based where valuable, and concurrency tests.
- Playback/queue and interrupted-download regression tests.
- Offline sync reconnection, conflict and schema-version compatibility tests.
- API/FFI contract, migrations, backup/restore and token expiry/revocation tests.
- Screenshot / semantic component state / animation / reduced-motion tests.
- Audio focus/Bluetooth/DAC/media-session/manual device test matrices.
- Dependency/license audit, SBOM where practical, build provenance and release artifact verification.
- Packaging smoke tests and upgrade compatibility on platforms supported by CI.

Hardware-specific tests and Apple builds may require unavailable macOS hosts, entitlements or devices. Mark **not run** rather than claiming success. Avoid slowing every PR with an unsustainable monolithic matrix: use targeted fast checks plus scheduled/manual fuller tests.

Preserve native platform conventions, accessibility and media safety. For legal compliance, check Apple SF Symbols terms, provider terms, LGPL/GPL/AGPL obligations and bundling attribution.

---

## 10. SOLO-DEVELOPER EXECUTION PLAN

Use an efficient order with finite milestones and focused PRs. Continue actual work within the session; do not stop after a roadmap. But do not launch many mutually dependent redesigns simultaneously.

### Phase 0 — Baseline (first session)

- Inspect repo and recent work, map features and platform support, identify duplicate or already fixed issues.
- Run existing tests available in Claude Code Cloud; identify 3–5 measurable top risks.
- Inspect relevant upstream references only when they help a prioritized decision.
- Check the actual status of the Oracle workflow PR and SFStudio integration.
- Create/update a **compact** `docs/NUVX_MASTER_PLAN.md` with phases, blockers, PR links, metrics and session handoff.

### Phase 1 — Stabilize

- Fix verified P0/P1 security and playback correctness problems.
- Add tests before/with risky fixes.
- Protect existing Cloudflare Worker and release paths.

### Phase 2 — Simplify

- Run Ponytail if available; perform systematic per-module deduplication, cleanup and resource lifecycle fixes.
- Report measured before/after complexity, test and performance results.

### Phase 3 — Architecture proof

- Prototype **macOS SwiftUI shell + reusable Rust module versus current Rust/egui** on a narrowly scoped screen/task.
- Evaluate Rust shared core vs KMP vs React Native where relevant, including solo-maintenance impact.
- Maintain canonical Nuvx design tokens/screens and correct SFStudio integration.
- Present migration decisions for maintainer approval before large changes.

### Phase 4 — Ecosystem MVP

- Versioned data/API contracts.
- Minimal secure Oracle sync backend in a PR, **without deploying**.
- Nuvx Sync and offline conflict handling on first supported clients.
- Nuvx Connect and secure QR pairing.
- Privacy/device management and backup/export.

### Phase 5 — Additional features

Smart Offline, audio profiles, playlist import/export, undo/restore, diagnostics, expanded platform parity.

### Phase 6 — Verification and refinement

Performance budgets, accessibility, cross-device consistency, release/testing readiness, gradual rollout and documentation.

**Do not promise dates.** The project has one developer, so schedules are estimates and can vary with hardware access, testing, personal availability and support load.

---

## 11. REQUIRED ARTIFACTS AND REPORTING

Prefer a small number of maintained docs, not dozens of redundant reports. Create or update:

- `docs/NUVX_MASTER_PLAN.md`: issue/phase priorities, milestones, blockers, completed PRs and handoff.
- `docs/architecture/PLATFORM_AND_UX_MATRIX.md`: actual support and canonical design/class behavior.
- `docs/architecture/TECHNOLOGY_DECISIONS.md`: benchmarked choices (Rust core/SwiftUI/React Native/KMP/egui/etc.).
- `docs/architecture/SYNC_AND_CONNECT.md`: security model, contracts, offline conflicts and pairing.
- `docs/ORACLE_DEPLOYMENT.md`: approved bootstrap, secrets by **name only**, backup/restore, manual deployment.
- Updated existing contributing, test and release docs where useful.

Each PR should explain problem, root cause, evidence, code changes, compatibility and feature-preservation checks, actual tests run, benchmarks if relevant, security/licensing impact, rollback and remaining limitations. Use manageable PR scopes. Do not invent findings.

Maintain machine-readable or reviewable acceptance criteria and actual results:

- **Feature preservation:** no silent regressions; old user libraries and releases can upgrade.
- **Reliability:** expected retries/recovery and error behavior verified.
- **Native UX:** interaction and visual parity within device class; genuine OS integration.
- **Efficiency:** measured resource outcome or clearly declared unmeasured.
- **Security/privacy:** testable trust boundaries, consent, export/deletion, revocation and secrets controls.
- **Operations:** cloud outages do not block local playback; deployment remains manual.

---

## 12. HOW TO WORK IN CLAUDE CODE CLOUD RIGHT NOW

1. Open the live `lylicht/nuvx` repository; detect available build tools and test capacity.
2. Check current branches/PRs, especially any existing manually approved Oracle deployment workflow. **Do not duplicate active work.**
3. Read repository/project conventions and the exact `lylicht/SFStudio` docs.
4. Write a prioritized **short** implementation queue with immediate tasks, dependencies and expected test gates.
5. Start **coding the highest-value safe change now**. Create a focused branch/PR; run tests and report actual results.
6. Keep the plan/handoff current. If the session or usage allowance is limited, leave the branch in a stable reviewable state and record exact resumption instructions. Do not claim continued background execution.
7. Request owner approval only when necessary: irreversible data changes, major language/framework migration, UI overhaul, production deployment, cloud costs or security posture changes. Otherwise proceed with normal non-destructive PR work.

### Final operating rule

> **One Nuvx product. A consistent native-feeling UX per device category. Use Rust for shared capabilities where it wins on evidence; use SwiftUI, Compose, or other appropriate native frontends where they win. Use the correct `lylicht/SFStudio` icon/motion system legally and efficiently. Fix and simplify before expanding. Deliver tested, reviewable code, not just reports. Never deploy production without approval.**
