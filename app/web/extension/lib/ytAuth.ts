import type { YtAccount, YtAuthState, YtChannel } from '@nuvx/shared/bridge';
import { installExtensionFetchRule } from './rules';

declare const __STORE_BUILD__: boolean;

const STORE_BUILD = typeof __STORE_BUILD__ !== 'undefined' && __STORE_BUILD__;

export const MUSIC_ORIGIN = 'https://music.youtube.com';

const SIGNING_COOKIES = ['SAPISID', '__Secure-3PAPISID', '__Secure-1PAPISID'];
const SESSION_COOKIES = new Set([...SIGNING_COOKIES, 'SID', '__Secure-1PSID', '__Secure-3PSID']);

const WEB_REMIX_VERSION = '1.20251006.03.00';
const MAX_LOGINS = 10;
const ACCOUNTS_TTL_MS = 10 * 60_000;

interface Prefs {
  connected: boolean;
  selectedChannelId: string | null;
}

const PREFS_KEY = 'yt.prefs';

async function loadPrefs(): Promise<Prefs> {
  const stored = (await browser.storage.local.get(PREFS_KEY))[PREFS_KEY] as Partial<Prefs> | undefined;
  return { connected: stored?.connected ?? !STORE_BUILD, selectedChannelId: stored?.selectedChannelId ?? null };
}

async function savePrefs(change: Partial<Prefs>): Promise<void> {
  await browser.storage.local.set({ [PREFS_KEY]: { ...(await loadPrefs()), ...change } });
}

async function signingSecret(): Promise<string | null> {
  for (const name of SIGNING_COOKIES) {
    const cookie = await browser.cookies.get({ url: `${MUSIC_ORIGIN}/`, name }).catch(() => null);
    if (cookie?.value) return cookie.value;
  }
  return null;
}

async function sapisidHash(secret: string, origin: string): Promise<string> {
  const timestamp = Math.floor(Date.now() / 1000);
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(`${timestamp} ${secret} ${origin}`));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `SAPISIDHASH ${timestamp}_${hex}`;
}

interface Identity {
  authUser: string;
  pageId: string | null;
}

export class SignedOutError extends Error {
  readonly code = 'unauthenticated';
}

let ruleReady: Promise<void> | null = null;

export async function signedFetch(
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: BodyInit | null },
  identity: Identity,
  signAs?: string,
): Promise<Response> {
  const secret = await signingSecret();
  if (!secret) throw new SignedOutError('The browser is not signed in to YouTube.');
  ruleReady ??= installExtensionFetchRule().catch((error: unknown) => {
    ruleReady = null;
    throw error;
  });
  await ruleReady;

  const origin = signAs ?? new URL(url).origin;
  const headers = new Headers(init.headers);
  headers.set('Authorization', await sapisidHash(secret, origin));
  headers.set('X-Goog-AuthUser', identity.authUser);
  headers.set('X-Origin', origin);
  if (identity.pageId) headers.set('X-Goog-PageId', identity.pageId);
  else headers.delete('X-Goog-PageId');
  return fetch(url, { method: init.method ?? 'GET', headers, body: init.body ?? null, credentials: 'include' });
}

async function musicApi(endpoint: string, identity: Identity): Promise<unknown> {
  const response = await signedFetch(
    `${MUSIC_ORIGIN}/youtubei/v1/${endpoint}?prettyPrint=false`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Youtube-Client-Name': '67',
        'X-Youtube-Client-Version': WEB_REMIX_VERSION,
      },
      body: JSON.stringify({
        context: { client: { clientName: 'WEB_REMIX', clientVersion: WEB_REMIX_VERSION, hl: 'en', gl: 'US' }, user: {} },
      }),
    },
    identity,
  );
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

type Json = Record<string, unknown>;

function renderers(root: unknown, key: string, out: Json[] = []): Json[] {
  if (Array.isArray(root)) for (const item of root) renderers(item, key, out);
  else if (root && typeof root === 'object') {
    for (const [k, v] of Object.entries(root)) {
      if (k === key && v && typeof v === 'object' && !Array.isArray(v)) out.push(v as Json);
      else renderers(v, key, out);
    }
  }
  return out;
}

