import { useEffect, useState } from 'react';
import type { Track } from './types';
import { readStorage, writeStorage } from '@/lib/storage';

export interface NuvxPlaylist {
  id: string;
  title: string;
  tracks: Track[];
}

const PLAYLISTS_KEY = 'nuvx-local-playlists';
const MODE_KEY = 'nuvx-create-playlists-locally';
const changed = new EventTarget();

export function useNuvxPlaylists(): NuvxPlaylist[] {
  const [playlists, setPlaylists] = useState(readNuvxPlaylists);
  useEffect(() => {
    const refresh = () => setPlaylists(readNuvxPlaylists());
    changed.addEventListener('change', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      changed.removeEventListener('change', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);
  return playlists;
}

export function readNuvxPlaylists(): NuvxPlaylist[] {
  try {
    const value: unknown = JSON.parse(readStorage('localStorage', PLAYLISTS_KEY) ?? '[]');
    return Array.isArray(value) ? value.filter(isPlaylist) : [];
  } catch {
    return [];
  }
}

export function createPlaylistsLocally(): boolean {
  return readStorage('localStorage', MODE_KEY) === 'true';
}

export function setCreatePlaylistsLocally(value: boolean): void {
  writeStorage('localStorage', MODE_KEY, String(value));
  changed.dispatchEvent(new Event('change'));
}

export function createNuvxPlaylist(title: string, track?: Track): NuvxPlaylist {
  const playlist = { id: `nuvx-local:${crypto.randomUUID()}`, title: title.trim(), tracks: track ? [withoutStreamUrl(track)] : [] };
  save([...readNuvxPlaylists(), playlist]);
  return playlist;
}

export function addToNuvxPlaylist(id: string, track: Track): boolean {
  const playlists = readNuvxPlaylists();
  const index = playlists.findIndex((playlist) => playlist.id === id);
  if (index < 0) return false;
  const playlist = playlists[index];
  if (!playlist) return false;
  if (playlist.tracks.some((item) => item.videoId === track.videoId)) return true;
  playlists[index] = { ...playlist, tracks: [...playlist.tracks, withoutStreamUrl(track)] };
  save(playlists);
  return true;
}

function withoutStreamUrl(track: Track): Track {
  const { streamUrl: _expired, ...saved } = track;
  return saved;
}

function isPlaylist(value: unknown): value is NuvxPlaylist {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === 'string' && typeof item.title === 'string' &&
    Array.isArray(item.tracks) && item.tracks.every(isTrack);
}

function isTrack(value: unknown): value is Track {
  if (!value || typeof value !== 'object') return false;
  const track = value as Record<string, unknown>;
  return typeof track.videoId === 'string' && typeof track.title === 'string' &&
    Array.isArray(track.artists) && typeof track.explicit === 'boolean';
}

function save(playlists: NuvxPlaylist[]): void {
  writeStorage('localStorage', PLAYLISTS_KEY, JSON.stringify(playlists));
  changed.dispatchEvent(new Event('change'));
}
