// SPDX-License-Identifier: AGPL-3.0-only
import type { TrackAnalysis } from '../analysis/types';
import type { Deck } from './deck';
import { gridOf, phraseAtOrBefore, phraseAtOrAfter, downbeatAtOrAfter, BEATS_PER_BAR } from './grid';

export interface TransitionRequest {
  from: Deck;
  to: Deck;
  fromAnalysis: TrackAnalysis;
  toAnalysis: TrackAnalysis;
  bars: number;
  forceNow?: boolean;
}

export interface TransitionPlan {
  t0: number;
  durationSec: number;
  mixInTrackTime: number;
  mixOutTrackTime: number;
  rate: number;
  fallback: boolean;
  style: 'blend' | 'filter' | 'crossfade' | 'loop';
  reason: string | null;
}

export const MIN_GRID_CONFIDENCE = 0.2;
const KILL_DB = -12;
const SHORT_BLEND_KILL_DB = -6;
const FULL_SWAP_MIN_BARS = 8;

export function effectiveBlendBars(
  requestedBars: number,
  fromConfidence: number,
  toConfidence: number,
): number {
  const conf = Math.min(fromConfidence, toConfidence);
  if (conf >= 0.6) return requestedBars;
  if (conf >= 0.45) return Math.min(requestedBars, 8);
  return Math.min(requestedBars, 4);
}

export function scheduleTransition(req: TransitionRequest): TransitionPlan {
  const { from, to, fromAnalysis, toAnalysis, bars, forceNow } = req;
  const ctx = from.ctx;
  const now = ctx.currentTime;
  const earliest = now + Math.max(from.scheduleAhead, to.scheduleAhead) + 0.1;

  const fromGrid = gridOf(fromAnalysis);
  const toGrid = gridOf(toAnalysis);
  const gridsUsable =
    fromGrid !== null &&
    toGrid !== null &&
    fromAnalysis.gridConfidence >= MIN_GRID_CONFIDENCE &&
    toAnalysis.gridConfidence >= MIN_GRID_CONFIDENCE;

  if (fromGrid === null || fromAnalysis.gridConfidence < MIN_GRID_CONFIDENCE) {
    return scheduleFallback(req, earliest, 'this song has no beat grid at its end');
  }
  if (!gridsUsable) return scheduleFilterMix(req, earliest, fromGrid, null, 'next song has no beat grid');

  const outRate = from.timeline.rateAt(now) || 1;
  const effectiveFromBpm = fromGrid.bpm * outRate;
  const rawRate = effectiveFromBpm / toGrid.bpm;
  const rate = [rawRate, rawRate / 2, rawRate * 2].reduce((best, r) =>
    Math.abs(r - 1) < Math.abs(best - 1) ? r : best,
  );
  if (!stretchAllowed(rate)) {
    return scheduleFilterMix(req, earliest, fromGrid, toGrid, `tempos ${Math.round(Math.abs(rate - 1) * 100)}% apart`);
  }
  const effBars = effectiveBlendBars(bars, fromAnalysis.gridConfidence, toAnalysis.gridConfidence);
  const durationSec = effBars * BEATS_PER_BAR * (60 / effectiveFromBpm);

  let mixOutTrackTime: number;
  const fromPos = from.expectedTrackTime ?? 0;
  if (forceNow) {
    const earliestTrack = from.timeline.trackTimeAt(earliest) ?? fromPos;
    mixOutTrackTime = downbeatAtOrAfter(fromGrid, earliestTrack);
  } else {
    const outro = fromAnalysis.outroStartSec ?? fromAnalysis.durationSec;
    const blendInputSec = durationSec * outRate;
    mixOutTrackTime = phraseAtOrBefore(fromGrid, outro - blendInputSec);
    const latestStart = fromAnalysis.durationSec - blendInputSec - fromGrid.barSec;
    mixOutTrackTime = Math.min(mixOutTrackTime, phraseAtOrBefore(fromGrid, latestStart));
    if (mixOutTrackTime < fromPos) {
      mixOutTrackTime = phraseAtOrAfter(fromGrid, from.timeline.trackTimeAt(earliest) ?? fromPos);
    }
  }

  let t0 = from.timeline.ctxTimeAt(mixOutTrackTime, now);
  if (t0 === null || t0 < earliest) {
    mixOutTrackTime = downbeatAtOrAfter(fromGrid, from.timeline.trackTimeAt(earliest) ?? fromPos);
    t0 = from.timeline.ctxTimeAt(mixOutTrackTime, now);
    if (t0 === null) return scheduleFallback(req, earliest);
  }

  const introEnd = toAnalysis.introEndSec ?? 0;
  const fullEnergyPhrase = phraseAtOrAfter(toGrid, Math.max(introEnd, toGrid.downbeatSec));
  let mixInTrackTime = fullEnergyPhrase - durationSec * rate;
  if (mixInTrackTime < toGrid.downbeatSec) mixInTrackTime = toGrid.downbeatSec;

  to.playAt({ ctxTime: t0, trackTime: mixInTrackTime, rate });
  from.pauseAt(t0 + durationSec + 0.05);

  applyEqualPowerCross(from.gain.gain, to.gain.gain, t0, durationSec);

  const swapDb = effBars >= FULL_SWAP_MIN_BARS ? KILL_DB : SHORT_BLEND_KILL_DB;
  const mid = t0 + durationSec / 2;
  const barOutSec = fromGrid.barSec / outRate;
  to.eq.setGainAt('low', swapDb, t0 - 0.02);
  from.eq.rampGain('low', 0, swapDb, mid - barOutSec / 2, barOutSec);
  to.eq.rampGain('low', swapDb, 0, mid - barOutSec / 2, barOutSec);
  from.eq.rampGain('high', 0, -8, t0, durationSec);
  to.eq.setGainAt('high', -8, t0 - 0.02);
  to.eq.rampGain('high', -8, 0, t0, durationSec);

  const tEnd = t0 + durationSec;
  scheduleAfter(ctx, tEnd + 0.2, () => {
    from.eq.reset();
    from.setVolume(1);
  });
  glideRateToUnity(to, rate, tEnd);

  return { t0, durationSec, mixInTrackTime, mixOutTrackTime, rate, fallback: false, style: 'blend', reason: null };
}