function findString(root: unknown, key: string): string | null {
  if (Array.isArray(root)) {
    for (const item of root) {
      const found = findString(item, key);
      if (found) return found;
    }
  } else if (root && typeof root === 'object') {
    for (const [k, v] of Object.entries(root)) {
      if (k === key && typeof v === 'string' && v) return v;
      const found = findString(v, key);
      if (found) return found;
    }
  }
  return null;
}

function text(node: unknown): string {
  const n = node as { runs?: { text?: string }[]; simpleText?: string } | undefined;
  if (n?.runs) return n.runs.map((r) => r.text ?? '').join('');
  return n?.simpleText ?? '';
}

function photo(item: Json): string | null {
  const thumbnails = (item.accountPhoto as { thumbnails?: { url?: string }[] } | undefined)?.thumbnails;
  return thumbnails?.at(-1)?.url ?? null;
}

interface StoredChannel extends YtChannel {
  authUser: string;
  pageId: string | null;
  selectedOnWeb: boolean;
}

interface StoredAccount extends Omit<YtAccount, 'channels'> {
  channels: StoredChannel[];
}

function channelId(authUser: string, pageId: string | null): string {
  return `${authUser}:${pageId ?? 'own'}`;
}

function parseChannels(root: unknown, authUser: string): StoredChannel[] {
  const channels: StoredChannel[] = [];
  for (const item of renderers(root, 'accountItem')) {
    const name = text(item.accountName);
    if (!name) continue;
    const pageId = findString(item, 'pageId');
    const handle = text(item.channelHandle) || text(item.accountByline) || null;
    const id = channelId(authUser, pageId);
    if (channels.some((c) => c.id === id)) continue;
    channels.push({
      id,
      name,
      handle,
      avatarUrl: photo(item),
      brand: pageId !== null,
      authUser,
      pageId,
      selectedOnWeb: item.isSelected === true,
    });
  }
  return channels;
}

function describeEmpty(endpoint: string, body: unknown): string {
  const services =
    (body as { responseContext?: { serviceTrackingParams?: { params?: Json[] }[] } })?.responseContext
      ?.serviceTrackingParams ?? [];
  const loggedIn = services.flatMap((service) => service.params ?? []).find((param) => param.key === 'logged_in')?.value;
  if (loggedIn === '0') return `${endpoint}: YouTube answered as if signed out (the session wasn't accepted)`;
  const keys = body && typeof body === 'object' ? Object.keys(body).join(', ') : typeof body;
  return `${endpoint}: no account in the answer (${keys})`;
}

async function readLogin(authUser: string): Promise<{ account: StoredAccount | null; why: string | null }> {
  const identity = { authUser, pageId: null };
  let header: Json | undefined;
  try {
    const menu = await musicApi('account/account_menu', identity);
    header = renderers(menu, 'activeAccountHeaderRenderer')[0];
    if (!header) return { account: null, why: describeEmpty('account_menu', menu) };
  } catch (error) {
    return { account: null, why: `account_menu: ${error instanceof Error ? error.message : String(error)}` };
  }

  const name = text(header.accountName) || 'YouTube Music';
  const email = text(header.email) || null;
  const handle = text(header.channelHandle) || null;
  const avatarUrl = photo(header);
  let channels: StoredChannel[] = [];
  try {
    channels = parseChannels(await musicApi('account/accounts_list', identity), authUser);
  } catch {
  }
  if (!channels.length) {
    channels = [
      { id: channelId(authUser, null), name, handle: handle ?? email, avatarUrl, brand: false, authUser, pageId: null, selectedOnWeb: true },
    ];
  }
  return { account: { authUser, name, email, avatarUrl, channels }, why: null };
}

async function webSessionIndex(): Promise<string | null> {
  try {
    const response = await signedFetch(`${MUSIC_ORIGIN}/`, { headers: { Accept: 'text/html' } }, { authUser: '0', pageId: null });
    const html = await response.text();
    return /"SESSION_INDEX":"?(\d+)/.exec(html)?.[1] ?? null;
  } catch {
    return null;
  }
}

