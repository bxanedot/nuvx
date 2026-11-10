import type { ArtistRef, MusicItem, Track } from '@/data/music/types';
import type { QueueSource } from '@/playback/queue/timeline';
import { usePlayer } from '@/state/player';

export function toTrack(item: Track | MusicItem, fallbackArtwork?: string | null): Track {
  if ('videoId' in item) return item.thumbnail || !fallbackArtwork ? item : { ...item, thumbnail: fallbackArtwork };
  return {
    videoId: item.id,
    title: item.title,
    artists: item.artists,
    album: item.album,
    durationSec: item.durationSec,
    explicit: item.explicit,
    thumbnail: item.thumbnail ?? fallbackArtwork ?? null,
    index: null,
    plays: null,
  };
}

export function playTrack(item: Track | MusicItem, fallbackArtwork?: string | null, source?: QueueSource) {
  const track = toTrack(item, fallbackArtwork);
  usePlayer.getState().playTrack(track, source ?? { title: track.title, type: 'other' });
}

export interface ListContext {
  source: QueueSource;
  fallbackArtwork?: string | null | undefined;
  fallbackArtists?: ArtistRef[] | undefined;
}

function prepared(tracks: Track[], { fallbackArtwork, fallbackArtists }: ListContext): Track[] {
  return tracks.map((t) => {
    const withArt = toTrack(t, fallbackArtwork);
    return withArt.artists.length || !fallbackArtists ? withArt : { ...withArt, artists: fallbackArtists };
  });
}

export function playList(tracks: Track[], startIndex: number, context: ListContext) {
  usePlayer.getState().playTracks(prepared(tracks, context), startIndex, context.source);
}

export function shuffleList(tracks: Track[], context: ListContext) {
  if (!tracks.length) return;
  const player = usePlayer.getState();
  if (!player.shuffle) player.toggleShuffle();
  player.playTracks(prepared(tracks, context), Math.floor(Math.random() * tracks.length), context.source);
}
