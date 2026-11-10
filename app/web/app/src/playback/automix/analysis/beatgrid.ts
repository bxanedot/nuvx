// SPDX-License-Identifier: AGPL-3.0-only

export interface GridFit {
  bpm: number;
  beatIntervalSec: number;
  offsetSec: number;
  residualMs: number;
}

export function fitGrid(ticks: ArrayLike<number>): GridFit | null {
  const n = ticks.length;
  if (n < 8) return null;
  const gaps: number[] = [];
  for (let i = 1; i < n; i++) gaps.push(ticks[i]! - ticks[i - 1]!);
  gaps.sort((a, b) => a - b);
  const median = gaps[gaps.length >> 1]!;
  if (!(median > 0)) return null;

  let best = { interval: median, coherence: -1, phase: 0 };
  const scan = (lo: number, hi: number, steps: number) => {
    for (let k = 0; k <= steps; k++) {
      const interval = lo + ((hi - lo) * k) / steps;
      let c = 0;
      let s = 0;
      for (let i = 0; i < n; i++) {
        const a = (2 * Math.PI * ticks[i]!) / interval;
        c += Math.cos(a);
        s += Math.sin(a);
      }
      const coherence = Math.hypot(c, s) / n;
      if (coherence > best.coherence) best = { interval, coherence, phase: Math.atan2(s, c) };
    }
  };
  scan(median * 0.96, median * 1.04, 400);
  const step = best.interval * 0.0002;
  scan(best.interval - step, best.interval + step, 40);

  const interval = best.interval;
  const offset = ((((best.phase / (2 * Math.PI)) % 1) + 1) % 1) * interval;
  const clip = interval / 4;
  let ss = 0;
  for (let i = 0; i < n; i++) {
    const beats = (ticks[i]! - offset) / interval;
    const r = Math.min(clip, Math.abs(beats - Math.round(beats)) * interval);
    ss += r * r;
  }
  const residualMs = Math.sqrt(ss / n) * 1000;
  return { bpm: 60 / interval, beatIntervalSec: interval, offsetSec: offset, residualMs };
}

export function normalizeTempo(fit: GridFit): GridFit {
  let { bpm, beatIntervalSec } = fit;
  while (bpm < 70) {
    bpm *= 2;
    beatIntervalSec /= 2;
  }
  while (bpm > 180) {
    bpm /= 2;
    beatIntervalSec *= 2;
  }
  return { ...fit, bpm, beatIntervalSec };
}

export interface DownbeatResult {
  firstDownbeatSec: number;
  confidence: number;
}

export function findDownbeat(
  samples: Float32Array,
  sampleRate: number,
  beatIntervalSec: number,
  offsetSec: number,
): DownbeatResult {
  const low = kickBandEnvelope(samples, sampleRate);
  const durationSec = samples.length / sampleRate;
  const windowSec = 0.08;
  const half = Math.round((windowSec / 2) * sampleRate);

  const scores = [0, 0, 0, 0];
  const counts = [0, 0, 0, 0];
  for (let i = 0; ; i++) {
    const t = offsetSec + i * beatIntervalSec;
    if (t + windowSec > durationSec) break;
    const c = Math.round(t * sampleRate);
    let acc = 0;
    const from = Math.max(0, c - half);
    const to = Math.min(low.length, c + half);
    for (let s = from; s < to; s++) acc += low[s]!;
    const phase = i % 4;
    scores[phase]! += acc / Math.max(1, to - from);
    counts[phase]!++;
  }
  for (let p = 0; p < 4; p++) scores[p]! /= Math.max(1, counts[p]!);

  let best = 0;
  for (let p = 1; p < 4; p++) if (scores[p]! > scores[best]!) best = p;
  const sorted = [...scores].sort((a, b) => b - a);
  const confidence = sorted[0]! > 0 ? (sorted[0]! - sorted[1]!) / sorted[0]! : 0;

  let firstDownbeatSec = offsetSec + best * beatIntervalSec;
  const barSec = 4 * beatIntervalSec;
  while (firstDownbeatSec - barSec >= 0) firstDownbeatSec -= barSec;
  return { firstDownbeatSec, confidence };
}

function kickBandEnvelope(samples: Float32Array, sampleRate: number): Float32Array {
  const out = new Float32Array(samples.length);
  const alpha = 1 - Math.exp((-2 * Math.PI * 150) / sampleRate);
  let y = 0;
  for (let i = 0; i < samples.length; i++) {
    y += alpha * (samples[i]! - y);
    out[i] = y * y;
  }
  return out;
}
