import { readFileSync } from 'node:fs';
import { defineConfig } from 'wxt';
import { LYRIC_ORIGINS, patternOf } from './lib/lyricHosts';
import { matchPatternsFor, SITE_ORIGINS } from './site.config';

const STORE_BUILD = process.env.NUVX_STORE_BUILD === '1';

const DISCORD_PRESENCE = process.env.DISCORD_PRESENCE ? process.env.DISCORD_PRESENCE === '1' : !STORE_BUILD;
const DISCORD_ORIGINS = ['https://discord.com/*'];

const devPublicKey = readFileSync(new URL('./dev-key/public-key.txt', import.meta.url), 'utf8').trim();

export default defineConfig({
  manifestVersion: 3,
  vite: () => ({
    define: {
      __SITE_ORIGINS__: JSON.stringify(SITE_ORIGINS),
      __DISCORD_PRESENCE__: JSON.stringify(DISCORD_PRESENCE),
      __STORE_BUILD__: JSON.stringify(STORE_BUILD),
    },
  }),
  manifest: ({ browser }) => ({
    name: 'nuvx',
    description: 'Connects the nuvx web player to YouTube Music and Spotify.',
    homepage_url: `${SITE_ORIGINS[0]}/extension`,
    permissions: ['storage', 'declarativeNetRequest', 'cookies', ...(DISCORD_PRESENCE ? ['scripting'] : [])],
    host_permissions: [
      'https://music.youtube.com/*',
      'https://www.youtube.com/*',
      'https://*.youtube.com/*',
      'https://*.googlevideo.com/*',
      'https://open.spotify.com/*',
      'https://accounts.spotify.com/*',
      'https://api-partner.spotify.com/*',
      'https://clienttoken.spotify.com/*',
      'https://open.spotifycdn.com/*',
      ...matchPatternsFor(SITE_ORIGINS),
    ],
    optional_host_permissions: [...LYRIC_ORIGINS.map(patternOf), ...(DISCORD_PRESENCE ? DISCORD_ORIGINS : [])],
    ...(browser === 'firefox'
      ? {
          browser_specific_settings: {
            gecko: {
              id: 'extension@nuvx.lol',
              strict_min_version: '140.0',
              data_collection_permissions: {
                required: ['authenticationInfo', 'personallyIdentifyingInfo', 'searchTerms', 'websiteContent'],
              },
            },
          },
        }
      : {
          externally_connectable: { matches: matchPatternsFor(SITE_ORIGINS) },
          ...(!STORE_BUILD && { key: devPublicKey }),
        }),
  }),
  filterEntrypoints: DISCORD_PRESENCE ? undefined : ['background', 'popup', 'grant', 'relay'],
  hooks: {
    'build:manifestGenerated': (wxt, manifest) => {
      for (const script of manifest.content_scripts ?? []) {
        if (script.js?.some((file) => file.includes('relay'))) {
          script.matches = matchPatternsFor(SITE_ORIGINS);
        }
      }
    },
  },
});
