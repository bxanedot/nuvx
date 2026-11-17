# Privacy Connection

Routes nuvx's own network traffic through Tor. Off by default. No nuvx server, account or telemetry is involved.

## Status

| Part | State |
|---|---|
| Android: connection manager, failover, fail-closed routing | Done, unit tested |
| Android: Orbot backend | Done; **not yet run against a real Orbot** |
| Android: Settings section (19 languages) | Done; not yet seen on a device |
| Android: built-in Tor + Snowflake | **Not built.** The option is hidden (`PrivacyNetwork.EMBEDDED_AVAILABLE = false`) |
| Android TV / Auto | Share the routing code; no settings UI, so it stays off |
| Desktop: Privacy Connection (Tor) proxy mode, fail-closed, verified via check.torproject.org | Done, unit tested; **not yet run against a real Tor** |
| iOS, web | No in-app routing (see below): Orbot on iOS is a device-wide VPN; the web player follows the browser |

Until built-in Tor exists, a user needs the Orbot app, and Automatic mode is not offered.

## For users

Settings → Privacy Connection → choose **Orbot**, then close and reopen nuvx. Orbot must be installed, running and connected.

- It protects nuvx only. It is not a VPN and does not cover other apps.
- Services you sign in to (YouTube Music, Spotify, Discord, Last.fm) still know it is your account.
- Tor is slow. High audio quality may buffer; lower the streaming quality if it does.
- If Tor is unreachable, nuvx stops loading. It does not fall back to a direct connection.
- Tor relays can see limited connection metadata; this does not make anyone anonymous to the services they log in to.

## Desktop

Settings → Network → Proxy → **Privacy Connection (Tor)**. nuvx looks for a running Tor on this PC: Tor Browser (SOCKS port 9150) first, then a system `tor` service or Orbot-alike on 9050.

- Everything nuvx fetches goes through it as `socks5h`, so site names are resolved by Tor, not the PC: songs, search, artwork, lyrics, downloads, sign-in windows (through the local relay), yt-dlp and Listen Together.
- **Fail closed**: with no running Tor, the proxy points at a closed loopback port, so requests fail instead of going direct. Nothing falls back to a direct connection.
- **Test connection** only reports success after `check.torproject.org` confirms `IsTor: true` through the proxy, then checks that YouTube Music answers.
- When YouTube Music refuses a Tor exit, the existing exit-switching asks Tor for a new circuit (SOCKS username isolation) until one works.
- Like the other proxy modes, a change applies from the next start.

Tests (`cargo test`, `proxy::tests`): fail-closed resolution, port probing, save/load, a SOCKS5 handshake that must carry the host name, and an end-to-end check that a request in Tor mode with no Tor fails rather than reaching a live local server directly. **Not verified: a real Tor** (this build container cannot reach the Tor download servers).

## iOS and web

- **iOS**: Orbot for iOS is a device-wide VPN, so turning it on routes all of nuvx's traffic through Tor with nothing to build in the app. In-app, per-app routing like Android's is not possible without a network extension and is not planned for now.
- **Web player**: the page follows the browser's own connection. Use Tor Browser (the Firefox extension works there) or a browser configured for a Tor SOCKS proxy.

## How it works

`app/android/mobile/src/main/java/com/licht/nuvx/net/privacy/`

- `PrivacyConnection.kt`: `ConnectionManager` (modes, verified connect, failover with hysteresis, bounded retries with backoff) and `PrivacyProxySelector`.
- `OrbotBackend.kt`: finds Orbot, asks its status over Orbot's public broadcast interface, uses the SOCKS port Orbot reports (9050 only as an unverified candidate), and accepts the port only after a real request to `check.torproject.org` reports `IsTor: true`.
- `PrivacyNetwork.kt`: reads the mode at process start and installs the selector before any network client exists.

**Routing.** `PrivacyProxySelector` is set as the JVM default `ProxySelector`. Every OkHttp client in the app takes its proxy from it, which covers the shared `Http.client`, Ktor clients (InnerTube, lyrics, JioSaavn, Radio Javan, Last.fm, Listen Together, Discord), Coil images, downloads and Media3's `OkHttpDataSource` (audio, video, canvas). With a SOCKS proxy OkHttp sends the host name to the proxy unresolved, so DNS is resolved by Tor.

**Fail closed.** With no verified backend the selector returns a SOCKS proxy on a closed loopback port, so requests fail instead of going direct.

**Restart to apply.** OkHttp keeps connections alive for minutes. Switching a running process between direct and Tor could reuse a direct connection for a protected request, so the mode only changes at the next start.

**Failover.** Only connect failures to the local proxy count, never HTTP status codes. Three within 20 seconds trigger a real re-check of the backend; if it fails, the other backend is tried first.

## Routing audit

| Path | Protected mode |
|---|---|
| InnerTube / YouTube Music API, search, stream URL resolution | Through Tor |
| Audio and video streaming, range requests, redirects (Media3 OkHttp) | Through Tor |
| Downloads and stream cache | Through Tor |
| Artwork (Coil), lyrics providers, canvas, song info | Through Tor |
| JioSaavn, Radio Javan, add-on and module sources | Through Tor |
| Last.fm, Discord presence, Listen Together, Shazam recognition | Through Tor |
| Sign-in and token WebViews (YouTube Music, Spotify, Discord, PoToken) | Through Tor when the device's WebView supports proxy override; otherwise **direct**, and Settings says so |
| Loopback and private-network IP addresses (WebDAV, SMB, Cast on the LAN) | Direct, on purpose |
| Local file playback | No network |
| Google Cast / Wear OS data layer (Play services) | **Not covered**: handled by Google Play services outside nuvx |
| Native code | No network use found; FFmpeg only decodes |

## Tests

`PrivacyConnectionTest` (19 tests, JVM):

- Modes: Off never starts anything; Automatic prefers Orbot, else built-in Tor; Orbot-only and built-in-only never cross over.
- Failover both ways; both down ends Unavailable after bounded retries; one blip or spread-out failures do not switch.
- Cancellation, retry.
- Selector: transparent when off; blocked when nothing is connected; host names via SOCKS, local addresses direct.
- Real OkHttp traffic through a SOCKS5 test server: a range request and a redirect arrive by host name (`.invalid` names that cannot resolve locally), and requests fail closed with no backend or a dead proxy.

Backends are fakes in these tests. **Not verified anywhere:** a real Orbot, real Tor bootstrap, audio playback on a device over Tor, WebView proxying, behaviour on a censored network, battery impact.

## Manual test (Android)

1. Install Orbot, connect it.
2. nuvx → Settings → Privacy Connection → Orbot. Close nuvx from recents and reopen.
3. Status should read "Connected via Orbot". Run "Test connection".
4. Search, play a song, seek, skip. Expect slower loading.
5. Stop Orbot. New loads should fail; nothing should load directly. Start Orbot again and press Retry.
6. Set back to Off, restart, confirm normal speed.

## Still to do

- Built-in Tor + Snowflake (planned: Briar's `onionwrapper`, `tor` and `lyrebird` libraries), then Automatic mode. Needs a license and APK-size review.
- Resume an interrupted stream at its position after a backend switch (today Media3's normal retry applies).
- A separate "music streaming verified" check in Settings.
