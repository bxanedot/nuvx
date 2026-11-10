import type { Innertube, Types } from 'youtubei.js/web';
import { getBridge } from '@/extension/bridge';
import { ensureYtAuth, identityOf, noteMusicIdentity } from '@/extension/useYtAuth';
import { trackLog } from '../trackLog';

let instance: Promise<Innertube> | null = null;

export function getInnertube(): Promise<Innertube> {
  instance ??= create().catch((error: unknown) => {
    instance = null;
    throw error;
  });
  return instance;
}

let signedInstance: Promise<Innertube> | null = null;

export function getSignedInnertube(): Promise<Innertube> {
  signedInstance ??= create(signedFetch).then(
    (yt) => {
      yt.session.logged_in = true;
      return yt;
    },
    (error: unknown) => {
      signedInstance = null;
      throw error;
    },
  );
  return signedInstance;
}

export async function getMusicClient(): Promise<Innertube> {
  const auth = await ensureYtAuth().catch(() => null);
  noteMusicIdentity(auth ? identityOf(auth) : 'anonymous');
  return auth?.signedIn ? getSignedInnertube() : getInnertube();
}

const YOUTUBE_HOSTS = new Set(['music.youtube.com', 'www.youtube.com', 'youtube.com']);

const SAFELISTED = new Set(['accept', 'accept-language', 'content-language']);

async function simpleRequestFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = new URL(request ? request.url : input.toString());

  if (!YOUTUBE_HOSTS.has(url.hostname) || url.pathname.startsWith('/api/jnn/')) {
    return fetch(input, init);
  }

  const method = init?.method ?? request?.method ?? 'GET';
  const source = new Headers(init?.headers ?? request?.headers);
  const headers = new Headers();
  source.forEach((value, key) => {
    if (SAFELISTED.has(key.toLowerCase())) headers.set(key, value);
  });

  let body: BodyInit | null | undefined = init?.body;
  if (body === undefined && request && method !== 'GET' && method !== 'HEAD') {
    body = await request.text();
  }
  if (body != null) headers.set('Content-Type', 'text/plain;charset=UTF-8');

  return fetch(url, {
    method,
    headers,
    body: body ?? null,
    signal: init?.signal ?? request?.signal ?? null,
    credentials: 'omit',
  });
}

function isInnertubeCall(url: URL): boolean {
  return (url.hostname === 'music.youtube.com' || url.hostname === 'www.youtube.com') && url.pathname.startsWith('/youtubei/');
}

function decodeBase64(base64: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

async function signedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = new URL(request ? request.url : input.toString());
  if (!isInnertubeCall(url)) return simpleRequestFetch(input, init);

  const bridge = getBridge();
  if (!bridge) throw new Error('Signed-in requests need the nuvx extension');
  const method = (init?.method ?? request?.method ?? 'GET').toUpperCase() as 'GET' | 'POST';
  const headers: Record<string, string> = {};
  new Headers(init?.headers ?? request?.headers).forEach((value, key) => (headers[key] = value));
  let body = init?.body ?? undefined;
  if (body === undefined && request && method !== 'GET') body = await request.text();
  if (body != null && typeof body !== 'string') body = await new Response(body).text();

  const response = await bridge.call(
    'yt.fetch',
    { url: url.toString(), method, headers, ...(typeof body === 'string' && { body }), auth: true },
    { ...(init?.signal && { signal: init.signal }) },
  );
  const payload = response.bodyEncoding === 'base64' ? decodeBase64(response.body) : response.body;
  return new Response(response.status === 204 || response.status === 304 ? null : payload, {
    status: response.status,
    headers: response.headers,
  });
}

async function isFirstRun(): Promise<boolean> {
  try {
    const databases = await indexedDB.databases();
    return !databases.some((db) => db.name === 'youtubei.js');
  } catch {
    return true;
  }
}

async function create(fetch: typeof globalThis.fetch = simpleRequestFetch): Promise<Innertube> {
  const started = performance.now();
  const { Innertube, Platform, UniversalCache } = await import('youtubei.js/web');

  Platform.shim.eval = async (data: Types.BuildScriptResult, env: Record<string, Types.VMPrimative>) => {
    const properties: string[] = [];
    if (env.n) properties.push(`n: exportedVars.nFunction(${JSON.stringify(env.n)})`);
    if (env.sig) properties.push(`sig: exportedVars.sigFunction(${JSON.stringify(env.sig)})`);
    return new Function(`${data.output}\nreturn { ${properties.join(', ')} }`)();
  };

  const firstRun = await isFirstRun();
  try {
    const yt = await Innertube.create({
      cache: new UniversalCache(true),
      fetch,
      retrieve_player: true,
    });
    const client = yt.session.context.client;
    trackLog.i(
      `InnerTube ready in ${Math.round(performance.now() - started)}ms ` +
        `(${client.clientName} ${client.clientVersion}, player ${yt.session.player?.player_id ?? 'none'})`,
    );
    return yt;
  } catch (error) {
    if (firstRun) indexedDB.deleteDatabase('youtubei.js');
    trackLog.e(`InnerTube failed to start: ${String(error)}`);
    throw error;
  }
}
