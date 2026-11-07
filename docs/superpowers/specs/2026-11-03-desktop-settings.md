# Desktop App and Settings Experience

## Goal

Adapt the whole Nuvx client to the user-approved desktop concept and make every shown setting control a real, persisted Nuvx capability.

## Product behavior

- Keep the existing Nuvx routes and shared settings state. Desktop adapts their presentation; it does not create a second player or a separate preference store.
- Match the latest approved visible desktop reference: left navigation, compact horizontal player controls at the top, and a wide central route. Show the Queue/Lyrics Listening inspector by default when the window can preserve a 560dp route and 240dp inspector; keep its header toggle for hiding it.
- Reuse live Nuvx catalog and player data only. Do not invent playlists, account state, notifications, download state, or playback modes to fill visual space.
- The bottom player bar controls the current Nuvx player, including play/pause, previous/next, seek, and volume. The inspector keeps Now Playing visible and switches between the real queue and lyrics.
- Sidebar has a persistent music-search field, then Home, Explore, Your library, and Search; after a divider it shows History and On this device when available, with Settings pinned at the bottom. Do not show Downloads until authorized offline downloads work. Liked songs remain in Library; Queue remains in the Listening inspector.
- Desktop Search uses that persistent sidebar field as its only query input; omit the redundant page title and second search box, while retaining Nuvx's result filters and recent searches. Enter submits the current query; Enter on an empty field clears the query and results.
- Desktop Home, Explore, Library, and Search begin directly below the transport bar without a duplicate route-title toolbar. Explore uses horizontally clipped, artwork-backed category cards at the reference scale; the Queue/Lyrics inspector toggle stays in the top transport when the available width supports the inspector.
- Desktop Home keeps the Listen Together action as a compact first banner and omits a redundant page-title row. Follow it with real history and seed-radio shelves; do not invent sign-in, album feeds, or personalized recommendations that Nuvx cannot provide.
- Desktop Library starts with three horizontally clipped 300×190dp listening-summary cards, then compact separated rows and recent tracks. Cards use only the local recent-history count (capped at 50), newest artist, and newest track; unavailable account, download, and cloud-playlist actions remain absent.
- Match the visible desktop grayscale surfaces: medium-gray route canvas, darker left rail, and compact charcoal transport bar, while retaining Nuvx's own mark, icons, product behavior, and readable text.
- Keep the desktop transport at 56dp high: Nuvx mark and shuffle/previous/play/next/repeat controls on the left, current track and seek in the wide center panel, volume on the right, and Queue/Lyrics inspector toggle only when the window can fit the pane. Keep the route at 560dp; let the rail range from 144–200dp and the inspector from 240–320dp, hiding the inspector below 944dp.
- Opening Settings preserves the selected primary route highlight, as in the visible desktop reference.
- Group settings into Streaming, Appearance, Listening Together, Lyrics, Library, and About.
- Add persisted playback-speed choices from 0.5× through 2× in 0.25× increments and apply them through each platform player.
- The compact desktop player includes shuffle-up-next, previous, play/pause, next, repeat, seek, and volume controls. Keep the volume control in the top bar and honor the persisted Hide volume bar preference; default is visible.
- Streaming exposes InnerTubeX/eXcor selection, current engine health, and automatic fallback.
- Appearance exposes the existing full-bleed cover preference and a real switch for community Canvas playback.
- Lyrics exposes the default provider, per-provider enable/disable, source order, and a preference for source-supplied word timing. The selected player provider persists across songs and restarts. lrc.red remains the fresh-install default and first source.
- Lyrics Settings exposes a persisted text-size choice (Small, Standard, Large, Extra large), and the selected size scales timed and plain lyrics in Now Playing and the desktop Listening inspector.
- Library rows navigate to the working local-library and liked-songs pages. Listen Together opens the existing room flow. About links retain their current destinations.
- Reordering, switching, and selecting providers takes effect on the next lookup; the lyrics chooser in Now Playing uses the same persisted preference.
- Preserve keyboard activation, visible focus, screen-reader names and values, and comfortable hit targets.

## Data and edge behavior

- Store provider order and disabled names as newline-delimited values through `KeyValueStore`; ignore unknown or duplicate provider names when loading.
- Keep at least one lyric source enabled from the Settings UI. A malformed saved source list must normalize to the registered providers without crashing.
- A disabled default source falls back to the first enabled provider. If none are registered, show a truthful empty state.
- A user-selected source is attempted first; on no result or failure the remaining enabled providers follow the configured order. Real word-timed results win over earlier line-only results when the preference is enabled.
- Canvas disabled means no community lookup and no Canvas decoder attached; music playback continues normally.

## Scope and constraints

