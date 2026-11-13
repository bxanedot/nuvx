// SPDX-License-Identifier: AGPL-3.0-only
import { trackLog } from '@/playback/trackLog';
import { getAnalysis, putAnalysis } from '../db';
import { fetchTrackAudio, type TrackAudio } from '../fetchAudio';
import type { AnalyzeResponse } from './analysis.worker';
import { BEAT_SR, VOCAL_WINDOW_SEC } from './models';
import type { TrackAnalysis, TrackTags } from './types';
import { ANALYSIS_VERSION } from './types';

export type AnalysisStatus = 'pending' | 'analyzing' | 'done' | 'error';

const RETRY_FAILED_MS = 60_000;
const ANALYSIS_SR = 44100;
const BEAT_WINDOW_SEC = 59;
const USE_BEAT_MODEL = true;
const USE_VOCAL_MODEL = true;
const KEEP_BYTES_FOR = 6;

class Analyzer {
  private worker: Worker | null = null;
  private queue: { videoId: string; tags: TrackTags }[] = [];
  private running = false;
  private pendingResolve: ((r: AnalyzeResponse) => void) | null = null;
  private readonly results = new Map<string, TrackAnalysis>();
  private readonly status = new Map<string, AnalysisStatus>();
  private readonly listeners = new Set<() => void>();
  private readonly failedAt = new Map<string, number>();
  private readonly bytes = new Map<string, Promise<TrackAudio>>();

  audioBytes(videoId: string): Promise<ArrayBuffer> {
    return this.trackAudio(videoId).then((a) => a.bytes);
  }

  private trackAudio(videoId: string): Promise<TrackAudio> {
    let held = this.bytes.get(videoId);
    if (held) {
      this.bytes.delete(videoId);
      this.bytes.set(videoId, held);
      return held;
    }
    held = fetchTrackAudio(videoId);
    held.catch(() => this.bytes.delete(videoId));
    this.bytes.set(videoId, held);
    while (this.bytes.size > KEEP_BYTES_FOR) this.bytes.delete(this.bytes.keys().next().value!);
    return held;
  }

  get(videoId: string): TrackAnalysis | null {
    return this.results.get(videoId) ?? null;
  }

