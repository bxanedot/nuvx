import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchHome, fetchPlaylist, fetchPodcasts, fetchRadioCountries, fetchRadioStations } from './api';

const mocks = vi.hoisted(() => ({
  ensureYtAuth: vi.fn(),
  getInnertube: vi.fn(),
  getMusicClient: vi.fn(),
}));

vi.mock('@/extension/useYtAuth', () => ({ ensureYtAuth: mocks.ensureYtAuth }));
vi.mock('@/playback/youtube/innertube', () => ({
  getInnertube: mocks.getInnertube,
  getMusicClient: mocks.getMusicClient,
  getSignedInnertube: vi.fn(),
}));

describe('fetchPodcasts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.ensureYtAuth.mockResolvedValue({ signedIn: false });
  });

  it('uses the supported long video filter and maps public search results', async () => {
    const search = vi.fn().mockResolvedValue({
      videos: [{
        id: 'abcdefghijk',
        title: { toString: () => ' Episode title ' },
        author: { name: 'Creator', id: 'channel-id' },
        thumbnails: [{ url: 'small.jpg', width: 120 }, { url: 'large.jpg', width: 600 }],
        duration: { seconds: 3600 },
      }],
    });
    mocks.getInnertube.mockResolvedValue({ search });

    const shelves = await fetchPodcasts();

    expect(search).toHaveBeenCalledWith('podcast full episodes', { type: 'video', duration: 'over_twenty_mins' });
    expect(shelves[0]?.items[0]).toMatchObject({
      kind: 'video',
      id: 'abcdefghijk',
      title: 'Episode title',
      subtitle: 'Creator',
      thumbnail: 'large.jpg',
      durationSec: 3600,
    });
  });

  it('falls back to general YouTube podcasts when signed-in shelves are empty', async () => {
    mocks.ensureYtAuth.mockResolvedValue({ signedIn: true });
    const applyFilter = vi.fn().mockResolvedValue({ sections: [] });
    mocks.getMusicClient.mockResolvedValue({ music: {
      getHomeFeed: vi.fn().mockResolvedValue({
        header: { chips: [{ text: 'Podcasts' }] },
        applyFilter,
      }),
    } });
    const search = vi.fn().mockResolvedValue({ videos: [{ id: 'abcdefghijk', title: { toString: () => 'Episode' } }] });
    mocks.getInnertube.mockResolvedValue({ search });

    const shelves = await fetchPodcasts();

    expect(applyFilter).toHaveBeenCalledOnce();
    expect(search).toHaveBeenCalledWith('podcast full episodes', { type: 'video', duration: 'over_twenty_mins' });
    expect(shelves[0]?.title).toBe('Podcasts on YouTube');
  });

  it('uses public podcast results after sign-out instead of retaining signed-in shelves', async () => {
    mocks.ensureYtAuth.mockResolvedValue({ signedIn: true });
    const signedShelf = { type: 'MusicShelf', title: { text: 'Private shelf' }, contents: [
      { type: 'MusicResponsiveListItem', id: 'private001', title: 'Private episode', item_type: 'video' },
    ] };
    const applyFilter = vi.fn().mockResolvedValue({ sections: [signedShelf] });
    mocks.getMusicClient.mockResolvedValue({ music: { getHomeFeed: vi.fn().mockResolvedValue({
      header: { chips: [{ text: 'Podcasts' }] }, applyFilter,
    }) } });
    const search = vi.fn().mockResolvedValue({ videos: [{ id: 'public00001', title: { toString: () => 'Public episode' } }] });
    mocks.getInnertube.mockResolvedValue({ search });

    expect((await fetchPodcasts())[0]?.items[0]?.id).toBe('private001');
    mocks.ensureYtAuth.mockResolvedValue({ signedIn: false });
    expect((await fetchPodcasts())[0]?.items[0]?.id).toBe('public00001');
  });
});

