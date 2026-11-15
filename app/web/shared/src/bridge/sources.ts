export type SourceProvider = 'jiosaavn' | 'radiojavan';

export interface SourceFetchParams {
  provider: SourceProvider;
  url: string;
}

const JIO_CALLS = new Set(['search.getResults', 'song.generateAuthToken']);
const JIO_KEYS = new Set(['__call', '_format', '_marker', 'api_version', 'ctx', 'q', 'n', 'p', 'url', 'bitrate']);
const RJ_PATHS = new Set(['/api2/search', '/api2/mp3']);
const RJ_KEYS = new Set(['query', 'id']);
const MAX_VALUE = 1024;

/** Parses and checks a provider request; returns the URL or null. The extension runs this again at its boundary. */
export function checkSourceRequest(provider: unknown, raw: unknown): URL | null {
  if ((provider !== 'jiosaavn' && provider !== 'radiojavan') || typeof raw !== 'string') return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash) return null;
  const keys = [...url.searchParams.keys()];
  if (new Set(keys).size !== keys.length || [...url.searchParams.values()].some((v) => v.length > MAX_VALUE)) return null;
  if (provider === 'jiosaavn') {
    const call = url.searchParams.get('__call');
    const ok = url.hostname === 'www.jiosaavn.com' && url.pathname === '/api.php' && !!call && JIO_CALLS.has(call);
    return ok && keys.every((k) => JIO_KEYS.has(k)) ? url : null;
  }
  const ok = url.hostname === 'rj-deskcloud.com' && RJ_PATHS.has(url.pathname);
  return ok && keys.every((k) => RJ_KEYS.has(k)) ? url : null;
}
