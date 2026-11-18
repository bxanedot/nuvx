# Network resilience and censorship-resistant access

Status: Android (Orbot, local SOCKS5) verified on a real device; desktop proxy hardened; TV, Wear, iOS and web rely on a device-level VPN for now.
This is an app connectivity feature, **not** an embedded VPN and **not** a guarantee of access in Iran.

## What Nuvx needs

Nuvx is a YouTube Music player. Search, InnerTube, Google auth, `googlevideo.com` stream playback, covers, lyrics, downloads, Spotify integrations, Listen Together and release checks use different network stacks. A successful homepage fetch is not proof that playback, sign-in or downloads use the same path.

For censorship-affected networks, prefer **a trusted, separately installed VPN/TUN or local SOCKS5 client**, not an unreviewed proxy engine bundled inside Nuvx. A device-level tunnel is the broadest initial option because Nuvx cannot control every embedded browser, extension or native media transport.

## User setup today

| Platform | Existing / initial path | Location |
| --- | --- | --- |
| Android phone/tablet | A trusted device VPN/TUN; or local SOCKS5 from a separately installed client, or Orbot. A local SOCKS5 port is available in this branch. | Settings → Network → Privacy Connection |
| Android TV / Google TV / Auto | Install a supported VPN/TUN on the **device playing the audio**, or use a separately configured network gateway. The phone's proxy is not automatically inherited by a TV, casting receiver or standalone head unit. | Future: TV Settings → Network |
| Wear OS | For watch controls with playback on the phone, protect the **phone's** connection. Independent watch requests require separate verification and possibly a watch network route. | Future: watch connection diagnostics |
| Windows | Nuvx custom HTTP/SOCKS proxy, system proxy, or device VPN/TUN. | Settings → Network |
| macOS / Linux | Custom HTTP/SOCKS proxy or a device VPN/TUN. The existing System option honors common environment proxy variables via the HTTP stack; it is not the Windows proxy registry. | Settings → Network |
| iPhone / iPad | A reputable iOS device VPN/TUN for now. The app uses Ktor Darwin/NSURLSession and native playback paths that require an explicit per-app routing audit before adding in-app proxy controls. | Future: Settings → Network |
| Web + extension | A browser/OS VPN or proxy for **both** the website and extension. The site cannot transparently change browser-wide proxy settings. | Future: Settings → Network diagnostics and setup guidance |

### Android local SOCKS5 (this branch)

1. Install and configure a trusted network client yourself. If it exposes a local SOCKS5 port on the phone, identify that port. **No server/config subscriptions are fetched by Nuvx.**
2. In Nuvx, open **Settings → Network → Privacy Connection → Local SOCKS5**, set the loopback listener port (default `10808`) and save.
3. Restart Nuvx to ensure existing sockets cannot continue over the previous route.
4. Open **Test connection**. Nuvx sends an HTTPS request to YouTube Music **through** the local SOCKS5 listener, and marks it available only if it succeeds.
5. Test search, a full audio stream, artwork, lyrics, login and downloads separately. A passed probe is not a DNS/WebView/native-player leak audit.

Important: this mode does **not** turn on your proxy client or handle its VLESS/REALITY, Hysteria, WireGuard or Mihomo configuration. If the client is not running or the port changes, Nuvx's proxy selector fails closed for protected requests. The app still allows local/LAN addresses to remain direct for existing local-media and local-sync workflows. Some native third-party transports may not use that selector; do not claim whole-device leak protection.

### Desktop custom proxy

If your external client exposes SOCKS5 on `127.0.0.1:10808`, open **Settings → Network → Proxy → Custom** and enter `socks5h://127.0.0.1:10808` (the `h` form requests proxy-side hostname resolution). Save, restart and run **Test connection**. For HTTP proxies use an appropriate `http://127.0.0.1:<port>` address. On macOS/Linux, the system mode displays environment-proxy guidance instead of Windows registry instructions.

## Verified on a real device (Galaxy S26, Android dev build, 2026-11-18)

Method: a SOCKS5 relay that logs every destination, reached by the phone at `127.0.0.1:10808`; the app's own TCP
sockets read from `/proc/net/tcp{,6}` (owner uid) to catch anything that does not go through the proxy; then real
Orbot 17.9.7 (Direct and **Snowflake** transport) in Orbot mode.

