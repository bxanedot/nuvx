import { albumHref, artistHref } from '@/components/music/links';
import { fetchTrackLinks, findArtistPageId } from '@/data/music/api';
import type { AlbumRef, ArtistRef } from '@/data/music/types';
import { isExactArtistMatch, normalizedArtistName } from '@/lib/artistCredits';
import { queryClient } from '@/lib/queryClient';

function trackLinks(videoId: string) {
  return queryClient
    .fetchQuery({ queryKey: ['music', 'track-links', videoId], queryFn: () => fetchTrackLinks(videoId), staleTime: Infinity })
    .catch(() => null);
}

function searchHref(query: string): string {
  return `/search?q=${encodeURIComponent(query)}`;
}

export async function albumPageFor(videoId: string, album: AlbumRef | null, fallbackQuery: string): Promise<string> {
  if (album?.id) return albumHref(album.id)!;
  const linked = await trackLinks(videoId);
  return albumHref(linked?.album?.id ?? null) ?? searchHref(fallbackQuery);
}

export async function artistPageFor(videoId: string, credit: ArtistRef): Promise<string> {
  if (credit.id) return artistHref(credit.id)!;
  const linked = await trackLinks(videoId);
  const stated = linked?.artists.find((a) => a.id && isExactArtistMatch(a.name, credit.name));
  if (stated) return artistHref(stated.id)!;
  const found = await queryClient
    .fetchQuery({
      queryKey: ['music', 'artist-by-name', normalizedArtistName(credit.name)],
      queryFn: () => findArtistPageId(credit.name),
      staleTime: Infinity,
    })
    .catch(() => null);
  return artistHref(found) ?? searchHref(credit.name);
}
