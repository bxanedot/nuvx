import { useQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import { requestHostAccess, useHostAccess } from '@/extension/useHostAccess';
import { lyricsGet, onGranted, onUngrantedChange, ungrantedOrigins, urlWith } from '@/lyrics/http';
import { queryClient } from '@/lib/queryClient';
import { normalized, num, obj, parse, parseArtistPage, str, type AppleArtistArt, type Json } from './artistPage';
import { useAppleSettings } from './motionArt';
import { appleWebToken } from './webToken';

export type { AppleArtistArt };

async function artistPageUrl(name: string): Promise<string | null> {
  const key = normalized(name);
  const itunes = await lyricsGet(urlWith('https://itunes.apple.com/search', { term: name, entity: 'musicArtist', limit: 5 }));
  if (itunes) {
    const results = (obj(parse(itunes))?.results as Json[] | undefined) ?? [];
    const hit = results.find((r) => normalized(str(r.artistName)) === key);
    if (!hit) return null;
    const link = str(hit.artistLinkUrl).split('?')[0];
    return link || (num(hit.artistId) ? `https://music.apple.com/us/artist/${num(hit.artistId)}` : null);
  }

  const token = await appleWebToken();
  if (!token) throw new Error('Apple Music is out of reach');
  const search = await lyricsGet(
    urlWith('https://amp-api.music.apple.com/v1/catalog/us/search', { term: name, types: 'artists', limit: 5, l: 'en-US' }),
    { route: 'extension', headers: { Authorization: `Bearer ${token}` } },
  );
  if (!search) throw new Error('Apple Music search failed');
  const artists = (obj(obj(obj(parse(search))?.results)?.artists)?.data as Json[] | undefined) ?? [];
  const hit = artists.find((a) => normalized(str(obj(a.attributes)?.name)) === key);
  return hit ? str(obj(hit.attributes)?.url).split('?')[0] || null : null;
}

export async function fetchAppleArtistArt(name: string): Promise<AppleArtistArt | null> {
  if (!normalized(name)) return null;
  const page = await artistPageUrl(name);
  if (!page) return null;
  const html = await lyricsGet(page, { route: 'extension' });
  if (!html) throw new Error('Apple Music artist page out of reach');
  return parseArtistPage(html);
}

onGranted(() => void queryClient.invalidateQueries({ queryKey: ['apple', 'artist-art'] }));

export const APPLE_ORIGINS = ['https://music.apple.com', 'https://amp-api.music.apple.com'];

export function useUngrantedAppleOrigins(): string[] {
  return useSyncExternalStore(
    onUngrantedChange,
    () => APPLE_ORIGINS.filter((o) => ungrantedOrigins.has(o)).join(','),
    () => '',
  )
    .split(',')
    .filter(Boolean);
}

export function requestAppleAccess(): void {
  requestHostAccess(APPLE_ORIGINS);
}

export function useAppleAccess() {
  return useHostAccess(APPLE_ORIGINS);
}

export function useAppleArtistArt(name: string | null | undefined): AppleArtistArt | null {
  const on = useAppleSettings((s) => s.artistArt);
  const query = useQuery({
    queryKey: ['apple', 'artist-art', normalized(name ?? '')],
    queryFn: () => fetchAppleArtistArt(name!),
    enabled: on && !!name && !!normalized(name),
    staleTime: Infinity,
    gcTime: 60 * 60_000,
    retry: false,
  });
  return on ? (query.data ?? null) : null;
}
