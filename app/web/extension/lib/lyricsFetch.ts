import type { HttpRequest, HttpResponse } from '@nuvx/shared/bridge';

import { LYRIC_ORIGINS, patternOf } from './lyricHosts';
import { serialiseResponse } from './httpResponse';

export { LYRIC_ORIGINS, patternOf };

export class LyricsPermissionError extends Error {
  readonly code = 'permission';
}

const RULES = [
  {
    id: 200,
    urlFilter: '||amp-api.music.apple.com/',
    headers: [
      { header: 'Origin', operation: 'set', value: 'https://music.apple.com' },
      { header: 'Referer', operation: 'set', value: 'https://music.apple.com/' },
    ],
  },
  {
    id: 201,
    urlFilter: '||musixmatch.com/',
    headers: [{ header: 'Cookie', operation: 'set', value: 'mxm_bab=AB' }],
  },
] as const;

let rulesReady: Promise<void> | null = null;

function installRules(): Promise<void> {
  rulesReady ??= browser.declarativeNetRequest
    .updateSessionRules({
      removeRuleIds: RULES.map((r) => r.id),
      addRules: RULES.map((r) => ({
        id: r.id,
        priority: 1,
        condition: { urlFilter: r.urlFilter, tabIds: [-1], resourceTypes: ['xmlhttprequest'] },
        action: { type: 'modifyHeaders', requestHeaders: r.headers.map((h) => ({ ...h })) },
      })) as Browser.declarativeNetRequest.Rule[],
    })
    .catch((error: unknown) => {
      rulesReady = null;
      throw error;
    });
  return rulesReady;
}

export async function grantedOrigins(origins: string[]): Promise<string[]> {
  const known = origins.filter((o) => (LYRIC_ORIGINS as readonly string[]).includes(o));
  const answers = await Promise.all(known.map((o) => browser.permissions.contains({ origins: [patternOf(o)] })));
  return known.filter((_, i) => answers[i]);
}

export async function requestOrigins(origins: string[]): Promise<void> {
  const known = origins.filter((o) => (LYRIC_ORIGINS as readonly string[]).includes(o));
  if (!known.length) return;
  const url = browser.runtime.getURL(`/grant.html?origins=${encodeURIComponent(known.join(','))}`);
  await browser.tabs.create({ url });
}

export async function lyricsFetch(request: HttpRequest): Promise<HttpResponse> {
  const url = new URL(request.url);
  if (!(LYRIC_ORIGINS as readonly string[]).includes(url.origin)) {
    throw Object.assign(new Error(`lyrics.fetch doesn't serve ${url.origin}`), { code: 'forbidden' });
  }
  if (!(await browser.permissions.contains({ origins: [patternOf(url.origin)] }))) {
    throw new LyricsPermissionError(`nuvx needs permission to reach ${url.host} for lyrics.`);
  }
  await installRules();
  const response = await fetch(url, {
    method: request.method ?? 'GET',
    headers: request.headers ?? {},
    ...(request.body !== undefined && { body: request.body }),
    credentials: 'omit',
    signal: AbortSignal.timeout(15_000),
  });
  return serialiseResponse(response, true);
}