export function plannedBars(
  requestedBars: number,
  fromAnalysis: TrackAnalysis,
  toAnalysis: TrackAnalysis | null,
  outRate: number,
): number {
  const fromGrid = gridOf(fromAnalysis);
  const toGrid = toAnalysis ? gridOf(toAnalysis) : null;
  if (!toAnalysis) return effectiveBlendBars(requestedBars, fromAnalysis.gridConfidence, 1);
  if (!fromGrid || !toGrid || toAnalysis.gridConfidence < MIN_GRID_CONFIDENCE) return Math.min(requestedBars, FILTER_MAX_BARS);
  const raw = (fromGrid.bpm * outRate) / toGrid.bpm;
  const rate = [raw, raw / 2, raw * 2].reduce((best, r) => (Math.abs(r - 1) < Math.abs(best - 1) ? r : best));
  if (!stretchAllowed(rate)) return Math.min(requestedBars, FILTER_MAX_BARS);
  return effectiveBlendBars(requestedBars, fromAnalysis.gridConfidence, toAnalysis.gridConfidence);
}

export function stretchAllowed(rate: number): boolean {
  return rate >= 1 ? rate - 1 <= MAX_SPEED_UP : 1 - rate <= MAX_SLOW_DOWN;
}
const MAX_SLOW_DOWN = 0.1;
const MAX_SPEED_UP = 0.25;
const FILTER_MAX_BARS = 8;

