# Changelog

![nuvx: Music in full color](https://raw.githubusercontent.com/lylicht/nuvx/main/docs/assets/nuvx-music-in-full-color.webp)

Every nuvx release, newest first. Downloads and checksums are on each version's [GitHub release](https://github.com/lylicht/nuvx/releases).

## Unreleased

### Phone and computer

- **Nuvx Connect (new).** Control nuvx on your computer from your phone: see what plays there, play/pause,
  skip, and **Play this song on the computer** to move the phone's song (at the same moment) over. Each app has its
  own **Settings → Nuvx Connect** tab. On the computer, turn on **Allow remote control** there (off by default);
  only phones you paired and approved in Local Wi-Fi Sync can use it, encrypted, on your own network.
- **Copy downloads between your phone and computer.** **Settings → Sync & Connect → Copy downloads from …** copies
  the songs your paired computer downloaded onto the phone, and **Send downloads to …** sends the phone's
  downloads to the computer; each skips songs the other side already has. It uses
  the same encrypted pairing, so it only works between your own approved devices; the usual storage limit and free
  space rules apply.
- **Bluetooth as a second link (Windows computer ↔ Android phone).** When the phone and computer share no Wi-Fi,
  Sync, Nuvx Connect and copying downloads switch to Bluetooth by themselves, and back to Wi-Fi when it returns.
  The 4-digit code also finds a computer over Bluetooth. Needs Bluetooth on, the two devices paired once in the
  Bluetooth settings, and "Nearby devices" allowed for nuvx. Bluetooth is slower: copying a song takes longer.
- **Phone to phone (Android).** **Settings → Sync & Connect → Let other phones pair with this phone** makes this
  phone a host, like the computer: it shows a 4-digit code, you approve each phone and compare the 6-digit code,
  and paired phones then share nuvx playlists and copy downloads both ways. Turn on **Allow remote control** to let
  them play through this phone with the speaker button. Same Wi-Fi, encrypted. A phone that hosts cannot also sync
  with a computer at the same time. A playlist made on a paired phone shows up on the host within seconds, and the
  host no longer lists itself as a computer to control or copy from.
- **Desktop: a first-run intro** like the Android TV app's, on the same navy background: welcome, sign in to
  YouTube Music, choose a look (System, Light, Dark), listening options (autoplay, spatial audio, animated artwork
  and background, nightly updates), done, plus window material and Windows or macOS window buttons. It is a full
  page scaled to fit any window, with the official vector nuvx mark; nothing behind it can be clicked.
  Shown once on a new install; **Set up later** skips it. A **nightly** build shows it once for every new nightly you install, with all your saved data kept.
- **Desktop: swipe the expanded player down to close it**, like on the phone (let go past a fifth of the window,
  or flick). The intro also gets a **Your tabs** step to show or hide Radio and Podcasts.
- **Android: swiping a song to the queue adds it once** (it could appear several times; issue #328).
- **Android: Play starts a playlist in order again.** After a shuffled play, pressing Play on a playlist or album
  kept shuffling the next songs; now Play turns shuffle off and Shuffle turns it on (issue #329).
- **Android: Undo after renaming or reordering a playlist.**
- **Desktop: a song that fails to start is tried again** (after 350 ms, then 700 ms) before it is skipped, and
  skipping stops after 4 songs in a row, the same rules as Android. Both apps are tested against one shared set of
  cases (`core/contracts/stream-recovery.json`).
- **Desktop: smarter downloads.** They stop under 500 MB free on the download drive, and Settings → Downloads has a
  **Storage limit** (Off, 1–50 GB) with how much downloads use now, like on Android.
- **Desktop: equalizer per output.** Each output device you pick in Settings → Playback keeps its own equalizer
  (like on Android): switch from speakers to headphones and each gets its setting back.
- **Desktop: playlist files.** A playlist's **More → Export playlist…** saves it as M3U or CSV, and **Settings → Import
  → Import a playlist file** reads one into a nuvx playlist. Same formats as the phone, so playlists move both ways.
- **Desktop: Settings → Your data.** What stays on this computer and what leaves it (per service, and when), the
  data folder, clearing recent searches, paired phones, and **Copy diagnostics**: version, system, renderer, network mode and this run's latest
  warnings and errors, with links, tokens and your folder names removed. Copied only when you press it.
- **Android: Radio moved next to your profile** in the top bar (when Radio is on; hidden when it is off), leaving
  more room in the tab bar.
- **Contributor guide refreshed** (CONTRIBUTING.md): where everything is, the build and test commands for every app, the shared Rust core and its golden cases, and how releases and nightlies work.
- **Release notes open with the nuvx picture**, on GitHub and in the update notes inside the phone, TV and desktop apps.
- **The creator's name is written licht** everywhere in the apps, installer and pages.
- **A nightly every 3 hours.** When `main` has new commits since the last nightly, one is built (GitHub can start it
  late under load) and named *nightly N*, counting on after each stable release. Only the newest nightly is kept; older
  ones are deleted. Apps offer nightlies only with the nightly switch on and move on to the next stable release when it
  ships (stable → nightly → nightly 2 → stable 2).
- **Control your phone from the computer.** While a paired phone plays, the computer's Settings → Sync & Connect
  shows what it plays with Previous, Play/Pause and Next. The phone keeps its own queue; nothing to turn on.
- **A phone that pairs again replaces its old entry** on the computer (or hosting phone) instead of showing twice.
- **Sync & Connect shows once in Settings** on Android (the extra row in the category list is gone).
- **Pair with a 4-digit code.** The computer shows a 4-digit code in Nuvx Sync and Nuvx Connect; type it on the
  phone instead of an address and the phone finds the computer on your Wi-Fi. You still approve the phone on the
  computer and compare the 6-digit code. The address is shown too, and now skips VPN/proxy adapters.

### Android phone and tablet

- **A clear message when your network blocks YouTube's audio.** Some DNS services (for example 87.107.206.10 /
  87.107.206.11) let search, home and covers through but not googlevideo.com, the servers the songs play from, so
  songs showed up but never played, with no explanation. nuvx now tells you once, then points you to
  **Settings → Network → Privacy Connection** (Orbot or a local SOCKS5 app) or a VPN, which do carry the audio.
- **Import a playlist from an M3U or CSV file.** Library → Playlists → **Import file**, then pick the file. Files
  nuvx exported come back with the same songs in the same order; other players' files work too when their entries
  carry a YouTube link. Entries with only a title and artist (for example a Spotify playlist exported to CSV, or
  local files with names) are looked up on YouTube Music; only confident matches are kept, in file order. The
  playlist is saved on this device only, and nuvx tells you how many songs could not be found.
- **Copy diagnostics for a bug report.** **Settings → Your data → Copy diagnostics** copies the app version,
  phone model, Android version, network mode and the latest warnings and errors to your clipboard, with links,
  cookies and tokens removed. Nothing is sent anywhere; paste it where you want to report the problem.
- **Your data explains what leaves your phone.** **Settings → Your data** now gathers backup export/import, Copy
  diagnostics and Sync, with a short note on what stays on this device and what only leaves it when you turn
  something on.
- **A storage limit for downloads.** **Settings → Downloads → Storage limit** (Off, 2, 5, 10, 20 or 50 GB). Once
  your downloads take up that much space, new downloads stop with a message saying so; songs already saved stay.
  Off by default, so nothing changes unless you set it.
- **Remove songs from your nuvx playlists, with Undo.** The song menu in a playlist saved on this device now has
  **Remove from this playlist** even when you aren't signed in (before, it never appeared for these playlists).
  A bar with **Undo** puts the song back where it was.
- **Playlists work without signing in.** Signed out, **New playlist** now creates a playlist on this device
  (before, nothing happened), says "Saved on this device", and **Add to playlist** shows in the song menu so you
  can fill it. Signed-in behaviour is unchanged.

### Desktop (Windows, macOS, Linux)

- **Downloaded WebM songs play again.** Songs saved as WebM/Opus (downloaded on the computer or sent from a phone)
  were opened as AAC, failed at once and skipped to the next song. The format is now read from the file.
- **YouTube videos keep their original language.** Videos dubbed into other languages list every dub next to the
  original. nuvx used to take the first one listed, which could be a dub (Arabic, German, ...). It now plays the
  track YouTube marks as the original, and falls back to YouTube's default track when no original is marked.
- **How to use it:** turn on **Settings → Enable YouTube Search**, switch the search results to **YouTube**, and
  play any video. Nothing else to set.

## 1.0.3

Android version code 4 (phone, TV, Auto and Wear OS). iOS build 4. Desktop 1.0.3 for Windows, macOS and Linux.

This release makes nuvx faster and more reliable over Tor. How to set up Tor or a VLESS/REALITY app on each device:
**[Connecting from restricted networks](https://github.com/lylicht/nuvx/blob/main/docs/CONNECTING_FROM_RESTRICTED_NETWORKS.md)**.

### Android phone and tablet (also Android Auto)

- **Songs start much faster over Tor.** YouTube turns away many Tor exits. nuvx now notices that straight away and
  moves to a fresh exit instead of retrying the blocked one for about half a minute, and it tries up to four exits
  per song instead of two. In testing over Orbot, 8 of 8 songs played, most within 3 to 9 seconds (before: up to a
  minute, or not at all).
- **How to use it:** in Orbot choose a transport (Smart Connect, or Snowflake / obfs4 on blocked networks) and tap
  **Connect**. In nuvx open **Settings → Network → Privacy Connection**, choose **Orbot** and tap **Restart now**.
  Nothing else to set: nuvx picks new exits by itself. If Orbot ignores start requests from other apps, nuvx shows
  **Open Orbot**: open it and tap **Connect**.
- Local SOCKS5 (v2rayNG, Hiddify, NekoBox, Clash/Mihomo) is unchanged and keeps its normal retries.

### Android TV and Google TV

- New version (code 4) with the shared fixes. Use a VPN app on the TV for a protected connection.

### Wear OS

- New version (code 4). No network changes on the watch.

### Windows, macOS and Linux

- New version (1.0.3). No changes since 1.0.2; the desktop already switches Tor exits by itself
  (**Settings → Network → Proxy → Privacy Connection (Tor)**).

### iPhone and iPad

- Build 4. Use a VPN app for the whole device for a protected connection.

### Web player and browser extension

- Extension 1.0.3. Use your device's or browser's VPN/proxy for a protected connection.

## 1.0.2

Android version code 3 (phone, TV, Auto and Wear OS). iOS build 3. Desktop 1.0.2 for Windows, macOS and Linux.

Using nuvx from Iran or another filtered network? Read **[Connecting from restricted networks](https://github.com/lylicht/nuvx/blob/main/docs/CONNECTING_FROM_RESTRICTED_NETWORKS.md)** first.

### Android phone and tablet (also Android Auto, which plays from the phone)

**Network and privacy (Settings → Network → Privacy Connection)**
- **New: Local SOCKS5.** Use a proxy app you installed yourself (v2rayNG, Hiddify, NekoBox, Clash/Mihomo). Start it in *proxy* mode, choose **Local SOCKS5** in nuvx, type the app's port (v2rayNG `10808`, NekoBox `2080`, Clash/Mihomo `7890`), tap **Save** and restart nuvx. nuvx only connects once a real request to YouTube Music has gone through that port. If you use the proxy app in *VPN* mode instead, leave this setting **Off**.
- **Orbot works on filtered networks.** nuvx now waits up to 5 minutes for Orbot to connect (bridges like Snowflake and obfs4 are slow to start) and gives Tor's first answer more time. Turn on a bridge in Orbot first, then choose **Orbot** in nuvx.
- **Songs refused by YouTube over Tor are retried on another Tor exit.** YouTube blocks many Tor exits; nuvx moves to up to two fresh exits by itself. Over Tor some songs can still fail, and start slower than usual.
- **Screens no longer stay empty while Tor connects.** Home, artist pages and search wait for the connection instead of failing for good.
- **Music videos play at 360p** while Orbot or Local SOCKS5 is on, so they don't stall. You can still pick another quality in the player.
- **Nothing leaks when the connection drops.** Notification, lock-screen and Auto artwork now go through the protected connection too; before, they could go out directly when the proxy was down.
- **Casting is off while a protected connection is on**, because the TV would connect directly. nuvx says so when you try.
- The Network page explains what to do on filtered networks, in every app language.

**Sync**
- **Nuvx Sync (opt-in):** keep nuvx playlists in step across your devices through a sync server you choose. **Settings → Nuvx Sync**; nothing leaves the phone until you turn it on.
- **Local Wi-Fi Sync:** sync nuvx playlists straight with nuvx on your computer over the same Wi-Fi, with no account or internet. On the computer turn on **Settings → Nuvx Sync → Local Wi-Fi Sync**; on the phone open **Settings → Nuvx Sync → Local Wi-Fi Sync**, type the address the computer shows, check that both screens show the same 6-digit code and click **Approve** on the computer. Traffic is encrypted; remove a phone any time on either side.

**Playback**
- Crossfade stops when a call or the assistant takes the audio: before, the next song kept playing under the call.
- After nuvx is closed by the system, it reopens on the song that was playing, even far into a long queue.

**App**
- The theme follows your phone (light or dark) until you choose one in **Settings → Appearance**.
- **Hide Radio tab** and **Hide Podcasts tab** are now under **Settings → Miscellaneous**, where the tab tips point.
- Export a nuvx playlist as CSV from its menu (**Export playlist**).

### Android TV and Google TV

- Same playback fixes and app version as the phone (version code 3).
- No in-app proxy on TV yet: to use a protected connection, run a VPN app on the TV itself. A phone's connection does not cover a TV.

### Wear OS

- New version (code 3) alongside the phone. **No network changes** on the watch: when the watch controls the phone, the phone's connection settings apply.

### Windows, macOS and Linux

**Network (Settings → Network → Proxy)**
- A custom `socks5://` proxy now looks up addresses through the proxy (like `socks5h://`), so DNS lookups no longer go around it.
- A custom proxy address nuvx can't use now blocks connections instead of quietly connecting directly.
- **Use with a proxy app:** choose **Custom** and enter its address, for example `socks5h://127.0.0.1:10808` (v2rayN/v2rayA), `socks5h://127.0.0.1:7890` (Clash/Mihomo) or `socks5h://127.0.0.1:9150` (Tor Browser). **Privacy Connection (Tor)** finds a running Tor Browser or Tor by itself.

**Sync**
- **Local Wi-Fi Sync:** **Settings → Nuvx Sync → Local Wi-Fi Sync**. Turn it on, give the phone the address shown, and approve the phone when both screens show the same code. Paired devices, last sync and **Sync now** are on the same page. Off by default; it only answers devices on your own network.

**Animated artwork**
- Song canvases now come from Nuvx Community Canvas (canvas.nuvx.lol), Apple Music, Tidal and, if you are signed in to Spotify under **Account & integrations**, Spotify, in that order. Animated artwork stays under **Settings → Appearance → Animated artwork**.
- Downloaded clips are kept under 300 MB.

**Playback**
- Seeking while the next song is still loading now lands where you seeked.
- A bad folder in the Windows PATH no longer skips songs that need yt-dlp.

### iPhone and iPad

- Build 3 with this release's shared fixes.
- No in-app proxy on iOS yet: use a VPN app for the whole device (for example a VLESS/REALITY client, or Orbot for iOS).

### Web player and browser extension

- Extension 1.0.2.
- A web page cannot change your network: use a VPN or proxy for your browser or the whole device.

### Behind the scenes

- Continuous checks for Android, shared Kotlin, web and desktop (Linux and Windows) on every pull request, a weekly Android emulator run, and CodeQL code scanning including the iOS app.
- Sync server: first pairing approval wins, device cap at claim, whole-server size cap.
- Docs and comments: GlyphStudio is now SFStudio.

## 1.0.1

Android version code 2.

### Privacy Connection

- **Android:** Orbot is detected when it is already running. nuvx asked Orbot for its status in a way Orbot never answers, so the connection only succeeded if Orbot happened to change state while nuvx was listening. nuvx now asks with Orbot's start request, which Orbot answers with its status and SOCKS port, and if Orbot does not answer (its "allow background starts" is off) nuvx still uses a Tor that is running on port 9050. A real request through Tor must pass before anything is sent.
- **Android:** Privacy Connection now lives under **Settings → Network**, and a **Restart now** row applies a changed mode in one tap.
- **Desktop:** a Tor started after nuvx is found by itself. nuvx used to remember "no Tor" from start-up until **Find Tor** was pressed, so it stayed offline. The Tor check also tolerates whitespace in the Tor Project's reply.

### Playback

- **Android:** a failed song is retried after 350 ms and then 700 ms, not twice after 350 ms, so a brief network drop can recover.

### Playlists

- **Android:** export a playlist as an M3U file from its menu (**Export playlist**).

## 1.0.0

### Android release

- Rename the Android app identity to `com.licht.nuvx` and sign the phone, TV, Auto, and Wear packages with the `licht` certificate.
- Publish one APK for each Android target, with no separate GMS or FOSS assets. Set Android version code to `1` on every target.
- Use plain APK filenames and keep the update checker compatible with older release assets.
- Archive the original version-code-10 release as old Nuvx while retaining its tag target and downloads.
- macOS ships one universal disk image for Apple silicon and Intel in place of two. Old macOS download links still work.

### Updates

- Add **Nightly updates** to Android phone and TV and to desktop. It is off by default: while it is off nuvx only offers stable releases, and while it is on it also offers nightly test builds. A nightly install with the switch off moves to the stable build of the same version. iPhone, iPad and Mac only ever offer stable releases and have no switch.
- The updaters skip any release named "old" and any pre-release that is not a nightly, so an old build with a higher number is never offered to the new line.

### Fixes

- Keep the Android search dock and Mini Player anchored just above the keyboard. The dock applied the keyboard and navigation-bar insets on top of each other, so both elements jumped by their sum when the keyboard opened ([#279](https://github.com/lylicht/nuvx/issues/279)). iPhone already anchors its pill with a single keyboard inset and needed no change.

### Playlists

- Let users upload custom artwork for created, imported, downloaded, or YouTube Music playlists on Android, Apple platforms, desktop, and web. Artwork stays local to the current device or browser.
- Show device-only playlists in the Playlists shelf immediately after creation, including while the remote library is loading or signed out.

### Alternate audio sources

- Add optional Radio Javan search and AAC streams to Android and Apple clients; it starts disabled and appears after YouTube Music and JioSaavn in source order.
- Keep built-in sources ahead of user-added sources in Android and Apple clients, even when an existing install stored add-ons first. Every non-YouTube source can be switched off in those clients.

- Add optional JioSaavn and Radio Javan fallbacks to the web player and browser extension. Both start disabled, run after YouTube Music fails (JioSaavn first), need extension site access, and play as seekable files rather than live radio. The extension's new `source.fetch` bridge method only serves `www.jiosaavn.com/api.php` (`search.getResults`, `song.generateAuthToken`) and `rj-deskcloud.com/api2/{search,mp3}`.
- Fix Apple clients showing JioSaavn results and fallback regardless of the source switch; the legacy flag now follows the JioSaavn source switch and defaults off.

### Privacy

- Add **Privacy Connection (Tor)** to desktop's Network settings: route everything nuvx fetches through a running Tor Browser or tor service, resolving names through Tor and failing closed when Tor is unreachable. The connection test only passes once check.torproject.org confirms the traffic is Tor. Android gained the equivalent Orbot-based Privacy Connection in this release.

### Discord

- Change the default Discord activity action to **Listen on nuvx** and send it to `https://nuvx.lol/open`, which attempts to open the installed app and keeps a website fallback available.

## 1.0.0 (Previous app identity)

This section records every change since the v0.9.0 stable release.

### Android

- Add the **Enable YouTube Search** setting to Android TV. Turning it off removes YouTube results and returns the selected search filter to Music.
- Replace separate GMS and FOSS phone and TV releases with one shared universal APK for each app. Each APK includes armeabi-v7a, arm64-v8a and x86_64. Cast and Wear features work when Google Play Services is installed.
- Keep the old `-gms.apk` and `-foss.apk` download names as byte-identical aliases of the shared APK so existing 0.9.x installs can update.
- Update InnerTubeX to v0.11.7 across Android and Apple builds; the audio resolver now prefers YouTube Music SABR profiles when resolving audio-only streams.
- Add **nuvx Auto**, a standalone universal head-unit APK that reuses the TV app's large-focus interface, equalizer, and loudness normalization. Auto starts with stereo widening off for a conventional car speaker setup. Android Auto projection continues to use the phone app's media service; remove the phone-only Car Mode toggle.
- Expose the eXcor fallback switch in TV settings so it is available in the shared TV and Auto builds as well as the phone app.
- Serialize Android download stream resolution across queued tracks and report YouTube rate limits as temporary refusals instead of missing audio.
- Prevent update rollbacks: stable installs stay on stable; nightlies accept only a newer nightly, reject older stable releases, and can move to the next newer stable release.
- Keep Android nightlies at `0.9.0` / version code 9 and desktop nightlies at `0.9.0` until stable `v1.0.0` is published; browser extension nightlies use `0.9.0` with an increasing build component. Stable `v1.0.0` and its Android APKs use version code 10. Preserve already published nightlies as-is.

### iPhone and iPad

- Package version 1.0.0/build 10 for iPhone and iPad as `nuvx-1.0.0-universal.ipa`; the unsigned package must be signed with a sideloading tool such as Sideloadly, AltStore Classic or SideStore.
- Link the IPA download and install/update guide from **Settings → Version & Updates**, alongside the stable-release check and release notes. Install over the existing app to preserve local data; a free Apple Account signature needs refreshing before its seven-day expiry.
- iOS cannot install this unsigned IPA from inside nuvx. Finish installation and updates with the same sideloading tool and Apple Account.

### Music video playback

- Keep music videos aligned with the playing song on Android and the web player, with tighter offset checks on desktop and iOS.
- Reject ambiguous synchronization offsets and hide a video when reliable audio alignment cannot be established.
- Preserve playback timing through seeks, pauses and shared views, with regression coverage for alignment and animated cover playback.

### Desktop

- Keep stable installs on stable releases, offer only newer nightlies to nightly installs, and prevent a previous stable release from replacing a nightly.
- Clarify and correct window material behavior: macOS now distinguishes the opaque Window backdrop from translucent Under Window; Linux Tinted remains opaque while Translucent follows compositor behavior.
- Keep animated cover playback active while any visible desktop view still uses it.
- Select the correct Intel or Apple silicon macOS download when the browser reports its architecture, and fall back to the release chooser when it is unknown.

### Web, downloads and release packages

- Add direct Android TV and Wear OS downloads to the web Downloads page.
- Route phone, TV, Auto and iPhone downloads to their packaged assets; keep the browser extensions, desktop installers and Android packages under the same release tag.
- Preserve compatibility with the published v0.9.0 GMS/FOSS phone and TV assets, and add direct routes for the shared 1.0.0 phone, TV, Auto and iPhone packages.
- Serialize batch YouTube stream lookups on Android, iOS, and desktop to reduce request bursts while keeping audio transfers concurrent; report temporary YouTube refusals separately from missing audio.
- Detect the website visitor's device and link supported stable/nightly downloads to the matching asset; unavailable or unknown devices return to the on-site package list.
- Add Telegram to the community invitation and settings links across phone, TV/Auto, iOS, macOS, desktop and web.
- Keep `robots.txt`, the canonical sitemap, and the IndexNow key in the website source so Google and Bing can discover the public pages after deployment.
- Preserve silent Windows installer switches and put a time limit on the Windows nightly installer packaging job.
- Record startup smoke-test results for the phone, TV, Auto, and Wear APKs, including the limits of the available emulator checks.
- Align package metadata to version 1.0.0 and Android version code 10 across Android, iOS, desktop and browser extensions.

### Licensing and maintenance

- Include third-party license notices with desktop packages, add the LGPL-3.0-only license text for the existing bit-perfect component, and clarify AGPL and bundled PoToken client attributions.
- Include project license texts plus native and AutoMix model notices in Android phone, TV, Auto, and Wear APKs; merge duplicate Android dependency license resources instead of retaining only the first copy.
- Correct the Android glass pill's GPL-3.0-only declaration, preserve its MIT motion-model attribution, and ship the Kira MIT notice with the web app.
- Keep the bundled iOS InnerTubeX notice aligned with its vendored v0.11.7 source revision.
- Correct Linux backdrop, download architecture and release documentation; extract Android detail playback handling into a focused controller without changing its intended behavior.

### Playlists

- Add **Create Playlists in Nuvx Only** across Android phone/TV/Auto, iOS/macOS, desktop and web. It is off by default; when enabled, new playlists are stored on the current device or browser without creating or changing YouTube Music playlists. Android, Apple and web Spotify-import flows also honor the setting.
- Keep local playlists available after restart, playable and available in add-to-playlist pickers. Local playlists are device-specific and do not sync across nuvx installs.

## Old build 0.9.0

### Desktop

- Account for the active audio output buffer when presenting video frames, and keep Now Playing lyrics clear of artwork behind the text.
- Measure and cache a separate audio/video start offset for each desktop music-video pair.
- Discover stable and nightly desktop releases across channels and select assets by release tag.

### Android

- Offer newer stable and nightly releases across channels, including newer nightlies, with the matching GMS or FOSS APK.

### Web and browser extensions

- Load the unsigned Firefox ZIP directly as a temporary add-on in Firefox Developer Edition or Nightly; its manifest is at the archive root.
- Publish Chrome and Firefox extensions with the desktop, Android mobile, Android TV, and Wear OS release assets.

### Releases

- Set the next release version to 0.9.0, with Android version code 9.
- Build one multi-architecture Windows installer and Linux package, separate Intel and Apple silicon macOS DMGs, universal GMS and FOSS phone and TV APKs, a Wear OS APK, and both browser extensions in the same release.

## Old build 0.8.0

### Android

- Reconnect Listen Together sockets after server closes and stop session workers cleanly.
- Keep new account, download, Spotify import, and Discord invite resource keys in sync with the current locale fallback text.
- Secure TV sign-in and remote routes, bound request bodies and client connections, and render remote search metadata as text.
- Preserve the TV remote client inactivity timeout while enforcing the total request deadline.
- Advance the launch animation using elapsed time so dropped frames do not keep the intro open.
- Keep Now Playing open when an artist lookup fails, and initialize signed-in account state reliably.
- Preserve the rest of an album queue when shuffle is enabled mid-album.
- Move Enable YouTube Search into Content preferences, and give Hide Podcasts tab the same GlyphStudio icon and size as Podcasts navigation.
- Move player creation, audio-session ownership, and buffer configuration out of PlaybackService without changing playback settings.
- Move cast stream URL resolution out of PlaybackService while retaining the same direct and downloaded-source fallbacks.
- Publish signed Android nightly and stable builds with a stable update handoff.
- Reorganize playback, queue persistence, source resolution, recovery, audio routing, crossfade and transition planning, audio cache, lyrics, Android Auto, Listen Together HTTP, downloads, account, library, details, settings, and app dialogs into focused controllers and components. These changes preserve the existing UI and behavior.
- Move Now Playing album, artist, and playback-source navigation out of the app shell into a focused controller, preserving route and tab behavior.
- Move download preparation and permission routing out of the app shell, preserving queue, cache, and prompt behavior.
- Split TV profile selection, PIN protection, and account settings dialogs into a focused internal module with no UI changes.

### Desktop

- Publish a universal Windows installer and Linux package, Intel and Apple silicon macOS installers, universal GMS and FOSS APKs for Android phones and TV, and a separate Wear OS APK at version code 8.
- Reorganize playback and audio processing, backend search and catalog loading, downloads and transfers, lyrics and karaoke, Listen Together, settings, navigation, Now Playing, and shared widgets into focused modules. These are internal refactors with no intended user-facing behavior change.
- Move desktop decoder selection and audio source adapters into a dedicated stream submodule while preserving the existing stream API.

### Web and browser extensions

- Add Podcasts and Radio to the web player and fix public podcast search filtering.
- Split web player settings persistence out of playback state management without changing the `nuvx:player` saved format.
- Move browser radio autoplay refill and retry state into a dedicated player controller.
- Move browser media-session metadata, actions, and position updates into a dedicated controller.
- Enforce the 40 MB automix audio limit while streaming responses.
- Add a reproducible Firefox source build and source archive for AMO submission, and release the Chrome and Firefox extensions as version 0.8.0.
- Link the website releases section to the latest Android nightly and share the showcase playback control.

### Listen Together worker

- Bound JSON and multipart request bodies, retry conflicting canvas-index updates, revoke stale sockets on device rejoin, and reject non-finite track durations.

### Privacy and maintenance

- Publish the full-project privacy notice.
- Remove comments from first-party source files.
- Extract shared lyrics, Listen Together, party synchronization, and YT Music home feed, version matching, search, playlist, and history handling into dedicated modules.
- Split Innertube account, playback, search, browse-header, artist-header, and library-state parsing into dedicated modules.

## Old build 0.7.0

### Desktop
- Music videos play in Now playing: the picture in a 16:9 frame, in step with the song, through seeks and pauses (Windows).
- Switch to video and Switch to song, both ways: any song to its music video, any music video to its song.
- A light glides along the progress line while a song or its video loads.
- Music video quality, 1080p to 360p, in Now playing's menu and Settings; "Upgrade audio quality" names the audio one.
- Load songs and videos together (off by default): the other version loads as a track starts, so switching is instant.
- The real YouTube Music cover instead of the video thumbnail, everywhere a music video shows.
- Animated artwork for single songs as well as albums, from Apple Music and now Tidal, with a setting to turn it off.
- Last.fm: Send now playing and Primary artist only.

## Old build 0.6.0

### Downloads on desktop

Albums and playlists get a Download button beside Search, and artist pages get a Download pill that saves every release the page lists. Songs you already have are skipped, and three download at a time.

### Lyrics

nuvx now picks the most finely timed lyrics it finds: syllable by syllable first, then word by word, then line by line. On Android, **Prioritize syllable sync** is on by default.

### Desktop

- The player in the top bar keeps its sharp, solid display while a song plays, as Apple Music's does, instead of turning glassy.
- The red, yellow and green window buttons are the default. You can still switch to Windows buttons in Settings.
- Dolby Atmos is on by default.
- New in Settings: **Reduce animation**, **Hide Quick picks** and **Hide videos from Home**, as on Android.

The new defaults apply to fresh installs. If you already chose window buttons or Atmos, your choice is kept.

### Android

Song recognition has a new waveform-and-microphone icon.

## Old build 0.5.0

### Podcasts

Desktop and Android phones and tablets get a Podcasts tab beside Radio, with shows and episodes from YouTube Music. The first time you open Radio or Podcasts, nuvx asks whether to keep the tab. Either one can be hidden later in Settings.

### YouTube search

Turn on **Enable YouTube Search** in Settings to add a YouTube switch over your search results, for videos that YouTube Music doesn't carry. It's off by default.

### Expanded player

- Queue and Lyrics now sit beside Repeat.
- Shuffle replaces Start radio in the ⋯ menu.
- On desktop, Lyrics sits on the far left, before Shuffle.
- On desktop, a music video can switch between the video and the song from the ⋯ menu.

### Desktop

- The player in the top bar takes after Apple Music's: a sharp display in the middle with the song, its artwork and a thin progress line, the controls to its left, and volume, lyrics and up next to its right. Volume opens its slider when you hover over it.
- Android's equalizer comes to desktop, with its Dynamic tone pad, seven Manual bands and presets, and balance. Find it in Settings.
- Desktop gets Android's spatial audio, on by default.
- The red, yellow and green window buttons close, minimize and maximize, as the Windows buttons do.
- Scrollbars are gone. Pages still scroll with the wheel, trackpad or touch.
- The app's own window background is now called Dynamic. Your choice is kept.
- Ctrl+F opens Search from any page, ready to type. F shows full-screen lyrics whenever a song is loaded, and keeps working after you click a button.
- Release notes show pictures, tables, numbered lists, quotes and line breaks as GitHub does. Android's notes keep their line breaks and pictures too.

### Websites

nuvx.lol has this changelog at [nuvx.lol/changelog](https://nuvx.lol/changelog). nuvx.lol and the web player now wear burgundy.

## Old build 0.4.0

### Radio

Desktop now has Android's Radio tab. Pick a country (yours comes first) to hear its most played live stations, or search all of them. The broadcast icon in the sidebar bounces like the other tabs and pulses while a station is on air.

### Software Update

When a new version has downloaded, desktop now shows it the way Android does: a Software Update alert with What's New, and Install Now or Later.

### Cleaner source

Dead code, unused theme thumbnails and deprecated calls are gone from the desktop app.

![Music in full color. nuvx Now Playing framed by cobalt and copper panels.](https://github.com/lylicht/nuvx/raw/7e852a9/docs/nuvx-banner-now-playing.png)

<p>
  <img src="https://github.com/lylicht/nuvx/raw/7e852a9/docs/nuvx-banner-desktop.png" width="49%" alt="Make room for music. nuvx Desktop showing Home with the lyrics panel open.">
  <img src="https://github.com/lylicht/nuvx/raw/7e852a9/docs/nuvx-banner-desktop-lyrics.png" width="49%" alt="Lyrics, full screen. nuvx Desktop with red album artwork beside synced lyrics.">
</p>

## Old build 0.3.0

### Moods and genres in pictures

On desktop, the Moods and genres tiles on Search and Explore now carry cover art from the playlists they open, in the same duotone style as Android. The tiles appear at once and their pictures fill in a few seconds later.

## Old build 0.2.0

<img width="1672" height="941" alt="nuvx desktop with synced lyrics" src="https://github.com/user-attachments/assets/df21ea8e-6f5c-406a-af7d-db949dfe958a" />

### Meet nuvx for desktop

nuvx 0.2.0 brings your music to Windows, Linux and macOS. Explore YouTube Music by mood or genre, follow synced lyrics beside the expanded player, and listen with friends through Listen Together.

The desktop app keeps playback controls at the top and gives Up Next and Lyrics their own collapsible panels. Make it yours with an icon-only sidebar, animated GlyphStudio icons, and window materials that fit your platform.

This release also includes hotfixes for Android phones, tablets and TVs.

## Old build 0.1.0

<img width="1672" height="941" alt="nuvx Now Playing" src="https://github.com/user-attachments/assets/bfd50b52-0543-467f-a501-edb4214073b4" />

The first nuvx release, for Android phones and tablets, Android TV and Wear OS. Both APKs are universal and run on Android 8.0 or newer. Prefer the browser? Use the web player at [web.nuvx.lol](https://web.nuvx.lol).
