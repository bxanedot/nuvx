# Firefox Add-on source and reproducible build

This package describes the source for the nuvx Firefox add-on (version 0.9.0). Its TypeScript, HTML, CSS, manifest configuration, and the required `@nuvx/shared` workspace code are supplied as readable source. WXT/Vite generate the installable Firefox files during the build; generated bundles are not substituted for the source in this archive.

## Build environment

- OS: Windows 10/11, macOS, or Linux (64-bit x64 or ARM64)
- Node.js: 22 or newer; npm is included with Node.js
- pnpm: exactly 12.11.2 (the version pinned by `app/web/package.json`)
- Git: a recent Git 2.x installation, needed to create the source archive
- Firefox target: desktop Firefox 140 or newer

Install Node.js from [nodejs.org](https://nodejs.org/). Install Git from [git-scm.com](https://git-scm.com/downloads). Then install the pinned pnpm version in a terminal:

```sh
npm install --global pnpm@12.11.2
```

## Reproduce the Firefox add-on

1. Clone the repository and enter its web workspace:

   ```sh
   git clone https://github.com/bxanedot/nuvx.git
   cd nuvx/app/web
   ```

2. Install the locked dependencies:

   ```sh
   pnpm install --frozen-lockfile
   ```

3. Build the Firefox store variant:

   ```sh
   cd extension
   node scripts/amo-source.mjs build
   ```

The build script sets `NUVX_STORE_BUILD=1` and runs WXT's Firefox build. The generated add-on files are in `app/web/extension/.output/firefox-mv3/`. The script checks the generated manifest version, Firefox add-on ID, and minimum supported Firefox version. The store variant excludes the Discord integration as configured in the source.

## Create the AMO source-code ZIP

Run this after committing the source changes you are submitting. From `app/web/extension`, run:

```sh
node scripts/amo-source.mjs source
```

This creates `nuvx-firefox-source-0.9.0.zip` in the repository root. It contains the extension source, this README, build script, shared workspace source, and the exact pnpm workspace manifests and lockfile. It excludes generated build output and installed dependencies. The script stops if the archive exceeds Mozilla's 200 MB upload limit. Upload this ZIP in the AMO **Source code** field for the matching submitted version.

Dependencies are installed from the lockfile during reproduction; third-party packages are not copied into the source archive. No private signing key is needed or included.

## Source layout

- `app/web/extension/`: Firefox and Chromium extension source and WXT configuration
- `app/web/shared/`: TypeScript workspace code imported by the extension
- `app/web/pnpm-lock.yaml`, `pnpm-workspace.yaml`, `package.json`: pinned workspace dependency graph and package manager version