function scheduleFilterMix(
  req: TransitionRequest,
  earliest: number,
  fromGrid: NonNullable<ReturnType<typeof gridOf>>,
  toGrid: ReturnType<typeof gridOf>,
  reason: string,
): TransitionPlan {
  const { from, to, fromAnalysis, toAnalysis, forceNow } = req;
  const ctx = from.ctx;
  const now = ctx.currentTime;
  const outRate = from.timeline.rateAt(now) || 1;
  const barOutSec = fromGrid.barSec / outRate;
  let bars = Math.min(req.bars, FILTER_MAX_BARS);
  let durationSec = bars * barOutSec;
  let blendInputSec = durationSec * outRate;

  const fromPos = from.expectedTrackTime ?? 0;
  let mixOutTrackTime: number;
  if (forceNow) {
    mixOutTrackTime = downbeatAtOrAfter(fromGrid, from.timeline.trackTimeAt(earliest) ?? fromPos);
  } else {
    const outro = fromAnalysis.outroStartSec ?? fromAnalysis.durationSec;
    mixOutTrackTime = phraseAtOrBefore(fromGrid, outro - blendInputSec);
    mixOutTrackTime = Math.min(mixOutTrackTime, phraseAtOrBefore(fromGrid, fromAnalysis.durationSec - blendInputSec - fromGrid.barSec));
    if (mixOutTrackTime < fromPos) mixOutTrackTime = phraseAtOrAfter(fromGrid, from.timeline.trackTimeAt(earliest) ?? fromPos);
  }
  let t0 = from.timeline.ctxTimeAt(mixOutTrackTime, now);
  if (t0 === null || t0 < earliest) {
    mixOutTrackTime = downbeatAtOrAfter(fromGrid, from.timeline.trackTimeAt(earliest) ?? fromPos);
    t0 = from.timeline.ctxTimeAt(mixOutTrackTime, now);
    if (t0 === null) return scheduleFallback(req, earliest, reason);
  }

  const room = fromAnalysis.durationSec - 0.3 - mixOutTrackTime;
  if (blendInputSec > room) {
    bars = Math.max(1, Math.floor(room / fromGrid.barSec));
    durationSec = bars * barOutSec;
    blendInputSec = durationSec * outRate;
  }

  const mixInTrackTime = toGrid ? toGrid.downbeatSec : Math.min(toAnalysis.introEndSec ?? 0, 1);
  to.playAt({ ctxTime: t0, trackTime: mixInTrackTime, rate: 1 });
  from.pauseAt(t0 + durationSec + 0.05);
  applyEqualPowerCross(from.gain.gain, to.gain.gain, t0, durationSec);

  const hp = from.sweep.highpass.frequency;
  const lp = to.sweep.lowpass.frequency;
  hp.cancelScheduledValues(t0 - 0.03);
  lp.cancelScheduledValues(t0 - 0.03);
  hp.setValueAtTime(FILTER_OPEN_LOW_HZ, t0);
  hp.exponentialRampToValueAtTime(FILTER_HP_END_HZ, t0 + durationSec);
  lp.setValueAtTime(FILTER_LP_START_HZ, t0 - 0.02);
  lp.exponentialRampToValueAtTime(FILTER_OPEN_HIGH_HZ, t0 + durationSec);

  const mid = t0 + durationSec / 2;
  to.eq.setGainAt('low', KILL_DB, t0 - 0.02);
  from.eq.rampGain('low', 0, KILL_DB, mid - barOutSec / 2, barOutSec);
  to.eq.rampGain('low', KILL_DB, 0, mid - barOutSec / 2, barOutSec);

  const tEnd = t0 + durationSec;
  scheduleAfter(ctx, tEnd + 0.2, () => {
    from.eq.reset();
    from.resetSweep();
    to.resetSweep();
    from.setVolume(1);
  });
  return { t0, durationSec, mixInTrackTime, mixOutTrackTime, rate: 1, fallback: false, style: 'filter', reason };
}

const FILTER_OPEN_LOW_HZ = 20;
const FILTER_OPEN_HIGH_HZ = 20000;
const FILTER_HP_END_HZ = 1800;
const FILTER_LP_START_HZ = 350;

function scheduleFallback(req: TransitionRequest, earliest: number, reason: string | null = null): TransitionPlan {
  const { from, to, toAnalysis } = req;
  const ctx = from.ctx;
  const durationSec = 12;
  const t0 = earliest;
  const mixInTrackTime = toAnalysis.introEndSec ?? 0;
  to.playAt({ ctxTime: t0, trackTime: mixInTrackTime, rate: 1 });
  from.pauseAt(t0 + durationSec + 0.05);
  applyEqualPowerCross(from.gain.gain, to.gain.gain, t0, durationSec);
  scheduleAfter(ctx, t0 + durationSec + 0.2, () => {
    from.eq.reset();
    from.setVolume(1);
  });
  return {
    t0,
    durationSec,
    mixInTrackTime,
    mixOutTrackTime: from.expectedTrackTime ?? 0,
    rate: 1,
    fallback: true,
    style: 'crossfade',
    reason,
  };
}

function applyEqualPowerCross(outParam: AudioParam, inParam: AudioParam, t0: number, durationSec: number): void {
  const N = 65;
  const fadeOut = new Float32Array(N);
  const fadeIn = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const x = i / (N - 1);
    fadeOut[i] = Math.cos((x * Math.PI) / 2);
    fadeIn[i] = Math.sin((x * Math.PI) / 2);
  }
  outParam.cancelScheduledValues(t0 - 0.03);
  inParam.cancelScheduledValues(t0 - 0.03);
  outParam.setValueAtTime(1, t0 - 0.02);
  inParam.setValueAtTime(0, t0 - 0.02);
  outParam.setValueCurveAtTime(fadeOut, t0, durationSec);
  inParam.setValueCurveAtTime(fadeIn, t0, durationSec);
}

function glideRateToUnity(deck: Deck, fromRate: number, startCtxTime: number): void {
  if (Math.abs(fromRate - 1) < 0.002) {
    deck.setRate(1, startCtxTime + 0.5);
    return;
  }
  const steps = 8;
  const stepSec = 3;
  for (let i = 1; i <= steps; i++) {
    const r = fromRate + (1 - fromRate) * (i / steps);
    deck.setRate(r, startCtxTime + i * stepSec);
  }
}

function scheduleAfter(ctx: AudioContext, ctxTime: number, fn: () => void): void {
  setTimeout(fn, Math.max(0, (ctxTime - ctx.currentTime) * 1000));
}