- Implement in shared Kotlin/Compose so desktop and existing mobile/tablet clients use the same preferences.
- Keep the supplied Nuvx brand and icon assets.
- Latest user instruction (2026-11-03) authorizes matching the publicly visible reference UI directly at high fidelity, including its layout, settings, and motion. Author all Compose UI independently and use Nuvx-owned assets.
- Do not expose controls for behavior that is unavailable, unauthorized, or still blocked.
- Reference settings inventory observed on 2026-11-04: avoid-repeat within one autoplay session, tray icon, keep playing when closed, hide volume, app language (system default), and player diagnostics (codec, bitrate, sample rate). Nuvx implements hide-volume and tray/keep-playing controls. Radio-session repeat avoidance is a persisted default-on setting wired to the actual radio refill session. Language localization and complete decoder stats remain hidden until their behavior/data exist.
- Settings seen in other apps are requirements only where Nuvx has the corresponding working behavior. Audio Auto/Low/High profiles and video Auto/Data saver/High requested-height caps are persisted and wired to their respective extractors; video caps are not exact-resolution guarantees and video runtime remains blocked at stream resolution. Radio-session repeat avoidance is implemented and persisted; generic autoplay, crossfade, downloads, account linking, notifications, exact video resolution selection, and delivered-resolution reporting remain unavailable.
- Do not incorporate excluded-project code, assets, dependencies, private endpoints, or undocumented token flows.
- Preserve the current dirty worktree and do not commit, push, deploy, or discard changes.

## Acceptance

- Lyrics preferences round-trip through `KeyValueStore`, discard unknown provider names, and maintain stable defaults.
- Provider order, enablement, preferred source, and word-timing choice affect the actual `LyricsChain` lookup.
- Settings changes persist after recreating the app composition.
- Community Canvas preference gates real Canvas lookup/rendering.
- Playback speed round-trips through `KeyValueStore` and is applied by Android Media3 and desktop VLC playback.
- Lyrics text size round-trips through `KeyValueStore`, defaults safely to Standard, and scales timed and plain lyrics in the shared player and desktop inspector.
- Desktop shared tests and Windows app-image packaging pass; Android mobile, TV, and Wear debug builds/unit tests pass; `git diff --check` is clean.
- Visual checks remain separate evidence from builds. Record which screen and behavior were opened; one checked Settings control does not verify whole-app parity.

## 2026-11-04 desktop reference update

The user confirmed that the visible desktop layout can be matched closely. Nuvx keeps its own brand, icons, code, and product behaviors. The shell now places transport at the top, gives the active route the main width by default, exposes Queue/Lyrics in an optional inspector, and adds a History route backed by local `History.played` data. Tray visibility and keep-playing-when-closed are persisted and wired to a native system-tray menu. Their runtime behavior still needs a fresh Windows app build and visual check. Radio-session repeat avoidance is wired to an active radio station; localization and full decoder diagnostics remain unfinished.

## 2026-11-04 reference Settings inventory

Read-only desktop inspection found these visible groups and controls: account/integrations; Listen Together; ordered streaming sources plus an add-source action; audio quality (Low, Medium, High, Lossless); download quality (Standard, High, Lossless); output precision (16-bit PCM, 32-bit float); and audio output (System default and device selection). The Settings title and search field share the top page header.

Nuvx places its Settings filter in the matching desktop header and retains the in-page filter on phone/TV. It offers only real Nuvx engine choices (InnerTubeX/eXcor), persisted provider audio quality, lyrics source selection/order, Canvas and playback preferences, and desktop tray/window behavior. Account linking and source importing are absent because they are not implemented. Download-quality controls are absent until authorized offline downloads exist. Lossless and output-precision choices are absent until a backend can verify and apply them. Per-device audio routing is also absent until a supported player API can enumerate and select devices. The reference page was observed without changing its settings. On 2026-11-04, Nuvx's title/search and visible Settings rows were checked in the initial 980×700dp window. A later maximized 1080×1920 review covered Settings from Playback through About. No controls were changed. Settings behavior, regular windowed layout, and full-page pixel parity remain unchecked.

The user-provided extreme-fidelity requirement also explicitly calls for adjustable lyric size. Settings now persists Small, Standard, Large, or Extra large and applies the selected scale to synchronized and plain lyrics in the shared player and desktop inspector; the source timing remains unchanged.

## 2026-11-04 radio Settings behavior

“Avoid repeats in radio” defaults on and applies to a real `RadioStation` launched from Now Playing. The station refills near the end of the queue, tracks all IDs even when repeats are allowed, and stops if unrelated playback replaces its current track. The switch persists through the shared store on desktop, Android, and TV.

## 2026-11-04 whole-interface matching clarification

Treat the approved visible desktop interface as the whole-app fidelity target: match its observable layouts, settings organization, dimensions, and motion as closely as practical. This updates the visual target only. All Nuvx UI remains independently authored with bxane-owned icons/assets; unavailable or unauthorized behaviors stay absent rather than being simulated.
