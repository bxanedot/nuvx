import type { ApiResponse, Innertube, YT } from 'youtubei.js/web';
import { trackLog } from '../trackLog';
import { botguard } from './botguard';

export const CLIENT_ORDER = ['VISIONOS', 'YTMUSIC', 'WEB', 'MWEB'] as const;
export type PlayerClientName = (typeof CLIENT_ORDER)[number];

const WEBPO_CLIENTS: ReadonlySet<PlayerClientName> = new Set(['YTMUSIC', 'WEB', 'MWEB']);

export function usesWebPo(client: PlayerClientName): boolean {
  return WEBPO_CLIENTS.has(client);
}

async function playerRequestToken(yt: Innertube, client: PlayerClientName): Promise<string | undefined> {
  if (!WEBPO_CLIENTS.has(client)) return undefined;
  const visitorData = yt.session.context.client.visitorData;
  if (!visitorData) return undefined;
  return botguard.mint(decodeURIComponent(visitorData));
}

export const CLIENT_ATTEMPTS = 3;
const EXCLUDE_MS = 10 * 60_000;
const UNPLAYABLE_TTL_MS = 10 * 60_000;
const MAX_REMEMBERED = 64;

export class UnplayableError extends Error {
  override readonly name = 'UnplayableError';

  readonly ageGated: boolean;

  constructor(message: string, ageGated = false) {
    super(message);
    this.ageGated = ageGated;
  }
}

export interface ResolvedPlayer {
  videoId: string;
  client: PlayerClientName;
  info: YT.VideoInfo;
  yt: Innertube;
  signed: boolean;
}

const unplayable = new Map<string, { reason: string; at: number; ageGated: boolean }>();
const excluded = new Map<string, Map<PlayerClientName, number>>();

const keyOf = (videoId: string, signed: boolean) => (signed ? `${videoId}#signed` : videoId);

function knownVerdict(videoId: string, signed: boolean): { reason: string; ageGated: boolean } | null {
  const key = keyOf(videoId, signed);
  const verdict = unplayable.get(key);
  if (!verdict) return null;
  if (Date.now() - verdict.at < UNPLAYABLE_TTL_MS) return verdict;
  unplayable.delete(key);
  return null;
}

export function unplayableReason(videoId: string, signed = false): string | null {
  return knownVerdict(videoId, signed)?.reason ?? null;
}

function rememberUnplayable(videoId: string, signed: boolean, reason: string, ageGated: boolean) {
  if (unplayable.size > MAX_REMEMBERED) unplayable.clear();
  unplayable.set(keyOf(videoId, signed), { reason, at: Date.now(), ageGated });
  trackLog.w(`not playable${signed ? ' signed in' : ''}: ${reason}; not asking again for 10 minutes`, videoId);
}

export function excludeClient(videoId: string, client: PlayerClientName, why: string, signed = false) {
  trackLog.w(`${client}${signed ? ' (signed in)' : ''} refused: ${why}; skipping it for this track`, videoId);
  const key = keyOf(videoId, signed);
  let entries = excluded.get(key);
  if (!entries) {
    if (excluded.size > MAX_REMEMBERED) excluded.clear();
    entries = new Map();
    excluded.set(key, entries);
  }
  entries.set(client, Date.now() + EXCLUDE_MS);
}

export const DEBUG_CLIENTS_KEY = 'nuvx:debug:clients';

function clientOrder(): readonly PlayerClientName[] {
  const forced = localStorage.getItem(DEBUG_CLIENTS_KEY)
    ?.split(',')
    .map((c) => c.trim())
    .filter((c): c is PlayerClientName => (CLIENT_ORDER as readonly string[]).includes(c));
  return forced?.length ? forced : CLIENT_ORDER;
}

const SIGNED_ORDER: readonly PlayerClientName[] = ['YTMUSIC', 'WEB', 'VISIONOS'];

