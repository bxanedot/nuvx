// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/storage', () => ({
  readStorage: (_storage: string, key: string) => window.localStorage.getItem(key),
  writeStorage: (_storage: string, key: string, value: string) => window.localStorage.setItem(key, value),
}));

import { addToNuvxPlaylist, createNuvxPlaylist, readNuvxPlaylistArtwork, readNuvxPlaylists, setNuvxPlaylistArtwork } from './localPlaylists';

describe('Nuvx playlist artwork', () => {
  beforeEach(() => window.localStorage.clear());

  it('keeps the uploaded cover when a track is added and reads legacy playlists', () => {
    const legacy = { id: 'nuvx-local:legacy', title: 'Older', tracks: [] };
    window.localStorage.setItem('nuvx-local-playlists', JSON.stringify([legacy]));
    expect(readNuvxPlaylists()).toEqual([legacy]);

    const playlist = createNuvxPlaylist('New');
    expect(setNuvxPlaylistArtwork(playlist.id, 'data:image/jpeg;base64,cover')).toBe(true);
    expect(addToNuvxPlaylist(playlist.id, {
      videoId: 'track', title: 'Track', artists: [], album: null, durationSec: null,
      explicit: false, thumbnail: null, index: null, plays: null,
    })).toBe(true);

    expect(readNuvxPlaylists().find((item) => item.id === playlist.id)?.artwork)
      .toBe('data:image/jpeg;base64,cover');
    expect(readNuvxPlaylistArtwork(playlist.id)).toBe('data:image/jpeg;base64,cover');
    expect(window.localStorage.getItem('nuvx-playlist-artwork')).toBeNull();
  });

  it('keeps remote playlist artwork local to the browser', () => {
    expect(setNuvxPlaylistArtwork('VLPLremote', 'data:image/jpeg;base64,cover')).toBe(true);
    expect(readNuvxPlaylistArtwork('PLremote')).toBe('data:image/jpeg;base64,cover');
    expect(readNuvxPlaylists()).toEqual([]);
  });
});
