# Connecting from restricted networks (Iran and elsewhere)

nuvx can send its traffic through a connection tool you install and trust yourself. nuvx itself is not a VPN and
never downloads proxy lists. None of this works during a complete internet shutdown, and a working connection does
not make you anonymous: services you sign in to still know your account.

**Which tool?** In Iran, plain Tor is blocked and slow even with bridges, and YouTube refuses many Tor exits. A
**VLESS/REALITY** (or Shadowsocks) client with a server you trust is usually much faster and more reliable. Use Tor
when that isn't available.

## Android phone (and Android Auto)

### Option A: a VLESS/REALITY app (recommended)

Apps: **v2rayNG**, **Hiddify** or **NekoBox** (from their official GitHub releases or a store you trust). Add a
server config you trust in that app.

- **Easiest: VPN mode.** Connect the app in VPN mode. In nuvx leave **Settings → Network → Privacy Connection** on
  **Off**. The whole phone, including nuvx, goes through the app.
- **Only nuvx: proxy mode.** Set the app to proxy-only mode and note its local SOCKS port (v2rayNG `10808`,
  NekoBox `2080`, Clash/Mihomo mixed port `7890`). In nuvx choose **Local SOCKS5**, enter the port, tap **Save**,
  restart nuvx, then tap **Test connection**.

### Option B: Tor with Orbot

1. Install **Orbot** (Guardian Project: GitHub releases, F-Droid or Play Store).
2. In Orbot tap **Set Transport** and choose a bridge: **Snowflake** first; if it fails, **Snowflake AMP**,
   **Built-in Bridges (obfs4)**, or ask Tor for bridges by Telegram or email.
3. Tap **Connect** and wait until Orbot says **Connected**. With bridges this can take a few minutes.
4. Either use Orbot as a VPN for nuvx (**Choose apps** → tick nuvx, and leave nuvx's Privacy Connection **Off**), or
   in nuvx choose **Settings → Network → Privacy Connection → Orbot**, tap **Restart now**, and wait for
   **Connected via Orbot**.

What to expect over Tor: the first screen can take a while; songs usually start in 3 to 10 seconds, sometimes up to 30; some songs are refused
by YouTube, and nuvx moves to a fresh Tor exit at once, up to four times per song. Music videos play at 360p. Casting is turned off
while a protected connection is on.

If **Privacy Connection** says *Connection unavailable*, nuvx is blocking everything on purpose rather than
connecting directly: check that Orbot or your proxy app is connected, then tap **Retry connection**.

## Android TV and Google TV

The phone's settings don't reach the TV. Install a VPN app on the TV itself (many VLESS clients have TV builds), or
use a router that tunnels the whole network.

## Wear OS

When the watch controls music playing on the phone, the phone's connection is what counts: set it up on the phone.

## Windows, macOS and Linux

- **VPN mode** of your client (v2rayN, Hiddify, Clash Verge, NekoRay): just run it; in nuvx leave
  **Settings → Network → Proxy** on the system setting.
- **Proxy mode:** in nuvx choose **Proxy → Custom** and enter the client's address, for example
  `socks5h://127.0.0.1:10808` or `socks5h://127.0.0.1:7890`, then **Save**. nuvx looks up addresses through the
  proxy and never connects directly if the proxy is missing.
- **Tor:** start **Tor Browser** (configure a bridge in its connection settings, for example Snowflake or obfs4) and
  leave it open. In nuvx choose **Proxy → Privacy Connection (Tor)**; it finds Tor Browser (port 9150) or a system
  Tor (9050) by itself.

## iPhone and iPad

nuvx has no in-app proxy on iOS yet. Use a VPN app for the whole device: a VLESS/REALITY client (for example
Streisand, FoXray, V2Box or Hiddify) or **Orbot for iOS** with a bridge.

## Web player and browser extension

A web page can't change your connection. Use your VPN for the whole device, or set your browser to use your proxy
app's address.

## Router level

[youtubeUnblock](https://github.com/Waujito/youtubeUnblock) runs on an OpenWrt router (or rooted Linux) and hides
which site you open from deep packet inspection. It needs nothing in nuvx: leave nuvx's network setting **Off**.

## What nuvx never does

- Download public proxy or VPN lists, or connect to servers you didn't choose.
- Turn off certificate checks.
- Fall back to a direct connection while a protected mode is on.
- Sync proxy settings or passwords between your devices.
