# Android release evidence

Checkpoint: 2026-11-06. **Release readiness is not established.** This maps the master spec's video definition of done to current evidence. Active delivery scope is Android phone/tablet, TV and Wear; other KMP platforms remain deferred rather than complete.

| Requirement | Evidence now | Remaining acceptance |
|---|---|---|
| Actual music-video playback; primary extraction | Android `PlaybackService` and `VideoSurface` exist and compile | Authorized source unavailable in the recorded live check; no private endpoint or undocumented token workaround permitted. See [video decision](VIDEO_PLAYBACK_DECISION.md). |
| Song/video switching | Shared matcher/resolver and media-kind routing exist; host checks pass | Real switch, preserved position, stable audio and failure recovery on device |
| Stable audio | AndroidPlayer, restoration and Stop/Play host regressions pass | Sustained audio, focus, Bluetooth/headset, interruption and background behavior on device |
| Backend capability restrictions | Router distinguishes video capability | Verify each enabled provider against documented authorized capability; never infer support from search success |
| Animated artist background | `ArtistPage.ArtistHero` currently renders static artwork and a gradient | Eligible owned/authorized motion source, integration and lifecycle/data/battery policies are missing; static fallback is not animation completion |
| Artist video discovery/categories/catalogue | Artist video shelves, categories, filtering, ordering and paging exist | Complete authorized live catalogue and real video playback; local metadata browsing is not proof of provider completeness |
| Browser queue correctness | Two actual-screen tests verify category/search/sort, nonzero selected index, empty message and Clear in wide/narrow layouts | Android touch/TalkBack and live paging/video acceptance |
| Video search | Shared screen/router path exists; true empty, provider failure and collection-only feedback checked | Authorized live results, full relevance and playback acceptance |
| Unified music/video queue | Shared Track media kind and one Android MediaSession queue | Native mixed queue transitions, seek, repeat and resource cleanup |
| Glass mini-player with video | Shared player UI and Android surface compile | Device gestures, expansion/collapse, contrast and audiovisual continuity |
| Video quality | Preference and routing exist | Verify selected quality on actual authorized source; four large-text Settings layout tests passed |
| Lyrics/Canvas coexistence | Independent shared lyrics and Canvas paths exist | Real video/lyrics sync, reduced motion, lifecycle and decoder contention |
| Artist/video radio | Existing radio paths can construct queues | Authorized live provider results, queue semantics and playback; do not call metadata-only checks native verification |
| PiP/background | Android eligibility helper and MainActivity hooks compile; host unit tests pass | Actual entry/exit, controls, service lifecycle and background audio/video on supported devices |
| Authorized video downloads | No eligible authorized full-catalogue flow verified | Authorization and storage rights before implementation; see [offline limitations](OFFLINE.md) |
| Automated checks | Published 1014d2b checkpoint including independent switch/motion: shared 199 passed/10 skipped; mobile/TV/Wear each 25 passed, zero failures/errors; Android lint passed | Ten shared skips remain unverified; checks cover their assertions only |
| Real-device testing | Current APK assembled; latest adb inventory has no devices | Connected device/emulator installation, media playback, accessibility and visual comparison; do not start an emulator under current standing instructions |
| Whole-app UI/settings fidelity | Targeted onboarding, Settings switch, artist browser and search evidence recorded in [UX verification](UX_VERIFICATION.md) | Whole-app comparison at phone/tablet/TV/Wear sizes, navigation/motion/input and every implemented setting; targeted passes are not whole-app parity |
| Source/assets and distribution | Dirty work preserved; current edits independently authored | Retain exclusions and owned assets; existing symbol distribution licence and signing/publishing gates remain open in [blockers](BLOCKERS.md) |
| Accurate delivery report | External handoff and SESSION_STATE contain exact build/test/install evidence | Keep these synchronized; never advance APK build time for compile-only/test-only work |

Published 6eb2f7f has passing Windows/Linux/macOS application jobs in run 42283773416 and worker checks; the duplicate application run still has macOS pending at this checkpoint. Next acceptance work is actual Android UI comparison when an authorized device is available. The independently authored switch and player motion passed focused regressions and Android compilation; no pixel or device fidelity is claimed. Native video acceptance, download authorization and animated artist backgrounds remain separate unfinished milestones. Do not add inactive settings merely to make the settings inventory look complete.

Artifact metadata belongs in external SESSION_STATE.json, not this document. The retained APK includes search retry, Canvas, Home and the independent switch/player replacement. Consult external state for its exact build provenance. A debug build is a preview, not a signed release.
