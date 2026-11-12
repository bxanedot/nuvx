import { useQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import type { SpotifyTrack } from '@nuvx/shared/bridge';
import type { Track } from '@/data/music/types';
import { useSpotifyAuth, useSpotifySignedIn } from '@/extension/useSpotifyAuth';
import { fetchSpotifyPlaylistInfo, fetchSpotifyPlaylists, fetchSpotifyTracks, matchToYouTube, pendingTrack } from './api';

export function useSpotifyPlaylists() {
  const signedIn = useSpotifySignedIn();
  return useQuery({
    queryKey: ['spotify', 'data', 'playlists'],
    queryFn: fetchSpotifyPlaylists,
    enabled: signedIn,
    staleTime: 5 * 60_000,
  });
}

export function useSpotifyPlaylistInfo(playlistId: string) {
  const signedIn = useSpotifySignedIn();
  return useQuery({
    queryKey: ['spotify', 'data', 'playlist', playlistId],
    queryFn: () => fetchSpotifyPlaylistInfo(playlistId),
    enabled: signedIn && !!playlistId,
    staleTime: 5 * 60_000,
  });
}

const MATCH_PARALLELISM = 6;
const PLAYLIST_FRESH_MS = 5 * 60_000;

export interface SpotifyRows {
  status: 'loading' | 'ready' | 'error';
  error: unknown;
  rows: Track[];
  total: number;
  matching: number;
}

class PlaylistJob {
  state: SpotifyRows = { status: 'loading', error: null, rows: [], total: 0, matching: 0 };
  private source: SpotifyTrack[] = [];
  private readonly listeners = new Set<() => void>();
  private fetching = false;
  private completedAt = 0;
  private readonly inFlight = new Set<number>();

  constructor(private readonly playlistId: string) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    this.start();
    return () => void this.listeners.delete(listener);
  };

  private emit(change: Partial<SpotifyRows>) {
    const rows = change.rows ?? this.state.rows;
    this.state = { ...this.state, ...change, matching: rows.filter((r) => r.unavailable === 'matching').length };
    this.listeners.forEach((l) => l());
  }

  retry() {
    if (this.state.status !== 'error') return;
    this.emit({ status: 'loading', error: null });
    this.start();
  }

  private start() {
    if (this.fetching) return;
    if (this.state.status === 'ready' && Date.now() - this.completedAt < PLAYLIST_FRESH_MS) return;
    if (this.state.status === 'ready') this.emit({ status: 'loading', error: null });
    if (this.state.status === 'loading' || this.state.status === 'error') {
      this.fetching = true;
      const oldRows = new Map(this.source.map((track, i) => [track.id, this.state.rows[i]]));
      fetchSpotifyTracks(this.playlistId, (soFar, total) => {
        this.source = soFar;
        const rows = soFar.map((track) => oldRows.get(track.id) ?? pendingTrack(track));
        this.emit({ rows, total });
        this.pump();
      })
        .then(() => {
          this.completedAt = Date.now();
          this.emit({ status: 'ready', error: null });
        })
        .catch((error: unknown) => this.emit({ status: 'error', error }))
        .finally(() => (this.fetching = false));
      return;
    }
    this.pump();
  }

  private pump() {
    while (this.inFlight.size < MATCH_PARALLELISM && this.listeners.size > 0) {
      const index = this.state.rows.findIndex((r, i) => r.unavailable === 'matching' && !this.inFlight.has(i));
      if (index < 0) return;
      this.inFlight.add(index);
      const track = this.source[index]!;
      void matchToYouTube(track)
        .catch(() => null)
        .then((found) => {
          if (this.source[index]?.id !== track.id) return;
          const rows = [...this.state.rows];
          rows[index] = found ?? pendingTrack(track, 'missing');
          this.emit({ rows });
        })
        .finally(() => {
          this.inFlight.delete(index);
          this.pump();
        });
    }
  }
}

const jobs = new Map<string, PlaylistJob>();

export function useSpotifyRows(playlistId: string): SpotifyRows & { retry: () => void } {
  const auth = useSpotifyAuth();
  const account = auth.state === 'ready' && auth.auth.signedIn ? (auth.auth.user?.name ?? '?') : null;
  const key = `${account}|${playlistId}`;
  let job = jobs.get(key);
  if (!job) {
    job = new PlaylistJob(playlistId);
    jobs.set(key, job);
  }
  const current = job;
  const state = useSyncExternalStore(account ? current.subscribe : noSubscribe, () => current.state);
  return { ...state, retry: () => current.retry() };
}

const noSubscribe = () => () => undefined;
