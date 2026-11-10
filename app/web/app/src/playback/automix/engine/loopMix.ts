import type { BeatSide, TrackAnalysis, VocalWindow } from '../analysis/types';
import type { LoopChange, LoopDeck } from './loopDeck';

export const LOOP_BARS = 4;
const LOOP_LENGTHS = [4, 2, 1] as const;
const ENTER_BARS = 4;
const EXIT_BARS = 2;
const SETTLE_BARS = 2;
const MAX_BPM_PER_BAR = 2.5;
const MIN_RAMP_BARS = 2;
const MAX_RAMP_BARS = 24;
const VOCAL_ACTIVE = 0.25;
const MAX_SUNG_SHARE = 0.03;
const MAX_SUNG_RUN_SEC = 0.2;
const LYRIC_GUARD_SEC = 0.3;
const LYRIC_LEAD_SEC = 0.15;
const LYRIC_TAIL_SEC = 0.35;
const MIN_LOUDNESS = 0.6;
export const OUT_SEARCH_SEC = 60;
export const IN_SEARCH_SEC = 45;
export const OUT_REGION_SEC = OUT_SEARCH_SEC + 5;
export const IN_REGION_SEC = IN_SEARCH_SEC + 40;
const HANDOVER_SEC = 0.025;
const BASS_CUT_DB = -18;

export type SungSpans = [number, number][];

export interface LoopPick {
  key: string;
  loop: [number, number];
  bars: number;
  beatSec: number;
  bpm: number;
  vocal: number | null;
  checkedBy: 'lyrics' | 'mask';
}

export interface LoopMixPlan {
  out: LoopPick;
  in: LoopPick;
  fold: number;
  enterBars: number;
  rampBars: number;
  exitBars: number;
  totalBars: number;
}

export interface LoopMixTimes {
  t0: number;
  beatTimes: number[];
  handoffAt: number;
  releaseAt: number;
  elementAt: number;
  elementTrackTime: number;
}

export function planLoopMix(
  out: TrackAnalysis,
  outBuffer: AudioBuffer,
  inn: TrackAnalysis,
  inBuffer: AudioBuffer,
  earliestOutSec: number,
  sung: { out?: SungSpans | null; in?: SungSpans | null } = {},
): LoopMixPlan | null {
  if (!out.beatIntervalSec || out.firstDownbeatSec === null || !out.bpm) return null;
  if (!inn.beatIntervalSec || inn.firstDownbeatSec === null || !inn.bpm) return null;
  if (out.musicVideo || inn.musicVideo) return null;
  const outBar = out.beatIntervalSec * 4;
  const inBar = inn.beatIntervalSec * 4;

  const outEnd = Math.min(out.durationSec - 0.5, outBuffer.duration - 0.1);
  const outFirst = Math.max(earliestOutSec, out.durationSec - OUT_SEARCH_SEC);
  const outPick = firstClean(
    (bars) => loopsBetween(out.tailBeats, out.firstDownbeatSec!, outBar, outFirst, outEnd, bars).reverse(),
    outBuffer,
    out.vocals ?? [],
    sung.out ?? null,
    out.beatIntervalSec,
  );
  if (outPick === null) return null;

  const inEnd = Math.min(IN_SEARCH_SEC + LOOP_BARS * inBar, inBuffer.duration - 20);
  const inPick = firstClean(
    (bars) => loopsBetween(inn.headBeats, inn.firstDownbeatSec!, inBar, 0, inEnd, bars),
    inBuffer,
    inn.vocals ?? [],
    sung.in ?? null,
    inn.beatIntervalSec,
  );
  if (inPick === null) return null;
  const outBeat = (outPick.end - outPick.start) / (outPick.bars * 4);
  const inBeat = (inPick.end - inPick.start) / (inPick.bars * 4);
  const outBpm = 60 / outBeat;
  const inBpm = 60 / inBeat;

  const fold = [1, 2, 0.5].reduce((best, f) =>
    Math.abs(Math.log(inBpm / f / outBpm)) < Math.abs(Math.log(inBpm / best / outBpm)) ? f : best,
  );
  const target = inBpm / fold;
  const rampBars = Math.min(MAX_RAMP_BARS, Math.max(MIN_RAMP_BARS, Math.ceil(Math.abs(target - outBpm) / MAX_BPM_PER_BAR)));
  const inLoopOutBars = inPick.bars / fold;
  const totalBars = Math.ceil((ENTER_BARS + rampBars + EXIT_BARS) / inLoopOutBars) * inLoopOutBars;

  const pick = (key: string, p: Clean, beatSec: number, bpm: number): LoopPick => ({
    key,
    loop: [p.start, p.end],
    bars: p.bars,
    beatSec,
    bpm,
    vocal: p.vocal,
    checkedBy: p.checkedBy,
  });
  return {
    out: pick(out.fileKey, outPick, outBeat, outBpm),
    in: pick(inn.fileKey, inPick, inBeat, inBpm),
    fold,
    enterBars: ENTER_BARS,
    rampBars,
    exitBars: totalBars - ENTER_BARS - rampBars,
    totalBars,
  };
}

