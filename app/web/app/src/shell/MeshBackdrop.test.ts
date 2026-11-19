import { describe, expect, it } from 'vitest';
import { meshFadeProgress } from './MeshBackdrop';

describe('MeshBackdrop artwork transition', () => {
  it('shows the latest artwork palette immediately with Reduced Motion', () => {
    // Reduced Motion may be enabled halfway through an already-started crossfade.
    expect(meshFadeProgress(300, 0, false)).toBeCloseTo(0.25);
    expect(meshFadeProgress(300, 0, true)).toBe(1);
    expect(meshFadeProgress(1200, 0, false)).toBe(1);
    expect(meshFadeProgress(0, -Infinity, true)).toBe(1);
  });
});
