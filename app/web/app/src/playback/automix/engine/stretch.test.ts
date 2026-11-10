import { describe, expect, it } from 'vitest';
import { stretchAllowed } from './transition-scheduler';

describe('stretchAllowed', () => {
  it('slows the next song down by at most 10%', () => {
    expect(stretchAllowed(120 / 130)).toBe(true);
    expect(stretchAllowed(120 / 152)).toBe(false);
  });

  it('speeds the next song up by at most 25%', () => {
    expect(stretchAllowed(120 / 100)).toBe(true);
    expect(stretchAllowed(120 / 92)).toBe(false);
  });
});
