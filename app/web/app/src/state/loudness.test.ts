import { describe, expect, it } from 'vitest';
import { loudnessGain } from './playerSettings';

describe('loudnessGain', () => {
  it('is off without a target or without track loudness', () => {
    expect(loudnessGain(5, null)).toBe(1);
    expect(loudnessGain(null, -14)).toBe(1);
  });
  it('turns a loud track down to the standard -14 LUFS', () => {
    expect(loudnessGain(6, -14)).toBeCloseTo(10 ** (-6 / 20));
  });
  it('lowers everything for a quieter target', () => {
    expect(loudnessGain(0, -20)).toBeCloseTo(10 ** (-6 / 20));
  });
  it('caps boosts at +3 dB and cuts at -15 dB', () => {
    expect(loudnessGain(-10, -10)).toBeCloseTo(10 ** (3 / 20));
    expect(loudnessGain(30, -14)).toBeCloseTo(10 ** (-15 / 20));
  });
});