describe('home pagination', () => {
  beforeEach(() => vi.clearAllMocks());

  const feed = (getContinuation: () => Promise<unknown>) => ({
    sections: [{ type: 'MusicShelf', title: { text: 'Home' }, contents: [
      { type: 'MusicResponsiveListItem', id: 'abcdefghijk', title: 'First item', item_type: 'song' },
    ] }],
    has_continuation: true,
    getContinuation,
  });

  it('turns an empty continuation response into an exhausted page without discarding initial items', async () => {
    mocks.getMusicClient.mockResolvedValue({ music: { getHomeFeed: vi.fn().mockResolvedValue(feed(async () => { throw new Error('continuation did not have any content'); })) } });
    const first = await fetchHome();

    expect(first.data[0]?.items[0]?.id).toBe('abcdefghijk');
    await expect(first.next?.()).resolves.toEqual({ data: [], next: null });
  });

  it('surfaces transient continuation failures while leaving the current page intact', async () => {
    mocks.getMusicClient.mockResolvedValue({ music: { getHomeFeed: vi.fn().mockResolvedValue(feed(async () => { throw new Error('network timeout'); })) } });
    const first = await fetchHome();

    await expect(first.next?.()).rejects.toThrow('network timeout');
    expect(first.data[0]?.items[0]?.id).toBe('abcdefghijk');
  });
});

describe('playlist pagination', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps the loaded playlist usable when continuation fails transiently', async () => {
    const getContinuation = vi.fn().mockRejectedValue(new Error('network timeout'));
    mocks.getMusicClient.mockResolvedValue({ music: { getPlaylist: vi.fn().mockResolvedValue({
      items: [{ type: 'MusicResponsiveListItem', id: 'abcdefghijk', title: 'First track', item_type: 'song' }],
      has_continuation: true,
      getContinuation,
    }) } });

    const { page, more } = await fetchPlaylist('VLplaylist');

    expect(page.tracks.map((track) => track.videoId)).toEqual(['abcdefghijk']);
    await expect(more?.()).rejects.toThrow('network timeout');
    expect(page.tracks.map((track) => track.videoId)).toEqual(['abcdefghijk']);
  });

  it('ends cleanly when the upstream continuation has no content', async () => {
    mocks.getMusicClient.mockResolvedValue({ music: { getPlaylist: vi.fn().mockResolvedValue({
      items: [{ type: 'MusicResponsiveListItem', id: 'abcdefghijk', title: 'First track', item_type: 'song' }],
      has_continuation: true,
      getContinuation: async () => { throw new Error('continuation did not have any content'); },
    }) } });
    const { more } = await fetchPlaylist('VLplaylist');

    await expect(more?.()).resolves.toEqual({ data: [], next: null });
  });
});

describe('Radio Browser failover', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('tries the next host after HTTP and malformed JSON responses', async () => {
    const requested: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      requested.push(String(input));
      if (requested.length === 1) return new Response(null, { status: 503 });
      if (requested.length === 2) return Response.json({ countries: [] });
      return Response.json([{ name: 'United States', iso_3166_1: 'US', stationcount: 2 }]);
    }));

    await expect(fetchRadioCountries()).resolves.toEqual([{ name: 'United States', iso_3166_1: 'US', stationcount: 2 }]);
    expect(requested).toHaveLength(3);
    expect(requested[0]).toContain('all.api.radio-browser.info');
    expect(requested[1]).toContain('de1.api.radio-browser.info');
    expect(requested[2]).toContain('fi1.api.radio-browser.info');
  });

  it('tries another host after malformed station data and preserves valid empty results', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(Response.json(null))
      .mockResolvedValueOnce(new Response('unavailable', { status: 502 }))
      .mockResolvedValueOnce(Response.json([]));
    vi.stubGlobal('fetch', fetcher);

    await expect(fetchRadioStations(null, 'jazz')).resolves.toEqual([]);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('rejects after all hosts fail', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 503 }));
    vi.stubGlobal('fetch', fetcher);

    await expect(fetchRadioCountries()).rejects.toThrow('Radio Browser answered 503');
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
});
