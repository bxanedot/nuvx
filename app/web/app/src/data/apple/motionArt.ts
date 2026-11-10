import { useQuery } from '@tanstack/react-query';
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { metered, useReducedMotion } from '@/data/spotify/canvas';
import { lyricsGet, onGranted, urlWith } from '@/lyrics/http';
import { queryClient } from '@/lib/queryClient';
import { obj, parse, str, type Json } from './artistPage';
import { albumIdOf, isCompilation, matches, motionUrls, ranked, songTerm, type AppleMotion } from './motion';
import { appleWebToken } from './webToken';

export type { AppleMotion };

export const useAppleSettings = create<{
  artistArt: boolean;
  motion: boolean;
  setArtistArt: (on: boolean) => void;
  setMotion: (on: boolean) => void;
}>()(
  persist(
    (set) => ({
      artistArt: true,
      motion: true,
      setArtistArt: (artistArt) => set({ artistArt }),
      setMotion: (motion) => set({ motion }),
    }),
    { name: 'nuvx:apple', version: 1 },
  ),
);

const AMP = 'https://amp-api.music.apple.com/v1/catalog';

const storefront = (() => {
  const region = typeof navigator === 'undefined' ? '' : (navigator.language.split('-')[1] ?? '');
  return /^[a-z]{2}$/i.test(region) ? region.toLowerCase() : 'us';
})();

async function catalog(path: string, params: Record<string, string | number>): Promise<Json | null> {
  const token = await appleWebToken();
  if (!token) throw new Error('Apple Music is out of reach');
  const body = await lyricsGet(urlWith(`${AMP}/${storefront}/${path}`, params), {
    route: 'extension',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (body === null) throw new Error('Apple Music catalogue read failed');
  return obj(parse(body));
}

const hitsOf = (root: Json | null, type: string): Json[] =>
  ((obj(obj(root?.results)?.[type])?.data as Json[] | undefined) ?? []).filter((h): h is Json => !!obj(h));

export async function fetchTrackMotion(title: string, artist: string, album: string | null): Promise<AppleMotion | null> {
  const root = await catalog('search', {
    term: songTerm(title, artist, album),
    types: 'songs',
    limit: 10,
    extend: 'editorialVideo',
    include: 'albums',
  });
  for (const song of ranked(hitsOf(root, 'songs'), title, artist, album)) {
    const attributes = obj(song.attributes);
    const found = { title: str(attributes?.name) || null, artist: str(attributes?.artistName) || null, album: str(attributes?.albumName) || null };
    const inline = motionUrls(attributes?.editorialVideo);
    if (inline && matches(found, title, artist, album)) return inline;

    const albumId = albumIdOf(song);
    if (!albumId) continue;
    const record = obj(((await catalog(`albums/${albumId}`, { extend: 'editorialVideo' }))?.data as Json[] | undefined)?.[0]);
    const albumAttributes = obj(record?.attributes);
    const albumName = str(albumAttributes?.name);
    if (!albumAttributes || isCompilation(albumName)) continue;
    const motion = motionUrls(albumAttributes.editorialVideo);
    if (motion && matches({ ...found, album: albumName || found.album }, title, artist, album)) return motion;
  }
  return null;
}

export async function fetchAlbumMotion(album: string, artist: string): Promise<AppleMotion | null> {
  const term = album.toLowerCase().includes(artist.toLowerCase()) ? album : `${artist} ${album}`;
  const root = await catalog('search', { term, types: 'albums', limit: 10, extend: 'editorialVideo' });
  for (const record of ranked(hitsOf(root, 'albums'), album, artist, album, true)) {
    const attributes = obj(record.attributes);
    const name = str(attributes?.name);
    if (!attributes || isCompilation(name)) continue;
    const motion = motionUrls(attributes.editorialVideo);
    if (motion && matches({ title: name, artist: str(attributes.artistName) || null, album: name }, album, artist, album)) return motion;
  }
  return null;
}

onGranted(() => void queryClient.invalidateQueries({ queryKey: ['apple', 'motion'] }));

function useMotionAllowed(): boolean {
  const enabled = useAppleSettings((s) => s.motion);
  const still = useReducedMotion();
  return enabled && !still && !metered();
}

const QUERY = { staleTime: Infinity, gcTime: 30 * 60_000, retry: false } as const;

export function useTrackMotion(title: string | null | undefined, artist: string | null | undefined, album: string | null | undefined) {
  const allowed = useMotionAllowed();
  const query = useQuery({
    queryKey: ['apple', 'motion', 'track', title ?? '', artist ?? '', album ?? ''],
    queryFn: () => fetchTrackMotion(title!, artist!, album ?? null),
    enabled: allowed && !!title?.trim() && !!artist?.trim(),
    ...QUERY,
  });
  return allowed ? (query.data ?? null) : null;
}

export function useAlbumMotion(album: string | null | undefined, artist: string | null | undefined) {
  const allowed = useMotionAllowed();
  const query = useQuery({
    queryKey: ['apple', 'motion', 'album', album ?? '', artist ?? ''],
    queryFn: () => fetchAlbumMotion(album!, artist!),
    enabled: allowed && !!album?.trim() && !!artist?.trim(),
    ...QUERY,
  });
  return allowed ? (query.data ?? null) : null;
}
