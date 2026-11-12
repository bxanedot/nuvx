import { botguard } from '@/playback/youtube/botguard';
import { ytAuthSnapshot } from '@/extension/useYtAuth';
import { getInnertube, getSignedInnertube } from '@/playback/youtube/innertube';
import { resolvePlayer, UnplayableError, usesWebPo } from '@/playback/youtube/resolver';

const MIN_VIDEO_ASPECT = 1.2;

export interface TrackAudio {
  bytes: ArrayBuffer;
  musicVideo: boolean;
}

const CHUNK_BYTES = 1024 * 1024;
const MAX_BYTES = 40 * 1024 * 1024;

export async function readAudioResponse(response: Response, maxBytes: number): Promise<Uint8Array> {
  const contentLength = Number(response.headers.get('content-length'));
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    await response.body?.cancel().catch(() => undefined);
    throw new Error('audio response exceeds byte limit');
  }
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array(0);
  const parts: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value.byteLength > maxBytes - size) {
        await reader.cancel().catch(() => undefined);
        throw new Error('audio response exceeds byte limit');
      }
      parts.push(value);
      size += value.byteLength;
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.byteLength;
  }
  return bytes;
}

export async function fetchTrackAudio(videoId: string, signal?: AbortSignal): Promise<TrackAudio> {
  let source = await resolveAudioUrl(videoId);
  const parts: Uint8Array[] = [];
  let at = 0;
  let refusals = 0;
  for (;;) {
    signal?.throwIfAborted();
    const { url, total } = source;
    const end = total ? Math.min(total, at + CHUNK_BYTES) - 1 : at + CHUNK_BYTES - 1;
    url.searchParams.set('range', `${at}-${end}`);
    const response = await fetch(url, { signal, credentials: 'omit' });
    if (response.status === 403 && refusals < MAX_REFUSALS) {
      refusals++;
      source = await resolveAudioUrl(videoId, source.itag);
      continue;
    }
    if (!response.ok) throw new Error(`audio fetch HTTP ${response.status} at byte ${at}`);
    const part = await readAudioResponse(response, MAX_BYTES - at);
    parts.push(part);
    at += part.byteLength;
    if (part.byteLength === 0 || (total ? at >= total : part.byteLength < CHUNK_BYTES)) break;
  }

  const out = new Uint8Array(at);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.byteLength;
  }
  return { bytes: out.buffer, musicVideo: source.musicVideo };
}

const MAX_REFUSALS = 2;

async function resolveAudioUrl(videoId: string, itag?: number): Promise<{ url: URL; total: number; itag: number; musicVideo: boolean }> {
  const { info, client, yt } = await resolveAsPlayerWould(videoId);
  const audio = (info.streaming_data?.adaptive_formats ?? []).filter(
    (f) => f.has_audio && !f.has_video && (f.url || f.signature_cipher || f.cipher) && !f.is_drc,
  );
  const pick = (mime: string) =>
    audio.filter((f) => f.mime_type.startsWith(mime)).sort((a, b) => b.bitrate - a.bitrate)[0];
  const format = (itag !== undefined ? audio.find((f) => f.itag === itag) : undefined) ?? pick('audio/webm') ?? pick('audio/mp4');
  if (!format) throw new Error('no direct audio URL (SABR only)');
  if (itag !== undefined && format.itag !== itag) throw new Error('the format changed between requests');

  const url = new URL(await format.decipher(yt.session.player));
  if (usesWebPo(client)) {
    const pot = await botguard.mint(videoId);
    if (pot) url.searchParams.set('pot', pot);
  }
  const pictures = (info.streaming_data?.adaptive_formats ?? []).filter((f) => f.has_video && f.width && f.height);
  const widest = pictures.sort((a, b) => b.width! * b.height! - a.width! * a.height!)[0];
  const musicVideo = !!widest && widest.width! / widest.height! >= MIN_VIDEO_ASPECT;
  return { url, total: format.content_length ?? 0, itag: format.itag, musicVideo };
}

async function resolveAsPlayerWould(videoId: string) {
  try {
    return await resolvePlayer(await getInnertube(), videoId);
  } catch (error) {
    if (!(error instanceof UnplayableError && error.ageGated) || !ytAuthSnapshot().signedIn) throw error;
    return resolvePlayer(await getSignedInnertube(), videoId, new Set(), true);
  }
}
