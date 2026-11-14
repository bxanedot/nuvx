import { ytAuthSnapshot } from '@/extension/useYtAuth';
import { trackLog } from '../trackLog';
import { getInnertube, getSignedInnertube } from './innertube';
import { resolvePlayer, UnplayableError, type PlayerClientName } from './resolver';
import { alignAudio, analyseAudio } from './videoAlignment';

const MAX_AUDIO_BYTES = 32 * 1024 * 1024;
const MAX_CACHED_PAIRS = 128;
const ANALYSIS_TIMEOUT_MS = 30_000;
const WEB_CLIENTS: ReadonlySet<PlayerClientName> = new Set(['YTMUSIC', 'WEB', 'MWEB']);

const offsets = new Map<string, number>();
const rejected = new Set<string>();
const pending = new Map<string, Promise<number | null>>();

function pairKey(songId: string, videoId: string): string {
  return `${songId}>${videoId}`;
}

function remember(key: string, offsetMs: number) {
  if (offsets.size >= MAX_CACHED_PAIRS) offsets.clear();
  offsets.set(key, offsetMs);
}

async function resolveAudioUrl(videoId: string): Promise<string | null> {
  let yt = await getInnertube();
  let resolved;
  try {
    resolved = await resolvePlayer(yt, videoId, WEB_CLIENTS);
  } catch (error) {
    if (!(error instanceof UnplayableError && error.ageGated) || !ytAuthSnapshot().signedIn) throw error;
    yt = await getSignedInnertube();
    resolved = await resolvePlayer(yt, videoId, new Set(), true);
  }
  const formats = resolved.info.streaming_data?.adaptive_formats ?? [];
  const audio = formats
    .filter((format) => format.has_audio && !format.has_video && (format.url || format.signature_cipher || format.cipher))
    .sort((left, right) => right.bitrate - left.bitrate)[0];
  if (!audio) return null;
  if (audio.url) return audio.url;
  const url = await audio.decipher(resolved.yt.session.player);
  return url || null;
}

async function envelope(videoId: string): Promise<Float32Array | null> {
  const url = await resolveAudioUrl(videoId);
  if (!url) return null;
  const response = await fetch(url, { signal: AbortSignal.timeout(ANALYSIS_TIMEOUT_MS) });
  if (!response.ok) return null;
  const declared = Number(response.headers.get('content-length') ?? '0');
  if (declared > MAX_AUDIO_BYTES) return null;
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_AUDIO_BYTES) return null;
  const context = new AudioContext();
  try {
    const buffer = await context.decodeAudioData(bytes);
    const frames = buffer.length;
    const channels = buffer.numberOfChannels;
    const mono = new Float32Array(frames);
    for (let channel = 0; channel < channels; channel++) {
      const samples = buffer.getChannelData(channel);
      for (let frame = 0; frame < frames; frame++) mono[frame]! += samples[frame]!;
    }
    if (channels > 1) {
      for (let frame = 0; frame < frames; frame++) mono[frame]! /= channels;
    }
    return analyseAudio(mono, 1, buffer.sampleRate);
  } finally {
    void context.close();
  }
}

async function compute(songId: string, videoId: string, key: string): Promise<number | null> {
  try {
    const [source, target] = await Promise.all([envelope(songId), envelope(videoId)]);
    if (!source || !target) {
      trackLog.w('video alignment: the audio for one side is unavailable', videoId);
      return null;
    }
    const offset = alignAudio(source, target);
    if (offset === null) {
      rejected.add(key);
      trackLog.i('video alignment: not confident, keeping timestamp sync', videoId);
      return null;
    }
    remember(key, offset);
    trackLog.i(`video alignment: video runs ${offset}ms from the song`, videoId);
    return offset;
  } catch (error) {
    trackLog.w(`video alignment: ${error instanceof Error ? error.message : String(error)}`, videoId);
    return null;
  }
}

export function alignVideoPair(songId: string, videoId: string): Promise<number | null> {
  if (!songId || !videoId || songId === videoId) return Promise.resolve(0);
  const key = pairKey(songId, videoId);
  const known = offsets.get(key);
  if (known !== undefined) return Promise.resolve(known);
  if (rejected.has(key)) return Promise.resolve(null);
  const running = pending.get(key);
  if (running) return running;
  const task = compute(songId, videoId, key).finally(() => pending.delete(key));
  pending.set(key, task);
  return task;
}
