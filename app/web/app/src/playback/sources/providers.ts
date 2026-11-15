import type { HttpResponse, SourceFetchParams, SourceProvider } from '@nuvx/shared/bridge';
import { getBridge } from '@/extension/bridge';
import { metadataMatches, type Described } from './match';

// Mirrors app/desktop/src/backend/sources.rs.
export type Json = Record<string, unknown>;
export type SourceCall = (params: SourceFetchParams) => Promise<unknown>;

export interface ResolvedSource {
  provider: SourceProvider;
  url: string;
  durationSec: number | null;
}

export type EnabledSources = Record<SourceProvider, boolean>;

const JIO = 'https://www.jiosaavn.com/api.php';
const RJ = 'https://rj-deskcloud.com/api2/';

const obj = (v: unknown): Json => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : {});
const text = (v: unknown, key: string): string | null => {
  const value = obj(v)[key];
  return typeof value === 'string' && value.trim() ? value : null;
};

function rows(root: unknown): Json[] {
  if (Array.isArray(root)) return root.map(obj);
  const r = obj(root);
  for (const key of ['mp3s', 'results', 'data', 'songs']) if (Array.isArray(r[key])) return (r[key] as unknown[]).map(obj);
  const single = r.mp3 ?? r.song ?? ('id' in r ? r : null);
  return single ? [obj(single)] : [];
}

export function seconds(raw: string | null): number | null {
  if (!raw) return null;
  const [m, s] = raw.split(':');
  if (s !== undefined) return /^\d+$/.test(m!) && /^\d+$/.test(s) ? Number(m) * 60 + Number(s) : null;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
}

function jioArtist(row: Json): string {
  const info = obj(row.more_info);
  const names = (obj(info.artistMap).primary_artists as unknown[] | undefined)?.map((a) => text(a, 'name')).filter(Boolean) ?? [];
  return names.length ? names.join(', ') : (text(info, 'singers') ?? '');
}

/** HTTPS, no credentials, mp3/m4a/mp4 only (not live radio or playlists). */
export function mediaUrl(raw: string | null): string | null {
  if (!raw) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || url.username || url.password || !url.hostname) return null;
    return /\.(mp3|m4a|mp4)$/i.test(url.pathname) ? url.href : null;
  } catch {
    return null;
  }
}

const withQuery = (base: string, query: Record<string, string>) => `${base}?${new URLSearchParams(query)}`;
const jio = (call: SourceCall, name: string, args: Record<string, string>) =>
  call({
    provider: 'jiosaavn',
    url: withQuery(JIO, { __call: name, _format: 'json', _marker: '0', api_version: '4', ctx: 'android', ...args }),
  });

export async function resolveJioSaavn(track: Described, call: SourceCall): Promise<ResolvedSource> {
  const search = await jio(call, 'search.getResults', { q: `${track.title} ${track.artist}`, n: '25', p: '1' });
  const row = rows(search).find((r) => {
    const info = obj(r.more_info);
    return metadataMatches(track, { title: text(r, 'title') ?? '', artist: jioArtist(r), durationSec: seconds(text(info, 'duration')) });
  });
  if (!row) throw new Error('JioSaavn has no matching track');
  const info = obj(row.more_info);
  const encrypted = text(info, 'encrypted_media_url');
  if (!encrypted) throw new Error('JioSaavn returned no media token');
  const token = await jio(call, 'song.generateAuthToken', { url: encrypted, bitrate: '320' });
  const url = mediaUrl(text(token, 'auth_url'));
  if (!url) throw new Error('JioSaavn returned no usable stream');
  return { provider: 'jiosaavn', url, durationSec: seconds(text(info, 'duration')) ?? track.durationSec };
}

export async function resolveRadioJavan(track: Described, call: SourceCall): Promise<ResolvedSource> {
  const search = await call({ provider: 'radiojavan', url: withQuery(`${RJ}search`, { query: `${track.title} ${track.artist}` }) });
  const row = rows(search).find((r) =>
    metadataMatches(track, {
      title: text(r, 'song') ?? text(r, 'title') ?? '',
      artist: text(r, 'artist') ?? '',
      durationSec: seconds(text(r, 'duration')),
    }),
  );
  const id = row && text(row, 'id');
  if (!id) throw new Error('Radio Javan has no matching track');
  const details = await call({ provider: 'radiojavan', url: withQuery(`${RJ}mp3`, { id }) });
  const detail = rows(details)[0] ?? obj(details);
  const url = mediaUrl(text(detail, 'hq_link')) ?? mediaUrl(text(detail, 'lq_link'));
  if (!url) throw new Error('Radio Javan returned no usable stream');
  return { provider: 'radiojavan', url, durationSec: seconds(text(detail, 'duration')) ?? track.durationSec };
}

/** After YouTube: JioSaavn, then Radio Javan. Each enabled source fails safely to the next. */
export async function resolveFallback(track: Described, enabled: EnabledSources, call: SourceCall): Promise<ResolvedSource | null> {
  const order: [SourceProvider, typeof resolveJioSaavn][] = [
    ['jiosaavn', resolveJioSaavn],
    ['radiojavan', resolveRadioJavan],
  ];
  for (const [provider, resolve] of order) {
    if (!enabled[provider]) continue;
    try {
      return await resolve(track, call);
    } catch {
      /* try the next source */
    }
  }
  return null;
}

export const bridgeSourceCall: SourceCall = async (params) => {
  const bridge = getBridge();
  if (!bridge) throw new Error('The nuvx extension isn’t available');
  const response: HttpResponse = await bridge.call('source.fetch', params);
  if (response.status < 200 || response.status >= 300) throw new Error(`Source answered ${response.status}`);
  return JSON.parse(response.body) as unknown;
};
