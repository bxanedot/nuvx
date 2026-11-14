# nuvx Web

The web player and its companion browser extension. The plan, and the decisions behind it, are in [`docs/web-app-build-prompt.md`](../../core/docs/web-app-build-prompt.md).

| Package | What it is |
|---|---|
| `app/` | The site: Vite, React 19, TypeScript, React Router, TanStack Query, Zustand, Tailwind CSS v4 |
| `extension/` | Manifest V3 extension for Chromium and Firefox, built with WXT |
| `shared/` | The page ↔ extension bridge contract and client, used by both |
| `docs/` | `apple-music-dom.md` (the design measurements), `kira-notes.md` (how the reference YouTube web client plays), `youtube-playback.md`, `youtube-signin.md`, `queue-and-player.md`, `lyrics.md`, `spotify-and-discord.md` |
| `fixtures/` | Real captured responses for parser tests (see its README) |
| `scripts/` | `capture-fixtures.mjs` |

## Setup

Node 22 or newer. pnpm comes through corepack, so nothing needs a global install:

```bash
corepack pnpm --dir app/web install
```

## Run the site

```bash
corepack pnpm --dir app/web/app dev
```

It serves on `http://localhost:5173`, and only there (`strictPort`): the extension trusts that exact origin.

## Run the extension

Build both targets:

```bash
corepack pnpm --dir app/web/extension build
```

- **Chrome, Edge, Brave:** `chrome://extensions` → Developer mode → *Load unpacked* → `app/web/extension/.output/chrome-mv3`. The id is always `ddclilijgpfpkhidfcooohihfalmmfmm`, pinned by the manifest `key` (`extension/dev-key/`), so the site finds it without configuration.
- **Firefox:** `about:debugging#/runtime/this-firefox` → *Load Temporary Add-on* → `app/web/extension/.output/firefox-mv3/manifest.json`.

To offer the extension as a download from the site's Extension page, package it:

```bash
corepack pnpm --dir app/web/extension package
```

This builds both targets and writes three files to `app/public/downloads/` (git-ignored):

- `nuvx-extension-chrome.zip` holds one folder to load unpacked. `nuvx-extension-firefox.zip` has `manifest.json` at its root so Firefox Developer Edition and Nightly can load the ZIP from `about:debugging` as a temporary add-on.
- `extension.json`, with the version. The site compares it with the installed extension's version and shows **Update** in the sidebar and an update row on the Extension page when the zip is newer. Bump `version` in `extension/package.json` to publish an update.

`VITE_EXTENSION_CHROME_URL`, `VITE_EXTENSION_FIREFOX_URL` and `VITE_EXTENSION_MANIFEST_URL` override those links, for example to point at store listings.

The toolbar icons (`extension/public/icon/`) are rendered from `app/public/favicon.svg` by `corepack pnpm --dir app/web/extension icons`.

Then open `http://localhost:5173/extension`. It shows "Installed", with the version and the transport: **Direct** on Chromium (`externally_connectable`), **Page relay** on Firefox (content script).

## Configuration

| Variable | Where | Default |
|---|---|---|
| `NUVX_SITE_ORIGINS` | extension build | `https://web.nuvx.lol,https://nuvx.pages.dev,http://localhost:5173` (store builds leave out localhost). Comma-separated origins the extension serves; replaces the default |
| `NUVX_STORE_BUILD=1` | extension build | Leaves out the dev `key` for a Chrome Web Store upload |
| `VITE_EXTENSION_ID_CHROMIUM` | app (`app/.env.local`) | The pinned dev id. Set it for a store-built extension |
| `VITE_ANDROID_APP_URL` | app | The GitHub releases page |
| `VITE_PARTY_SERVER_URL` | app (`app/.env.local`, Pages env) | None. The Listen Together server; `vite build` fails without it |

## Deploy (Vercel)

Live at **https://web.nuvx.lol**. The `nuvx-web` Vercel project deploys every push to `main` from the root directory `app/web`; build settings are in `vercel.json`, which installs the pnpm version `package.json` names (Vercel's Node has no corepack).

| Project environment variable | Value |
| --- | --- |
| `VITE_PARTY_SERVER_URL` | `https://nuvx-listen.bxane.workers.dev` |

`build:site` packages the extension first, so each deploy publishes the zips and `downloads/extension.json` with the version from `app/web/extension/package.json`. The Extension page polls that file and offers the update when it's newer than the installed one. Bump that version to make an update show.

The `Browser extension downloads` workflow also publishes the Chrome and Firefox ZIPs as stable-named assets on the `browser-extensions-latest` GitHub release. The Extension and Guide pages let users choose the browser store or a direct GitHub ZIP; ZIP installs require manual updates.

## Checks

```bash
corepack pnpm --dir app/web -r typecheck
corepack pnpm --dir app/web/app build
```