type Span = { start: number; end: number };
type Clean = Span & { bars: number; vocal: number | null; checkedBy: 'lyrics' | 'mask' };

function loopsBetween(side: BeatSide | null | undefined, anchor: number, bar: number, from: number, to: number, bars: number): Span[] {
  const loops: Span[] = [];
  if (side && side.downbeats.length >= bars + 1) {
    const beat = 60 / side.bpm;
    const downs = side.downbeats;
    for (const start of downs) {
      if (start < from) continue;
      const target = start + bars * 4 * beat;
      const end = downs.reduce((best, d) => (Math.abs(d - target) < Math.abs(best - target) ? d : best), downs[0]!);
      if (Math.abs(end - target) > beat / 4 || end > to) continue;
      loops.push({ start, end });
    }
    if (loops.length) return loops;
  }
  if (to < from) return loops;
  const len = bars * bar;
  for (let t = anchor + Math.ceil((from - anchor) / bar) * bar; t + len <= to; t += bar) loops.push({ start: t, end: t + len });
  return loops;
}

function firstClean(
  candidates: (bars: number) => Span[],
  buffer: AudioBuffer,
  vocals: VocalWindow[],
  sung: SungSpans | null,
  beatSec: number,
): Clean | null {
  for (const bars of LOOP_LENGTHS) {
    const loops = candidates(bars);
    if (!loops.length) continue;
    const barLoud = (l: Span) =>
      Math.min(...Array.from({ length: bars }, (_, b) => rms(buffer, l.start + (b * (l.end - l.start)) / bars, l.start + ((b + 1) * (l.end - l.start)) / bars)));
    const louds = loops.map(barLoud);
    const typical = [...louds].sort((a, b) => a - b)[Math.floor(louds.length * 0.6)] ?? 0;
    for (let i = 0; i < loops.length; i++) {
      const l = loops[i]!;
      if (louds[i]! < typical * MIN_LOUDNESS || louds[i]! <= 0.01) continue;
      if (sung) {
        const from = l.start - LYRIC_GUARD_SEC;
        if (sung.some(([a, b]) => a < l.end && b > from)) continue;
        return { ...l, bars, vocal: 0, checkedBy: 'lyrics' };
      }
      const m = maskStats(vocals, l.start - beatSec, l.end);
      if (!m || m.share > MAX_SUNG_SHARE || m.longestRunSec > MAX_SUNG_RUN_SEC) continue;
      return { ...l, bars, vocal: m.share, checkedBy: 'mask' };
    }
  }
  return null;
}

export function maskStats(windows: VocalWindow[], from: number, to: number): { share: number; longestRunSec: number } | null {
  let sung = 0;
  let n = 0;
  let covered = 0;
  let run = 0;
  let longest = 0;
  let fps = 1;
  for (const w of windows) {
    const a = Math.max(0, Math.ceil((from - w.startSec) * w.fps));
    const b = Math.min(w.curve.length, Math.floor((to - w.startSec) * w.fps));
    for (let i = a; i < b; i++) {
      n++;
      if (w.curve[i]! > VOCAL_ACTIVE) {
        sung++;
        run++;
        longest = Math.max(longest, run);
      } else run = 0;
    }
    if (b > a) {
      covered += (b - a) / w.fps;
      fps = w.fps;
    }
  }
  return n && covered >= (to - from) * 0.8 ? { share: sung / n, longestRunSec: longest / fps } : null;
}

export function sungShare(windows: VocalWindow[], from: number, to: number): number | null {
  return maskStats(windows, from, to)?.share ?? null;
}

export function sungSpansFromLines(lines: { timeMs: number; endMs: number | null; blank: boolean }[]): SungSpans {
  const spans: SungSpans = [];
  const sorted = [...lines].sort((a, b) => a.timeMs - b.timeMs);
  sorted.forEach((l, i) => {
    if (l.blank) return;
    const start = l.timeMs / 1000;
    const next = sorted.slice(i + 1).find((n) => n.timeMs > l.timeMs)?.timeMs;
    const end = l.endMs !== null && l.endMs > l.timeMs ? l.endMs / 1000 : Math.min(start + 10, (next ?? l.timeMs + 10_000) / 1000);
    spans.push([start - LYRIC_LEAD_SEC, end + LYRIC_TAIL_SEC]);
  });
  return spans;
}

function rms(buffer: AudioBuffer, from: number, to: number): number {
  const data = buffer.getChannelData(0);
  const a = Math.max(0, Math.floor(from * buffer.sampleRate));
  const b = Math.min(data.length, Math.floor(to * buffer.sampleRate));
  let ss = 0;
  let n = 0;
  for (let i = a; i < b; i += 16) {
    ss += data[i]! * data[i]!;
    n++;
  }
  return n ? Math.sqrt(ss / n) : 0;
}

