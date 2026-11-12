# nuvx browser extension store listing

## Publisher details

- Product name: nuvx
- Developer: bxane
- Support email: bxanee@gmail.com
- Homepage: https://web.nuvx.lol/extension
- Support: https://github.com/bxanedot/nuvx/issues
- Privacy policy: https://www.nuvx.lol/privacy
- License: GNU GPL-3.0-only
- Version: 0.9.0
- Paid features: none
- Discord integration: excluded from store builds

## Short description

Connect the nuvx web player to your YouTube Music and Spotify accounts.

## Detailed description

Use YouTube Music and Spotify with the nuvx web player.

After installing, click the nuvx toolbar icon and choose **Open nuvx web player**. The companion web player at https://web.nuvx.lol is required.

Connect your existing browser sessions to browse music libraries and playlists from the nuvx web player. Search and play music with your existing service accounts. Optional lyrics providers can be granted one at a time from the player.

The web player includes scrollable internet radio stations and podcast discovery. Signed-out listeners get general YouTube podcast episodes.

**How account access works**

- You choose Connect separately for YouTube Music and Spotify. The extension uses that browser's existing session and sends requests directly to the matching service.
- Account names, YouTube channel details, playlists and track metadata are passed to the nuvx web player in your browser to provide the features you request.
- Optional lyrics sources receive lyric-search details only after you choose a source and grant its permission.
- Your service passwords are never requested. Service cookies are not copied to a nuvx server.
- The store build does not include Discord integration.

You need the free nuvx web player at https://web.nuvx.lol. YouTube Music and Spotify accounts are subject to their own terms and availability. nuvx is an independent project and is not affiliated with Google, YouTube or Spotify.

## Store fields

| Field | Chrome Web Store | Firefox Add-ons |
|---|---|---|
| Category | Productivity → Tools | Photos, Music & Videos |
| Summary | Short description above | Connect nuvx to YouTube Music and Spotify in your browser. |
| Privacy disclosure | Authentication information, personally identifying information, search terms, website content | Required data: `authenticationInfo`, `personallyIdentifyingInfo`, `searchTerms`, `websiteContent` |
| Remote code | No | No |
| Supported browsers | Chrome and Chromium-based browsers supporting Manifest V3 | Desktop Firefox 140 or newer (Linux, macOS and Windows; Android not supported) |
| Source code | https://github.com/bxanedot/nuvx | https://github.com/bxanedot/nuvx |

## Permission explanations

- `cookies`: identify the signed-in YouTube Music and Spotify sessions after the user connects each service.
- `declarativeNetRequest`: apply the request-header rules required for YouTube Music and Spotify web APIs.
- `storage`: retain service connection choices, selected YouTube channel, Spotify player metadata cache and Spotify's browser device identifier.
- YouTube Music, YouTube, Google video, Spotify and nuvx web-player host access: make the requests required for the connected web-player features.
- Optional lyrics host access: contact only the lyrics sources the user selects and approves.

## Required listing graphics

- Store icon: use `public/icon/128.png`.
- Chrome Web Store screenshots: capture real screens from the current nuvx web player and extension. Do not use mockups as product screenshots.
- Firefox screenshots: use the same real product screens.
- Do not claim ratings, user counts, awards or endorsements.

## Build

Build and package each browser with `NUVX_STORE_BUILD=1`. Store builds remove localhost origins, the development key and Discord integration. Review generated manifests before uploading. Firefox's store package must target Firefox 140 or newer to use Firefox's built-in data-consent prompt.
