import { afterEach, describe, expect, it, vi } from 'vitest';
import { scheduleTransition } from './transition-scheduler';
import type { TrackAnalysis } from '../analysis/types';
import type { Deck } from './deck';

function analysis(overrides: Partial<TrackAnalysis> = {}): TrackAnalysis {
  return {
    fileKey: 'track', path: '', tags: { title: 'Track', artist: 'Artist' }, durationSec: 180,
    bpm: null, beatIntervalSec: null, firstDownbeatSec: null, gridConfidence: 0,
    camelot: null, keyName: null, keyConfidence: 0, energy: 0, energyCurve: [],
    introEndSec: 0, outroStartSec: null, analysisVersion: 8, ...overrides,
  };
}

function deck() {
  const param = {
    cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), setValueCurveAtTime: vi.fn(),
  };
  const pauseAt = vi.fn();
  const playAt = vi.fn();
  const value = {
    ctx: { currentTime: 30 }, scheduleAhead: 0.05, expectedTrackTime: 0,
    timeline: { rateAt: () => 1, trackTimeAt: () => 0, ctxTimeAt: () => null },
    gain: { gain: param }, eq: { reset: vi.fn() }, setVolume: vi.fn(),
    pauseAt, playAt,
  };
  return { value: value as unknown as Deck, param, pauseAt, playAt };
}

describe('transition fallback', () => {
  afterEach(() => { vi.useRealTimers(); vi.clearAllTimers(); });

  it('uses a normal crossfade when analysis is missing or its grid is untrusted', () => {
    vi.useFakeTimers();
    const from = deck();
    const to = deck();
    const plan = scheduleTransition({
      from: from.value,
      to: to.value,
      fromAnalysis: analysis(),
      toAnalysis: analysis({ gridConfidence: 0.1 }),
      bars: 16,
    });

    expect(plan).toMatchObject({ fallback: true, style: 'crossfade', durationSec: 12 });
    expect(to.playAt).toHaveBeenCalledOnce();
    expect(to.playAt.mock.calls[0]?.[0].ctxTime).toBeCloseTo(30.15);
    expect(to.playAt.mock.calls[0]?.[0].trackTime).toBe(0);
    expect(from.pauseAt.mock.calls[0]?.[0]).toBeCloseTo(42.2);
    expect(from.param.setValueCurveAtTime).toHaveBeenCalledOnce();
  });
});
