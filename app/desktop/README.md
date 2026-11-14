# nuvx for desktop

A native YouTube Music client for Windows, Linux and macOS, written in Rust (egui).

- Apple Music–style look: sidebar, liquid-glass player bar, full-screen now playing with synced lyrics
- SF Symbols from GlyphStudio, drawn as vectors, with their bounce animation
- Listen Together through the nuvx party server (`nuvx.lol/invite/CODE`, `nuvx://party/CODE`)
- Downloads, Discord presence, Last.fm, Spotify playlist transfer, tray and mini player

## Build

```bash
cargo build --release
```

Each system has its own folder: `windows/` (the Inno Setup installer, `windows/nuvx.iss`), `linux/` and `macos/`. Desktop packages include the app license and bundled font notices in a `licenses/` folder. The Linux package also includes notices for its vendored GLib crate. Last.fm needs `LASTFM_API_KEY` and
`LASTFM_SECRET` set at build time; without them that sign-in is off.
