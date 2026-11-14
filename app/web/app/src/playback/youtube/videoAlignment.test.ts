import { describe, expect, it } from 'vitest';
import { alignAudio, analyseAudio, energyEnvelope } from './videoAlignment';

function signal(length: number, seed: number): Float32Array {
  let state = seed >>> 0;
  const values = new Float32Array(length);
  for (let index = 0; index < length; index++) {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    values[index] = (state >>> 0) / 0xffffffff;
  }
  return values;
}

describe('energyEnvelope', () => {
  it('creates a mono energy envelope from stereo pcm', () => {
    const envelope = energyEnvelope(new Float32Array(200).fill(0.5), 2, 1_000);
    expect(envelope.length).toBe(7);
    for (const value of envelope) expect(value).toBeCloseTo(0.5, 6);
  });

  it('rejects empty input', () => {
    expect(energyEnvelope(new Float32Array(), 2, 48_000).length).toBe(0);
  });
});

describe('alignAudio', () => {
  it('finds a later video start with a different duration', () => {
    const source = signal(700, 1);
    const target = new Float32Array(65 + source.length + 90);
    target.set(signal(65, 2), 0);
    target.set(source, 65);
    target.set(signal(90, 3), 65 + source.length);
    expect(alignAudio(source, target)).toBe(650);
  });

  it('finds an earlier video start', () => {
    const source = signal(765, 4);
    expect(alignAudio(source, source.slice(65))).toBe(-650);
  });

  it('finds a long video intro and uses the overlapping audio', () => {
    const source = signal(6_500, 10);
    const target = new Float32Array(4_500 + source.length + 200);
    target.set(signal(4_500, 11), 0);
    target.set(source, 4_500);
    target.set(signal(200, 12), 4_500 + source.length);
    expect(alignAudio(source, target)).toBe(45_000);
  });

  it('rejects repeated audio when multiple offsets fit equally', () => {
    const pattern = signal(200, 13);
    const source = new Float32Array(1_200);
    for (let repeat = 0; repeat < 6; repeat++) source.set(pattern, repeat * pattern.length);
    const target = new Float32Array(65 + pattern.length + source.length + 90);
    target.set(signal(65, 14), 0);
    target.set(pattern, 65);
    target.set(source, 65 + pattern.length);
    target.set(signal(90, 15), 65 + pattern.length + source.length);
    expect(alignAudio(source, target)).toBeNull();
  });

  it('rejects silence', () => {
    expect(alignAudio(new Float32Array(700), new Float32Array(800))).toBeNull();
  });

  it('rejects unrelated audio', () => {
    expect(alignAudio(signal(700, 8), signal(800, 9))).toBeNull();
  });
});

describe('analyseAudio', () => {
  it('keeps only the requested analysis window', () => {
    const samples = new Float32Array(48_000 * 2 * 2).fill(0.25);
    const envelope = analyseAudio(samples, 2, 48_000, 1);
    expect(envelope.length).toBeGreaterThan(90);
    expect(envelope.length).toBeLessThan(100);
  });
});