| Traffic | Local SOCKS5 | Orbot | Notes |
| --- | --- | --- | --- |
| YouTube Music API (InnerTube), search, home | ✅ via proxy, host names | ✅ | remote DNS: the relay saw names, not IPs |
| Audio stream (`*.googlevideo.com`) | ✅ played | ✅ played | |
| Artwork in app (Coil: `i.ytimg.com`, `yt3.googleusercontent.com`) | ✅ | ✅ | |
| Notification / lock screen / Auto artwork (MediaSession) | ✅ **after fix** | ✅ | was leaking direct, see below |
| Lyrics providers, Apple/canvas lookups, update check, Listen Together | ✅ | ✅ | |
| Proxy down (fail-closed) | ✅ no direct sockets | — | status shows "Connection unavailable" |
| Casting | ⛔ refused in protected mode | ⛔ | the receiver would fetch the stream itself |
| LAN Sync to a paired computer | direct (local) | direct (local) | private-address exception, by design |
| Snowflake-bridged Orbot | — | ✅ traffic through Orbot 13 s after connect | Iran itself not tested |

Fixed in this branch:
- **Leak:** Media3's default MediaSession bitmap loader uses `HttpURLConnection`, whose platform OkHttp retries
  a failed proxy *directly*. With the route down, session artwork went straight to Google. Now loaded through the
  app's OkHttp client, which fails closed.
- **Desktop DNS leak:** a custom `socks5://` proxy resolved names locally; now used as `socks5h://`.
- **Desktop fail-open:** an unusable custom proxy address meant no proxy; it now fails closed like Tor mode.
- **Bridged Tor:** Orbot bootstrap wait 45 s → 5 min; Tor check 40 s → 90 s with one retry; local SOCKS5 probe
  20 s → 60 s.

## Iran and other heavily filtered networks

Plain Tor is blocked in Iran; a tester's Orbot failed for this reason. What works there changes often, so nuvx
depends on a local interface, not one transport:

1. **Fastest:** a VLESS/REALITY (or Shadowsocks) client such as v2rayNG, Hiddify or NekoBox with a config the user
   trusts. In its **VPN mode** leave nuvx's Network setting **Off** (the whole phone is tunnelled). In its
   **proxy mode** choose **Local SOCKS5** and enter its port (v2rayNG `10808`, NekoBox `2080`, Clash/Mihomo mixed
   port `7890`).
2. **Tor:** in Orbot, turn on a bridge (Snowflake, Snowflake AMP, obfs4 or WebTunnel) before connecting, then pick
   **Orbot** in nuvx. Expect slow starts and lower bitrates.
