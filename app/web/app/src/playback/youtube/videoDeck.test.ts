import { describe, expect, it, vi } from 'vitest';

vi.mock('shaka-player/dist/shaka-player.compiled', () => ({ default: {} }));

const { shouldSeekForDrift, videoTargetTime } = await import('./videoDeck');

describe('shouldSeekForDrift', () => {
  it('resynchronizes when drift exceeds 50ms in either direction', () => {
    expect(shouldSeekForDrift(0.051)).toBe(true);
    expect(shouldSeekForDrift(-0.051)).toBe(true);
    expect(shouldSeekForDrift(0.05)).toBe(false);
  });
});

describe('videoTargetTime', () => {
  it('applies the measured audio-to-video offset', () => {
    expect(videoTargetTime(10, 650)).toBeCloseTo(10.65);
    expect(videoTargetTime(10, -450)).toBeCloseTo(9.55);
    expect(videoTargetTime(10, 0)).toBe(10);
  });
});
