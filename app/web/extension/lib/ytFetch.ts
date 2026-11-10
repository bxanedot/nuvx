import type { HttpResponse, YtFetchParams } from '@nuvx/shared/bridge';
import { installExtensionFetchRule } from './rules';
import { activeIdentity, signedFetch, SignedOutError } from './ytAuth';

const ALLOWED = [/^https:\/\/www\.youtube\.com\/api\/jnn\//];
const ALLOWED_SIGNED = [/^https:\/\/(music|www)\.youtube\.com\/youtubei\/v1\//];
const STATS = /^https:\/\/(s|music|www)\.youtube\.com\/api\/stats\/(playback|watchtime|atr)(\?|$)/;
const MUSIC_ORIGIN = 'https://music.youtube.com';
const IDENTITY_HEADERS = ['authorization', 'cookie', 'x-goog-authuser', 'x-goog-pageid', 'x-origin', 'origin', 'referer'];

let ruleReady: Promise<void> | null = null;

function toBytes(base64: string): Uint8Array {
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

function forbidden(url: string): Error {
  const { origin, pathname } = new URL(url);
  return Object.assign(new Error(`yt.fetch doesn't serve ${origin}${pathname}`), { code: 'forbidden' });
}

export async function ytFetch(request: YtFetchParams): Promise<HttpResponse> {
  if (request.auth) return signedYtFetch(request);
  if (!ALLOWED.some((pattern) => pattern.test(request.url))) throw forbidden(request.url);
  ruleReady ??= installExtensionFetchRule().catch((error: unknown) => {
    ruleReady = null;
    throw error;
  });
  await ruleReady;

  const body =
    request.body === undefined ? undefined : request.bodyEncoding === 'base64' ? toBytes(request.body) : request.body;
  const response = await fetch(request.url, {
    method: request.method ?? 'GET',
    headers: request.headers ?? {},
    body: body as BodyInit | undefined,
    credentials: 'omit',
  });
  return serialise(response);
}

async function signedYtFetch(request: YtFetchParams): Promise<HttpResponse> {
  const stats = STATS.test(request.url) && (request.method ?? 'GET') === 'GET';
  if (!stats && !ALLOWED_SIGNED.some((pattern) => pattern.test(request.url))) throw forbidden(request.url);
  const identity = await activeIdentity();
  if (!identity) throw new SignedOutError('Not signed in to YouTube Music.');
  const headers: Record<string, string> = {};
  for (const [key, value] of Object.entries(request.headers ?? {})) {
    if (!IDENTITY_HEADERS.includes(key.toLowerCase())) headers[key] = value;
  }
  const body =
    request.body === undefined ? undefined : request.bodyEncoding === 'base64' ? toBytes(request.body) : request.body;
  const response = await signedFetch(
    request.url,
    { method: request.method ?? 'GET', headers, body: body as BodyInit | undefined },
    identity,
    stats ? MUSIC_ORIGIN : undefined,
  );
  return serialise(response);
}

async function serialise(response: Response): Promise<HttpResponse> {
  const headers: Record<string, string> = {};
  response.headers.forEach((value, key) => (headers[key] = value));
  const type = response.headers.get('content-type') ?? '';
  const textual = /json|text|javascript|xml/.test(type);
  return {
    status: response.status,
    headers,
    url: response.url,
    bodyEncoding: textual ? 'text' : 'base64',
    body: textual ? await response.text() : toBase64(new Uint8Array(await response.arrayBuffer())),
  };
}
