import { describe, expect, it } from 'vitest';
import { metadataMatches } from './match';
import { mediaUrl, resolveFallback, resolveJioSaavn, resolveRadioJavan, seconds, type SourceCall } from './providers';

const want = { title: 'Song', artist: 'Artist', durationSec: 180 };
const both = { jiosaavn: true, radiojavan: true };

describe('metadataMatches (same rules as desktop)', () => {
  it('accepts close duration, rejects wrong song, artist, version or length', () => {
    expect(metadataMatches(want, { title: 'Song', artist: 'Artist', durationSec: 200 })).toBe(true);
    expect(metadataMatches(want, { title: 'Other song', artist: 'Artist', durationSec: 180 })).toBe(false);
    expect(metadataMatches(want, { title: 'Song', artist: 'Unrelated', durationSec: 180 })).toBe(false);
    expect(metadataMatches(want, { title: 'Song', artist: 'Artist', durationSec: 220 })).toBe(false);
    expect(metadataMatches(want, { title: 'Song (Remix)', artist: 'Artist', durationSec: 180 })).toBe(false);
    expect(metadataMatches(want, { title: 'Song (Album Version)', artist: 'Artist', durationSec: 180 })).toBe(true);
  });
});

describe('metadataMatches shared duration boundary', () => {
  it('preserves the same exact threshold as the catalog matcher', () => {
    const candidate = { title: 'Song', artist: 'Artist' };
    expect(metadataMatches(want, { ...candidate, durationSec: 210 })).toBe(true);
    expect(metadataMatches(want, { ...candidate, durationSec: 211 })).toBe(false);
    expect(metadataMatches(want, { ...candidate, durationSec: null })).toBe(true);
    expect(metadataMatches(want, { ...candidate, durationSec: 0 })).toBe(true);
  });
});

describe('mediaUrl / seconds', () => {
  it('allows only https mp3/m4a/mp4 without credentials', () => {
    expect(mediaUrl('https://m.example/a.mp3?x=1')).not.toBeNull();
    expect(mediaUrl('https://m.example/a.m4a')).not.toBeNull();
    expect(mediaUrl('http://m.example/a.mp3')).toBeNull();
    expect(mediaUrl('https://u@m.example/a.mp3')).toBeNull();
    expect(mediaUrl('https://m.example/live.m3u8')).toBeNull();
    expect(mediaUrl('https://m.example/stream')).toBeNull();
    expect(mediaUrl(null)).toBeNull();
    expect(seconds('3:21')).toBe(201);
    expect(seconds('200.5')).toBe(200);
    expect(seconds('x')).toBeNull();
  });
});

const jioCall = (auth: string): SourceCall => async ({ url }) => {
  const call = new URL(url).searchParams.get('__call');
  if (call === 'search.getResults') {
    return { results: [
      { title: 'Wrong', more_info: { duration: '180', encrypted_media_url: 'bad' } },
      { title: 'Song', more_info: { duration: '181', encrypted_media_url: 'enc', artistMap: { primary_artists: [{ name: 'Artist' }] } } },
    ] };
  }
  expect(new URL(url).searchParams.get('bitrate')).toBe('320');
  expect(new URL(url).searchParams.get('url')).toBe('enc');
  return { auth_url: auth };
};

describe('JioSaavn', () => {
  it('matches strictly, then takes the https auth_url', async () => {
    const r = await resolveJioSaavn(want, jioCall('https://cdn.example/s_320.mp4?t=1'));
    expect(r).toEqual({ provider: 'jiosaavn', url: 'https://cdn.example/s_320.mp4?t=1', durationSec: 181 });
  });
  it('rejects an http stream and a missing match', async () => {
    await expect(resolveJioSaavn(want, jioCall('http://cdn.example/s.mp4'))).rejects.toThrow();
    await expect(resolveJioSaavn({ ...want, title: 'Nope' }, jioCall('https://cdn.example/s.mp4'))).rejects.toThrow('no matching');
  });
});

const rjCall = (detail: unknown): SourceCall => async ({ url }) => {
  const u = new URL(url);
  if (u.pathname.endsWith('/search')) return { mp3s: [{ id: '9', song: 'Song', artist: 'Artist', duration: '3:00' }] };
  expect(u.searchParams.get('id')).toBe('9');
  return detail;
};

describe('Radio Javan', () => {
  it('prefers hq_link, falls back to lq_link, skips unusable links', async () => {
    expect((await resolveRadioJavan(want, rjCall({ hq_link: 'https://rj/a.mp3', lq_link: 'https://rj/b.mp3' }))).url).toBe('https://rj/a.mp3');
    expect((await resolveRadioJavan(want, rjCall({ hq_link: 'http://rj/a.mp3', lq_link: 'https://rj/b.mp3' }))).url).toBe('https://rj/b.mp3');
    await expect(resolveRadioJavan(want, rjCall({ hq_link: 'https://rj/live.m3u8' }))).rejects.toThrow();
  });
});

describe('resolveFallback', () => {
  const calls: string[] = [];
  const router: SourceCall = async (p) => {
    calls.push(p.provider);
    if (p.provider === 'jiosaavn') throw new Error('down');
    return rjCall({ hq_link: 'https://rj/a.mp3' })(p);
  };
  it('skips disabled sources, fails over from JioSaavn to Radio Javan, returns null when none work', async () => {
    expect(await resolveFallback(want, { jiosaavn: false, radiojavan: false }, router)).toBeNull();
    expect(calls).toEqual([]);
    expect((await resolveFallback(want, both, router))?.provider).toBe('radiojavan');
    expect(calls[0]).toBe('jiosaavn');
    expect(await resolveFallback(want, both, async () => { throw new Error('x'); })).toBeNull();
  });
});
