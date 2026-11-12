import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchPodcasts } from './api';

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
});
