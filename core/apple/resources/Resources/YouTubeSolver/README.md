# YouTube challenge solver

Pinned release: yt-dlp/ejs 0.8.0, https://github.com/yt-dlp/ejs/releases/tag/0.8.0

The unmodified core and library distributions are bundled with the app and executed
locally in JavaScriptCore. The app does not fetch executable solver updates.
The core is Unlicense; the library file includes the ISC and MIT notices for its
bundled meriyah and astring dependencies. Preserve these notices when updating.

The BotGuard PoToken page is copied verbatim from
[Metrolist's implementation](https://github.com/MetrolistGroup/Metrolist/blob/0750a63d74d6/app/src/main/assets/po_token.html)
and is covered by Metrolist's [GPL-3.0 license](https://github.com/MetrolistGroup/Metrolist/blob/0750a63d74d6/LICENSE).

The solver extracts both signature and throttling transforms from the same player
script used to obtain the signature timestamp. Run scripts/check-player-js.sh after
updating it and scripts/check-playback.sh to verify actual media bytes.