  statusOf(videoId: string): AnalysisStatus | null {
    return this.status.get(videoId) ?? null;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  request(videoId: string, tags: TrackTags, urgent = false): void {
    const known = this.status.get(videoId);
    if (known === 'done' || known === 'analyzing') return;
    if (known === 'error' && Date.now() - (this.failedAt.get(videoId) ?? 0) < RETRY_FAILED_MS) return;
    if (known === 'pending') {
      if (urgent) {
        const at = this.queue.findIndex((q) => q.videoId === videoId);
        if (at > 0) this.queue.unshift(...this.queue.splice(at, 1));
      }
      return;
    }
    this.setStatus(videoId, 'pending');
    void getAnalysis(videoId).then((cached) => {
      if (cached && cached.analysisVersion === ANALYSIS_VERSION) {
        this.results.set(videoId, cached);
        this.queue = this.queue.filter((q) => q.videoId !== videoId);
        this.setStatus(videoId, 'done');
      }
    });
    if (urgent) this.queue.unshift({ videoId, tags });
    else this.queue.push({ videoId, tags });
    void this.run();
  }

  private setStatus(videoId: string, status: AnalysisStatus) {
    this.status.set(videoId, status);
    for (const l of this.listeners) l();
  }

  private ensureWorker(): Worker {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL('./analysis.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (ev: MessageEvent<AnalyzeResponse>) => {
      this.pendingResolve?.(ev.data);
      this.pendingResolve = null;
    };
    worker.onerror = (ev) => {
      this.pendingResolve?.({ type: 'error', fileKey: '', message: ev.message ?? 'worker error' });
      this.pendingResolve = null;
    };
    this.worker = worker;
    return worker;
  }

  private async run(): Promise<void> {
    if (this.running) return;
    this.running = true;
    await new Promise((r) => setTimeout(r, 50));
    while (this.queue.length > 0) {
      const { videoId, tags } = this.queue.shift()!;
      if (this.status.get(videoId) !== 'pending') continue;
      this.setStatus(videoId, 'analyzing');
      const started = performance.now();
      try {
        const analysis = await this.analyzeOne(videoId, tags);
        this.results.set(videoId, analysis);
        await putAnalysis(analysis);
        trackLog.i(
          `automix analysis: ${analysis.bpm?.toFixed(1) ?? '?'} BPM, ${analysis.keyName ?? '?'}, grid ${analysis.gridConfidence.toFixed(2)} in ${Math.round(performance.now() - started)}ms`,
          videoId,
        );
        this.setStatus(videoId, 'done');
      } catch (e) {
        trackLog.w(`automix analysis failed: ${String(e)}`, videoId);
        this.failedAt.set(videoId, Date.now());
        this.bytes.delete(videoId);
        this.setStatus(videoId, 'error');
      }
    }
    this.running = false;
  }

  private async analyzeOne(videoId: string, tags: TrackTags): Promise<TrackAnalysis> {
    const { bytes: arrayBuffer, musicVideo } = await this.trackAudio(videoId);
    const decodeCtx = new OfflineAudioContext({ numberOfChannels: 1, length: 1, sampleRate: ANALYSIS_SR });
    const buffer = await decodeCtx.decodeAudioData(arrayBuffer.slice(0));
    const mono = downmixToMono(buffer);
    const durationSec = buffer.duration;

    const beatWindows: { offsetSec: number; pcm: Float32Array }[] = [];
    const vocalWindows: { offsetSec: number; left: Float32Array; right: Float32Array }[] = [];
    if (USE_BEAT_MODEL) {
      const beatCtx = new OfflineAudioContext({ numberOfChannels: 1, length: 1, sampleRate: BEAT_SR });
      const mono22 = downmixToMono(await beatCtx.decodeAudioData(arrayBuffer.slice(0)));
      beatWindows.push({ offsetSec: 0, pcm: mono22.slice(0, Math.round(Math.min(durationSec, BEAT_WINDOW_SEC) * BEAT_SR)) });
      if (durationSec > BEAT_WINDOW_SEC) {
        const from = durationSec - BEAT_WINDOW_SEC;
        beatWindows.push({ offsetSec: from, pcm: mono22.slice(Math.round(from * BEAT_SR)) });
      }
    }
    if (USE_VOCAL_MODEL) {
      const left = buffer.getChannelData(0);
      const right = buffer.numberOfChannels > 1 ? buffer.getChannelData(1) : left;
      const vocalStarts = [0, VOCAL_WINDOW_SEC].filter((s) => s + VOCAL_WINDOW_SEC <= durationSec);
      for (let k = 1; k <= 3; k++) {
        const start = durationSec - k * VOCAL_WINDOW_SEC;
        if (start >= 2 * VOCAL_WINDOW_SEC) vocalStarts.push(start);
      }
      for (const start of vocalStarts) {
        const a = Math.round(start * ANALYSIS_SR);
        const b = Math.min(left.length, a + Math.floor(VOCAL_WINDOW_SEC * ANALYSIS_SR));
        vocalWindows.push({ offsetSec: start, left: left.slice(a, b), right: right.slice(a, b) });
      }
    }

    const worker = this.ensureWorker();
    const response = await new Promise<AnalyzeResponse>((resolve, reject) => {
      this.pendingResolve = resolve;
      try {
        worker.postMessage(
          { type: 'analyze', fileKey: videoId, samples: mono, sampleRate: ANALYSIS_SR, beatWindows, vocalWindows },
          [mono.buffer, ...beatWindows.map((w) => w.pcm.buffer), ...vocalWindows.flatMap((w) => [w.left.buffer, w.right.buffer])],
        );
      } catch (e) {
        this.pendingResolve = null;
        reject(e);
      }
    });

    if (response.type === 'error' || !response.result) {
      throw new Error(response.message ?? 'analysis failed');
    }
    return {
      fileKey: videoId,
      path: `youtube:${videoId}`,
      tags,
      durationSec,
      ...response.result,
      musicVideo,
      analysisVersion: ANALYSIS_VERSION,
    };
  }
}

function downmixToMono(buffer: AudioBuffer): Float32Array {
  const channels = buffer.numberOfChannels;
  if (channels === 1) return buffer.getChannelData(0).slice();
  const out = new Float32Array(buffer.length);
  for (let ch = 0; ch < channels; ch++) {
    const data = buffer.getChannelData(ch);
    for (let i = 0; i < out.length; i++) out[i]! += data[i]! / channels;
  }
  return out;
}

export const analyzer = new Analyzer();
