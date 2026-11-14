import { describe, expect, it, vi } from 'vitest';

vi.mock('shaka-player/dist/shaka-player.compiled', () => ({ default: {} }));

const { shouldSeekForDrift } = await import('./videoDeck');

describe('shouldSeekForDrift', () => {
  it('resynchronizes when drift exceeds 50ms in either direction', () => {
    expect(shouldSeekForDrift(0.051)).toBe(true);
    expect(shouldSeekForDrift(-0.051)).toBe(true);
    expect(shouldSeekForDrift(0.05)).toBe(false);
  });
});
