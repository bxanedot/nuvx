import { BridgeCallError } from '@nuvx/shared/bridge';
import { getBridge } from '@/extension/bridge';

export const LYRIC_EXTENSION_ORIGINS = [
  'https://mobileservice.kugou.com',
  'https://lyrics.kugou.com',
  'https://apic.musixmatch.com',
  'https://www.musixmatch.com',
  'https://genius.com',
  'https://music.apple.com',
  'https://amp-api.music.apple.com',
  'https://api.paxsenix.org',
  'https://lyrics-api.boidu.dev',
  'https://unison.boidu.dev',
];

const DIRECT_TIMEOUT_MS = 6000;
const EXTENSION_TIMEOUT_MS = 15_000;

export const ungrantedOrigins = new Set<string>();
const listeners = new Set<() => void>();
export function onUngrantedChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const grantListeners = new Set<() => void>();
export function onGranted(listener: () => void): () => void {
  grantListeners.add(listener);
  return () => grantListeners.delete(listener);
}

export async function recheckGrants(): Promise<void> {
  const bridge = getBridge();
  if (!bridge || !ungrantedOrigins.size) return;
  const { granted } = await bridge.call('lyrics.permissions', { origins: [...ungrantedOrigins] }).catch(() => ({ granted: [] }));
  if (!granted.length) return;
  granted.forEach((o) => ungrantedOrigins.delete(o));
  listeners.forEach((l) => l());
  grantListeners.forEach((l) => l());
}

if (typeof window !== 'undefined') {
  window.addEventListener('focus', () => void recheckGrants());
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && void recheckGrants());
}

export interface GetOptions {
  route?: 'direct' | 'extension';
  headers?: Record<string, string>;
  patient?: boolean;
}

async function viaExtension(url: string, headers: Record<string, string> | undefined): Promise<string | null> {
  const bridge = getBridge();
  if (!bridge) return null;
  try {
    const response = await bridge.call(
      'lyrics.fetch',
      { url, ...(headers && { headers }) },
      { timeoutMs: EXTENSION_TIMEOUT_MS },
    );
    if (response.status < 200 || response.status >= 300) return null;
    return response.bodyEncoding === 'base64' ? new TextDecoder().decode(Uint8Array.from(atob(response.body), (c) => c.charCodeAt(0))) : response.body;
  } catch (error) {
    if (error instanceof BridgeCallError && error.code === 'permission') {
      const origin = new URL(url).origin;
      if (!ungrantedOrigins.has(origin)) {
        ungrantedOrigins.add(origin);
        listeners.forEach((l) => l());
      }
    }
    return null;
  }
}

export async function lyricsGet(url: string, { route = 'direct', headers, patient }: GetOptions = {}): Promise<string | null> {
  if (route === 'extension') return viaExtension(url, headers);
  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json, text/plain, */*', ...headers },
      signal: AbortSignal.timeout(patient ? EXTENSION_TIMEOUT_MS : DIRECT_TIMEOUT_MS),
      referrerPolicy: 'no-referrer',
    });
    return response.ok ? await response.text() : null;
  } catch (error) {
    if (error instanceof TypeError) return viaExtension(url, headers);
    return null;
  }
}

export function urlWith(base: string, params: Record<string, string | number | null | undefined>): string {
  const url = new URL(base);
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') url.searchParams.set(key, String(value));
  }
  return url.toString();
}
