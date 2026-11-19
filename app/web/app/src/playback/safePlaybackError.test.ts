import { describe, expect, it } from 'vitest';
import { safePlaybackError } from './safePlaybackError';

describe('public playback errors', () => {
  it('explains how to retry autoplay blocked by the browser', () => {
    expect(safePlaybackError(new DOMException('Not allowed', 'NotAllowedError')))
      .toBe('Your browser blocked playback. Press Play to try again.');
  });

  it('does not expose stream URLs, tokens or arbitrary resolver messages', () => {
    const secret = 'https://audio.example/stream?token=private-token&sig=private-signature';
    const visible = safePlaybackError(new Error(`401 fetching ${secret}`));
    expect(visible).toBe('Playback failed. Press Play to try again, or skip this track.');
    expect(visible).not.toContain(secret);
    expect(visible).not.toContain('private-token');
    expect(safePlaybackError({ authorization: 'Bearer secret' })).toBe(visible);
  });
});
