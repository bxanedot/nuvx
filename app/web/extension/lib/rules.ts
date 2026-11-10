type Rule = Browser.declarativeNetRequest.Rule;
type HeaderInfo = Browser.declarativeNetRequest.ModifyHeaderInfo;

const XHR: `${Browser.declarativeNetRequest.ResourceType}`[] = ['xmlhttprequest'];
const XHR_AND_MEDIA: `${Browser.declarativeNetRequest.ResourceType}`[] = ['xmlhttprequest', 'media'];

const set = (header: string, value: string): HeaderInfo => ({ header, operation: 'set', value });
const remove = (header: string): HeaderInfo => ({ header, operation: 'remove' });

const CORS_RESPONSE: HeaderInfo[] = [
  set('Access-Control-Allow-Origin', '*'),
  set('Access-Control-Expose-Headers', '*'),
];

function impersonate(origin: string): HeaderInfo[] {
  return [set('Origin', origin), set('Referer', `${origin}/`), set('X-Origin', origin)];
}

export function youtubeRules(initiatorDomains: string[]): Rule[] {
  const rule = (
    id: number,
    urlFilter: string,
    requestHeaders: HeaderInfo[] | null,
    resourceTypes = XHR,
    priority = 1,
  ): Rule => ({
    id,
    priority,
    condition: { urlFilter, initiatorDomains, resourceTypes },
    action: {
      type: 'modifyHeaders',
      ...(requestHeaders && { requestHeaders }),
      responseHeaders: CORS_RESPONSE,
    },
  });

  return [
    rule(1, '||music.youtube.com/youtubei/', impersonate('https://music.youtube.com')),
    rule(2, '||www.youtube.com/youtubei/', impersonate('https://www.youtube.com')),
    rule(3, '||www.youtube.com/sw.js_data', null),
    rule(4, '||www.youtube.com/iframe_api', null),
    rule(5, '||www.youtube.com/s/player/', null),
    rule(6, '||googlevideo.com/', impersonate('https://www.youtube.com').slice(0, 2), XHR_AND_MEDIA),
    rule(7, '||googlevideo.com/*c=VISIONOS', [remove('Origin'), remove('Referer')], XHR_AND_MEDIA, 2),
  ];
}

export const RULE_IDS = Array.from({ length: 99 }, (_, i) => i + 1);

export function initiatorDomainsFor(origins: string[]): string[] {
  return [...new Set(origins.map((o) => new URL(o).hostname))];
}

export async function installRules(origins: string[]): Promise<void> {
  await browser.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: RULE_IDS,
    addRules: youtubeRules(initiatorDomainsFor(origins)),
  });
}

export const EXTENSION_FETCH_RULE_ID = 100;
const EXTENSION_MUSIC_FETCH_RULE_ID = 101;
const EXTENSION_STATS_FETCH_RULE_ID = 102;

export async function installExtensionFetchRule(): Promise<void> {
  const own = (id: number, host: string, origin: string): Rule => ({
    id,
    priority: 1,
    condition: { urlFilter: `||${host}/`, tabIds: [-1], resourceTypes: ['xmlhttprequest'] },
    action: { type: 'modifyHeaders', requestHeaders: impersonate(origin).slice(0, 2) },
  });
  await browser.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [EXTENSION_FETCH_RULE_ID, EXTENSION_MUSIC_FETCH_RULE_ID, EXTENSION_STATS_FETCH_RULE_ID],
    addRules: [
      own(EXTENSION_FETCH_RULE_ID, 'www.youtube.com', 'https://www.youtube.com'),
      own(EXTENSION_MUSIC_FETCH_RULE_ID, 'music.youtube.com', 'https://music.youtube.com'),
      own(EXTENSION_STATS_FETCH_RULE_ID, 's.youtube.com', 'https://music.youtube.com'),
    ],
  });
}

const SPOTIFY_RULE_IDS = [300, 301, 302];

export async function installSpotifyFetchRule(): Promise<void> {
  const hosts = ['open.spotify.com', 'api-partner.spotify.com', 'clienttoken.spotify.com'];
  await browser.declarativeNetRequest.updateSessionRules({
    removeRuleIds: SPOTIFY_RULE_IDS,
    addRules: hosts.map((host, i) => ({
      id: SPOTIFY_RULE_IDS[i]!,
      priority: 1,
      condition: { urlFilter: `||${host}/`, tabIds: [-1], resourceTypes: ['xmlhttprequest'] },
      action: { type: 'modifyHeaders', requestHeaders: impersonate('https://open.spotify.com').slice(0, 2) },
    })),
  });
}

const DISCORD_RULE_ID = 400;

export async function installDiscordFetchRule(): Promise<void> {
  await browser.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [DISCORD_RULE_ID],
    addRules: [
      {
        id: DISCORD_RULE_ID,
        priority: 1,
        condition: { urlFilter: '||discord.com/api/', tabIds: [-1], resourceTypes: ['xmlhttprequest'] },
        action: { type: 'modifyHeaders', requestHeaders: impersonate('https://discord.com').slice(0, 2) },
      },
    ],
  });
}