export function clientsFor(videoId: string, signed = false): PlayerClientName[] {
  const entries = excluded.get(keyOf(videoId, signed));
  const now = Date.now();
  return (signed ? SIGNED_ORDER : clientOrder()).filter((client) => {
    const until = entries?.get(client);
    return until === undefined || until <= now;
  });
}

export function onSessionChanged() {
  unplayable.clear();
  excluded.clear();
}

export async function playerRequest(
  yt: Innertube,
  videoId: string,
  client: PlayerClientName,
  reloadPlaybackContext?: unknown,
): Promise<ApiResponse> {
  const poToken = await playerRequestToken(yt, client);
  return yt.actions.execute('/player', {
    ...(poToken && { serviceIntegrityDimensions: { poToken } }),
    videoId,
    contentCheckOk: true,
    racyCheckOk: true,
    client,
    playbackContext: {
      contentPlaybackContext: { signatureTimestamp: yt.session.player?.signature_timestamp },
      ...(reloadPlaybackContext !== undefined && { reloadPlaybackContext }),
    },
  });
}

const AGE_GATE = /confirm your age|age-restricted|inappropriate for some users|sign in to confirm/;

function permanentReason(status: string | undefined, reason: string | undefined, signedIn: boolean): string | null {
  const text = (reason ?? '').toLowerCase();
  if (AGE_GATE.test(text)) {
    return signedIn
      ? 'YouTube won’t serve this age-restricted track to this account. Check the account’s age is verified on YouTube.'
      : 'This track is age-restricted. Sign in to YouTube Music in this browser to play it.';
  }
  if (/country|region/.test(text)) return 'This track isn’t available in your country';
  if (/private/.test(text)) return 'This track is private';
  if (/terminated/.test(text)) return 'The channel behind this track was terminated';
  if (/premium/.test(text)) return 'This track needs YouTube Music Premium';
  if (status === 'ERROR' && /removed|no longer available|does not exist/.test(text)) return 'This track was removed from YouTube';
  return null;
}

export async function resolvePlayer(
  yt: Innertube,
  videoId: string,
  skip: ReadonlySet<PlayerClientName> = new Set(),
  signed = false,
): Promise<ResolvedPlayer> {
  const known = knownVerdict(videoId, signed);
  if (known) throw new UnplayableError(known.reason, known.ageGated);

  const { Utils, YT } = await import('youtubei.js/web');
  const candidates = clientsFor(videoId, signed).filter((c) => !skip.has(c)).slice(0, CLIENT_ATTEMPTS);
  let lastReason = 'No YouTube client would play this track';
  let permanent: string | null = null;
  let ageGated = false;

  for (const client of candidates) {
    const started = performance.now();
    try {
      const response = await playerRequest(yt, videoId, client);
      const info = new YT.VideoInfo([response], yt.actions, Utils.generateRandomString(16));
      const status = info.playability_status?.status;
      const ms = Math.round(performance.now() - started);
      const streaming = info.streaming_data;
      const playable = !!streaming?.server_abr_streaming_url || !!streaming?.adaptive_formats.some((f) => f.has_audio && !f.has_video);
      if (status === 'OK' && playable) {
        trackLog.d(`${client}${signed ? ' (signed in)' : ''} answered OK in ${ms}ms`, videoId);
        return { videoId, client, info, yt, signed };
      }
      const reason = info.playability_status?.reason ?? (status === 'OK' ? 'no audio stream in the response' : status ?? 'no status');
      lastReason = reason;
      const verdict = permanentReason(status, reason, signed);
      if (verdict) {
        permanent = verdict;
        ageGated ||= AGE_GATE.test(reason.toLowerCase());
      }
      excludeClient(videoId, client, `${status}: ${reason} (${ms}ms)`, signed);
    } catch (error) {
      lastReason = String(error);
      excludeClient(videoId, client, `request failed: ${String(error)}`, signed);
    }
  }

  if (permanent) {
    rememberUnplayable(videoId, signed, permanent, ageGated);
    throw new UnplayableError(permanent, ageGated);
  }
  throw new Error(lastReason);
}
