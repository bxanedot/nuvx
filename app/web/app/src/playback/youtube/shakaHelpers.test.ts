import { describe, expect, it, vi } from 'vitest';

vi.mock('shaka-player/dist/shaka-player.compiled', () => ({ default: {} }));

const { isGoogleVideoURL } = await import('./shakaHelpers');

describe('isGoogleVideoURL', () => {
  it('accepts SABR and videoplayback URLs', () => {
    expect(isGoogleVideoURL('sabr://x')).toBe(true);
    expect(isGoogleVideoURL('https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&id=x')).toBe(true);
    expect(isGoogleVideoURL('https://rr1---sn-abc.googlevideo.com/videoplayback?source=youtube')).toBe(true);
    expect(isGoogleVideoURL('https://rr1---sn-abc.googlevideo.com/videoplayback/id/x/expire/1/sq/2')).toBe(true);
  });

  it('rejects everything else', () => {
    expect(isGoogleVideoURL('https://rr1---sn-abc.googlevideo.com/videoplayback?id=x')).toBe(false);
    expect(isGoogleVideoURL('https://i.ytimg.com/vi/x/hq720.jpg')).toBe(false);
    expect(isGoogleVideoURL('https://music.youtube.com/watch?v=x')).toBe(false);
  });
});
