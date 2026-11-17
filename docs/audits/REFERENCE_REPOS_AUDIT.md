# Reference repositories: what applies to nuvx

Checked 2026-11-16 against nuvx `main` at `e1cafdb`. Reference pages were read on the web; no reference code was copied. "Verified" means the nuvx code was read; nothing here was run on a device unless it says so.

Priority: P0 security or data loss, P1 wrong behavior users hit, P2 reliability or quality, P3 idea.

| Source | Status there | Problem | nuvx affected? | Evidence in nuvx | Pri | Action | Validation |
|---|---|---|---|---|---|---|---|
| BitChord PR 1802, party rejoin auth | Open, stacked | Rejoin matched by device ID alone handed out the member token | **Yes** | `core/worker/src/party.ts` `join()` did the same; desktop sends device ID as the broadcast `userId` | P0 | **Fixed**, nuvx PR 283 | New worker test; 28 pass |
| BitChord issue 1549, shuffle | Closed | External controller turns on native shuffle; app shows it off | **Yes** | `SessionPlayer` forwarded `setShuffleModeEnabled`; nuvx shuffle lives in `QueueShuffle` | P1 | **Fixed**, nuvx PR 285 | Compiles, Android suite; not tried with a real car or headset |
| BitChord PR 1805, download durability | Open | Pending downloads lost on process death | Not checked | `download/` has `Pending`, `DownloadService`, `PlaylistSyncWorker`; journal behavior unread | P1 | Investigate next | Kill-and-relaunch test |
| BitChord PR 1803, USB/DAC hardening | Open, unverified on hardware | Short packets, wrong-device streams, false quality labels | Not checked | nuvx ships `cpp/bitperfect` from the same lineage | P1 | Investigate | Needs a USB DAC |
| BitChord issue 1662, Automix decks | Open | Loop mixes always time out to crossfade | Not checked | `CrossfadeLoopPlanner`, `CrossfadeLoopMixController` exist | P2 | Investigate | Planner unit test |
| BitChord-PC issue 20, PipeWire 44.1 kHz | Open | Output opened once at 44.1 kHz, everything resampled | Partly | `audio/output.rs` opens the device at its default config and reads the rate back; it does not follow the track's rate | P2 | Investigate | `pw-top` on Linux |
| BitChord-PC issue 25, CJK fonts | Open | CJK text shows empty boxes on Linux | Likely no | `theme/fonts.rs` loads Noto CJK from standard paths | P3 | None unless reported | Screenshot on a distro without Noto |
| BitChord-PC PR 13, Windows PATH | Closed | yt-dlp fails on unusable PATH entries (WinError 448) | **Yes** | `ytdlp.rs` passed the inherited PATH and `--js-runtimes node`, so yt-dlp ran `realpath` on every folder | P2 | **Fixed**: child PATH keeps only folders that resolve, original spelling | `cargo test ytdlp` |
| BitChord-PC PR 17, WASAPI exclusive | Open | Feature: bit-perfect Windows output | n/a | Not present on desktop | P3 | Candidate feature | Needs Windows and a DAC |
| BitChord-PC PR 24, MPRIS | Open, no description | Volume, seek, two-way sync | Not checked | No `mpris` string under `app/desktop/src`; media keys live in `media.rs` | P2 | Investigate | `playerctl` on Linux |
| Hi-Res addon | No license | Provider adapters with failover | Concept only | nuvx has `hires/`, `SourceRegistry`, source fallback | P3 | Do not reuse code; quality labels must come from the decoded stream | n/a |
| Convx issue 153, stale local library | Open | Deleted files stay listed; wrong artwork | Not checked | Local library scanning unread | P2 | Investigate | Delete-then-rescan test |
| Convx PR 174, KMP cache | Draft, never built | Integration branch | No | n/a | P3 | Reject as a source | n/a |
| Vivi issue 917, Bluetooth distortion | Closed stale, no fix | Distortion after track change on one head unit | Unknown | Cannot reproduce without the hardware | P3 | Watch | n/a |
| Vivi issue 1055, quiet intros skipped | Closed | Caused by SponsorBlock, not skip-silence | Possible by design | nuvx has SponsorBlock settings | P3 | None | n/a |
| Vivi PR 772, double unbind crash | Open | `unbindService` twice on exit | **No** | No `unbindService` call in the phone app | n/a | Reject | grep |
| Vivi PR 938, SAF downloads | Open, stale | Download to a user folder | n/a | Feature idea | P3 | Candidate for Smart Offline | n/a |
| Metrolist issue 4763, offload deadlock | Closed, reporter says not fixed | Sink sleeps for offload and never wakes | **No** | No audio offload use in `playback/` | n/a | Reject | grep |
| Metrolist PR 4814, gapless | Open, review findings unresolved | Second player leaves a gap | **No** | With crossfade and Smart Fade off, `CrossfadeTransitionPlanner.planAutoTransition` returns before any second player, so ExoPlayer's own playlist (gapless) advances | n/a | None | A measured gap test on a device would still be good |
| Metrolist PR 4615, WebView leak | Merged | New hidden WebView per token reset, 300 MB each on Linux | Possible | Desktop builds WebViews in `turnstile.rs` and `login.rs`; reuse unread | P2 | Investigate | Process count across resets |

## The older BitChord feature list

`docs/2026-11-15-bitchord-audit.md` listed about 18 Android candidates. Reconciled against `main`:

- **Done:** sleep timer fade (1), loudness target (2), Advanced AutoMix (3), prefer lossless (4, already the default), playlist picker search (7), grid/list toggle (8, partly: no totals), undo dislike (12), thumbnail fallback (16 on desktop), LyricClock fix (10, partly).
- **Open:** channel switcher on Android (5), activity feed on Android (6), mood shelves (9), lyrics preview and provider work (10), canvas off switch (11), download notification states (13), WebDAV URL (14), Quick Settings tile (15), Atmos messages (17), player UI additions (18).
- **Licensing finding:** restored. The FFmpeg copyright is back in `cpp/dsd/dst/decoder.c`, from merged PR 281.

## Not read

Reference source trees themselves, discussions, and any issue not named above. Each "Not checked" row is a lead, not a finding.
