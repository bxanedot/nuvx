# TASK INDEX — 27 AUTONOMOUS TASKS (00–26)

The agent must read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, and `RUST_ANYTHING_INTEGRATION.md` first. Task 00 is mandatory before Task 01. Files under `tasks/` are ordered by prefix. Automatically proceed to the next READY task and repeat per-target subtasks until every in-scope item is verified.

| Step | Title | Required result |
|---|---|---|
| 00 | Investigate rust-anything | Read upstream GitHub; verify limits, safety, isolated applicability |
| 01 | Contract | Durable invariants and status ledger |
| 02 | Full inventory | Feature/platform/source matrix |
| 03 | UI/UX baselines | Golden images + interactions, missing evidence flagged |
| 04 | Size/performance baselines | Measured artifacts & app metrics |
| 05 | Canonical Rust workspace | One shared core; no copy-pasted implementations |
| 06 | Safe platform bindings | FFI + WASM contract and tests |
| 07 | Models/serialization | Golden cross-platform data contracts |
| 08 | Persistence | All storage subsystems, upgrade/rollback compatibility |
| 09 | Networking/auth | Canonical portable code; native secure credential adapters |
| 10 | Music catalog/metadata | Same search and provider behavior |
| 11 | Playlists/library | All existing and Nuvx-local playlist behavior |
| 12 | Queue/playback state | Deterministic shared Rust logic, same audio behavior |
| 13 | LAN/cloud/listen sync | Security, protocols, consent, old clients preserved |
| 14 | Downloads/cache | Resume/offline/cache safety across platforms |
| 15 | Audio/DSP | Shared algorithms; original media systems preserved |
| 16 | Restricted networking/privacy | Fail-closed, DNS, proxy, security parity |
| 17 | UI design foundations | Exact extracted tokens, fonts, symbols and motion |
| 18 | Desktop | Windows/Linux/macOS Rust core integration; same egui UI |
| 19 | Android phone/tablet | Screen-by-screen UI parity and native integration |
| 20 | TV/Auto | Each TV, Auto projection and standalone Auto surface |
| 21 | Wear OS | Watch interface, battery and companion parity |
| 22 | iPhone/iPad | Rust core + exact SwiftUI/Slint visual behavior |
| 23 | Web player | Rust/Leptos/WASM where exact DOM/style/UX parity |
| 24 | Browser extensions | Chrome + Firefox functionality, JS glue only as required |
| 25 | Services/other code | Cloudflare Worker, sync server, Discord bot, website APIs |
| 26 | Final verification and cleanup | All gates pass; only verified old code removed |

**Not a “finish one component then move on forever” plan.** Steps 08–25 are portfolio work: enumerate every applicable module/screen/target, implement each in small iterations, and checkpoint. The full program acceptance gate in 26 must reject any incomplete target.