export function scheduleLoopMix(plan: LoopMixPlan, outDeck: LoopDeck, inDeck: LoopDeck, t0: number): LoopMixTimes {
  const { out, in: inn, fold } = plan;
  const beats = plan.totalBars * 4;
  const settleBeats = Math.round(SETTLE_BARS * 4 / fold);
  const rampFrom = plan.enterBars * 4;
  const rampTo = (plan.enterBars + plan.rampBars) * 4;
  const target = inn.bpm / fold;

  const tempo = (k: number) =>
    k < rampFrom ? out.bpm : k >= rampTo ? target : out.bpm + ((target - out.bpm) * (k - rampFrom + 0.5)) / (rampTo - rampFrom);
  const beatTimes = [t0];
  for (let k = 0; k < beats + settleBeats; k++) beatTimes.push(beatTimes[k]! + 60 / tempo(k));

  const outLoopBeats = out.bars * 4;
  const inLoopBeats = inn.bars * 4;
  const inPerBeat = fold * inn.beatSec;
  const outChanges: LoopChange[] = [];
  const inChanges: LoopChange[] = [];
  for (let k = 0; k < beats; k++) {
    const t = beatTimes[k]!;
    const T = tempo(k);
    outChanges.push({
      output: t,
      input: out.loop[0] + (k % outLoopBeats) * out.beatSec,
      rate: T * out.beatSec / 60,
      active: true,
      loop: out.loop,
    });
    inChanges.push({
      output: t,
      input: inn.loop[0] + ((k * fold) % inLoopBeats) * inn.beatSec,
      rate: (T * inPerBeat) / 60,
      active: true,
      loop: inn.loop,
    });
  }
  const releaseAt = beatTimes[beats]!;
  outChanges.push({ output: releaseAt + 0.05, input: out.loop[0], rate: 1, active: false, loop: null });
  inChanges.push({ output: releaseAt, input: inn.loop[1], rate: 1, active: true, loop: null });
  const elementAt = beatTimes[beats + settleBeats]!;
  const elementTrackTime = inn.loop[1] + (elementAt - releaseAt);
  inChanges.push({ output: elementAt + 0.1, input: elementTrackTime + 0.1, rate: 1, active: false, loop: null });
  outDeck.schedule(outChanges);
  inDeck.schedule(inChanges);

  const og = outDeck.gain.gain;
  const ig = inDeck.gain.gain;
  og.cancelScheduledValues(0);
  ig.cancelScheduledValues(0);
  og.setValueAtTime(0, t0 - HANDOVER_SEC);
  og.linearRampToValueAtTime(1, t0);
  const enterEnd = beatTimes[rampFrom]!;
  const rampEnd = beatTimes[rampTo]!;
  ig.setValueAtTime(0, t0);
  ig.linearRampToValueAtTime(0.55, enterEnd);
  og.setValueAtTime(1, enterEnd);
  og.linearRampToValueAtTime(0.6, rampEnd);
  ig.linearRampToValueAtTime(1, rampEnd);
  og.linearRampToValueAtTime(0, releaseAt);
  ig.setValueAtTime(1, elementAt);
  ig.linearRampToValueAtTime(0, elementAt + HANDOVER_SEC);

  const mid = beatTimes[Math.round((rampFrom + rampTo) / 2)]!;
  const bar = 4 * (60 / tempo(Math.round((rampFrom + rampTo) / 2)));
  inDeck.eq.setGainAt('low', BASS_CUT_DB, t0);
  outDeck.eq.rampGain('low', 0, BASS_CUT_DB, mid - bar / 2, bar);
  inDeck.eq.rampGain('low', BASS_CUT_DB, 0, mid - bar / 2, bar);
  const hp = outDeck.sweep.highpass.frequency;
  hp.setValueAtTime(20, rampEnd);
  hp.exponentialRampToValueAtTime(1500, releaseAt);

  return { t0, beatTimes, handoffAt: mid, releaseAt, elementAt, elementTrackTime };
}

export function deckTrackTime(deck: LoopDeck): number {
  return deck.inputTime;
}

export function mixPositionAt(plan: LoopMixPlan, times: LoopMixTimes, side: 'out' | 'in', t: number): number | null {
  const bt = times.beatTimes;
  if (t < times.t0) return null;
  const beats = plan.totalBars * 4;
  if (side === 'in' && t >= times.releaseAt) return plan.in.loop[1] + (t - times.releaseAt);
  if (side === 'out' && t >= times.releaseAt) return null;
  let k = 0;
  while (k + 1 < bt.length && bt[k + 1]! <= t) k++;
  const frac = Math.min(1, (t - bt[k]!) / (bt[k + 1]! - bt[k]!));
  if (k >= beats) return null;
  if (side === 'out') return plan.out.loop[0] + ((k % (plan.out.bars * 4)) + frac) * plan.out.beatSec;
  return plan.in.loop[0] + (((k * plan.fold) % (plan.in.bars * 4)) + frac * plan.fold) * plan.in.beatSec;
}

export function mixBeatPhaseAt(times: LoopMixTimes, t: number): number | null {
  const bt = times.beatTimes;
  if (t < times.t0 || t >= bt[bt.length - 1]!) return null;
  let k = 0;
  while (k + 1 < bt.length && bt[k + 1]! <= t) k++;
  return (t - bt[k]!) / (bt[k + 1]! - bt[k]!);
}