async function readAccounts(): Promise<{ list: StoredAccount[]; webIndex: string | null; problem: string | null }> {
  const [results, webIndex] = await Promise.all([
    Promise.all(Array.from({ length: MAX_LOGINS }, (_, i) => readLogin(String(i)))),
    webSessionIndex(),
  ]);
  const list: StoredAccount[] = [];
  const seen = new Set<string>();
  for (const { account } of results) {
    if (!account) continue;
    const key = account.email ?? `${account.name}|${account.channels.map((c) => c.id.split(':')[1]).join(',')}`;
    if (seen.has(key)) continue;
    seen.add(key);
    list.push(account);
  }
  let problem: string | null = null;
  if (!list.length) {
    problem = results[Number(webIndex ?? 0)]?.why ?? results[0]?.why ?? 'no signed-in Google account found';
    console.warn(`nuvx: no YouTube account found: ${problem}`);
  }
  return { list, webIndex, problem };
}

interface Accounts {
  list: StoredAccount[];
  webIndex: string | null;
  problem: string | null;
}

let cache: (Accounts & { at: number }) | null = null;
let generation = 0;
let reading: { generation: number; promise: Promise<Accounts> } | null = null;

async function accounts(refresh: boolean): Promise<Accounts> {
  if (!refresh && cache && Date.now() - cache.at < ACCOUNTS_TTL_MS) return cache;
  if (reading?.generation === generation) return reading.promise;
  const readGeneration = generation;
  const promise: Promise<Accounts> = readAccounts()
    .then(async (read) => {
      if (readGeneration !== generation) return accounts(true);
      cache = { ...read, at: Date.now() };
      return read;
    })
    .finally(() => {
      if (reading?.promise === promise) reading = null;
    });
  reading = { generation: readGeneration, promise };
  return promise;
}

export function invalidateAccounts(): void {
  generation++;
  cache = null;
  reading = null;
}

export function isSessionCookie(cookie: { name: string; domain: string }): boolean {
  return SESSION_COOKIES.has(cookie.name) && /(^|\.)youtube\.com$/.test(cookie.domain.replace(/^\./, ''));
}

function chooseChannel({ list, webIndex }: Accounts, selectedId: string | null): StoredChannel | null {
  const all = list.flatMap((a) => a.channels);
  const web = list.find((a) => a.authUser === webIndex) ?? list[0];
  return (
    all.find((c) => c.id === selectedId) ??
    web?.channels.find((c) => c.selectedOnWeb) ??
    web?.channels[0] ??
    null
  );
}

function publicChannel({ id, name, handle, avatarUrl, brand }: StoredChannel): YtChannel {
  return { id, name, handle, avatarUrl, brand };
}

export async function authState(refresh = false): Promise<YtAuthState> {
  const prefs = await loadPrefs();
  const browserSignedIn = (await signingSecret()) !== null;
  if (!browserSignedIn || !prefs.connected) {
    return { browserSignedIn, connected: prefs.connected, signedIn: false, accounts: [], active: null, problem: null };
  }
  const read = await accounts(refresh);
  const { list } = read;
  const active = chooseChannel(read, prefs.selectedChannelId);
  return {
    browserSignedIn,
    connected: true,
    signedIn: active !== null,
    problem: active ? null : read.problem,
    accounts: list.map((a) => ({ ...a, channels: a.channels.map(publicChannel) })),
    active: active && { ...publicChannel(active), authUser: active.authUser },
  };
}

export async function activeIdentity(): Promise<Identity | null> {
  const prefs = await loadPrefs();
  if (!prefs.connected || (await signingSecret()) === null) return null;
  const active = chooseChannel(await accounts(false), prefs.selectedChannelId);
  return active && { authUser: active.authUser, pageId: active.pageId };
}

export async function selectChannel(id: string): Promise<YtAuthState> {
  const { list } = await accounts(false);
  if (!list.some((a) => a.channels.some((c) => c.id === id))) throw new Error(`No channel ${id} in this browser`);
  await savePrefs({ selectedChannelId: id });
  return authState();
}

export async function setConnected(connected: boolean): Promise<YtAuthState> {
  await savePrefs({ connected });
  return authState(connected);
}