3. **Router-level:** [youtubeUnblock](https://github.com/Waujito/youtubeUnblock) splits the TLS handshake on an
   OpenWrt router (or rooted Linux) so DPI cannot read the YouTube SNI. It needs no app support: nuvx works with it
   in **Off** mode. It cannot be embedded in an app (it relies on netfilter queues).

None of this works during a full internet shutdown, and a working probe is not a claim of anonymity.

## Network Management architecture

**UI:** first-class **Settings → Network & Connectivity** on every platform. Surface:
- Active route: Direct / System VPN / Local SOCKS5 / Custom HTTP(S) / Tor (only where supported)
- Port/profile configuration; clear status and explicit save/restart semantics
- Diagnostics separated by host and purpose: YouTube Music metadata, actual Google video streaming, sign-in WebView, artwork, lyrics, download and Listen Together
- Reachability and route verification; distinguish DNS failure, timeout, HTTP rejection, broken proxy and a country-specific block **without** claiming censorship detection from one HTTP failure
- Per-profile fail-closed preference. Never silently connect directly after an explicitly protected mode fails
- Optional manual retry, health-check backoff and opt-in local redacted diagnostics

**Architecture:** avoid a new shared all-platform VPN engine. Use a shared *specification* for modes, outcomes, coverage and tests, with platform adapters:
- Android: `app/android/mobile/.../net/privacy/` and `PrivacyConnectionSettingsGroup.kt` (plus TV settings and playback routing audit)
- Desktop: `app/desktop/src/proxy.rs`, `app/desktop/src/ui/network_settings.rs`; `reqwest`, streaming and sign-in relay must share selected route
- iOS: `app/ios/shared/src/appleMain/kotlin/.../data/http/Http.kt`, native playback/http source, media, login and Swift UI settings. Favor OS VPN first
- Web: `app/web/app/src/pages/SettingsPage.tsx` and the extension's own fetch/background paths; web cannot configure a system VPN. A user-selected external browser proxy is preferred to a credential-handling public relay
- Shared protocol-neutral test descriptions / support matrix: `docs/`, not a Kotlin-only runtime abstraction for the Rust and TypeScript players

**Do not** auto-import public proxy subscriptions, ship random third-party egress endpoints, disable TLS certificate validation, proxy Google credentials through unaudited infrastructure, inject unsigned scripts, or sync confidential proxy credentials through Listen Together or LAN Sync. If user-controlled remote proxy profiles are added later, validate inputs, store secrets only in device secure storage, block local-only endpoints in remote/cloud features, and limit logs to redacted host/proxy status.

## Assessment of research references

- [Iran internet GitHub topic](https://github.com/topics/iran-internet?o=desc&s=stars): discovery index, not an audited dependency list
- [WatTunnel](https://github.com/JohnWatsoon/WatTunnel): Iran ↔ overseas Linux server forwarding; could back an **operator-managed** network tunnel, not a Nuvx client library; introduces operational and identity risk
- [Config-Collector](https://github.com/miladtahanian/Config-Collector): continuously gathered Telegram configs; **do not auto-subscribe** (untrusted egress, attribution and disappearance risks)
- [BaleVPN](https://github.com/kookoo1sabzy/BaleVPN): peer VPN over a messaging provider; its author warns of social-graph/destination visibility and lack of authenticated end-to-end QUIC peers. Do not recommend it as anonymous
- [InternetForIran](https://github.com/InternetForIran/InternetForIran): two-server relay model, but older operational guidance includes unsafe identity and server practices; do not reproduce its instructions
- [freedom](https://github.com/rahgozar94725/freedom): useful as a survey of Xray, Shadowsocks and CDN routing, not a library to embed
- [REALITY gist](https://gist.github.com/justiceformahsa/f440307c83ffe1679074dbf691ca7e54): VLESS/REALITY user-owned gateway reference; claims of being “undetectable” and “unblockable” are not justified. Do not run unreviewed remote shell scripts
- [Iran Clash Serverless](https://github.com/Kingvornex/Iran-Clash-Serverless/blob/main/README_OLD.md): Mihomo/Xray config inspiration for YouTube; must not be assumed reliable, compatible or secure without audit

A local client that accepts a trusted proxy configuration can provide Nuvx a **stable local SOCKS5 or VPN/TUN interface** regardless of which upstream transport works. The app should depend on that interface, not on one Iran-specific protocol.

## Remaining work and release gates

1. **Android coverage audit:** verify OkHttp/Coil/Media3/Ktor, WebView auth, native eXcor fallback, DRM/casting, private LAN exceptions, and DNS behavior in Direct/Orbot/Local SOCKS5. Verify Android system VPN mode with and without per-app exclusions; check tunnels survive app restart and network changes
2. **Desktop audit:** test proxy auth, all network clients and platform behavior on Windows, Linux and macOS; address any unproxied child processes, WebViews and native decoders
3. **iOS app:** add Network page/status and opt-in local proxy only if **all** NSURLSession/Ktor/native media/login paths can be protected; otherwise direct users to system VPN. Validate on a real device
4. **TV/Auto/Wear:** explicit settings + tests by device; do not assume Android phone coverage also covers cast receivers or standalone devices
5. **Web/extension:** show connection test and platform instructions; verify requests from service worker, web page and HTML media element independently
6. **Conditions:** test network filtering, captive portals, DNS poisoning, IPv4/IPv6, proxy stoppage midstream, Wi-Fi/mobile handover, power saving and restart. Perform opt-in tests on real Iranian ISPs; do not promise coverage from an external test network

**Acceptance:** no unexpected direct fallback in protected mode; errors distinguish unavailability from invalid credentials; playback and all user-visible services work through a verified route, or display a specific unsupported-path warning. Preserve existing playback state, playlists, downloads, authentication and settings.
