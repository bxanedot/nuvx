import { useCallback } from 'react';
import { useParams } from 'react-router';
import { DetailHeader } from '@/components/music/DetailHeader';
import { playList, shuffleList } from '@/components/music/play';
import { LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { usePlaylist, useYouTubeReady } from '@/data/music/queries';
import { useNuvxPlaylists } from '@/data/music/localPlaylists';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { PlaylistSkeleton } from '@/skeletons/PageSkeletons';
import { detailFooter } from './pageStyles';

export default function PlaylistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const { missing } = useYouTubeReady();
  const playlist = usePlaylist(id);
  const local = useNuvxPlaylists().find((item) => item.id === id);
  const isLocal = id.startsWith('nuvx-local:');
  const header = isLocal
    ? local && {
        title: local.title,
        subtitle: 'Nuvx only · This device',
        meta: `${local.tracks.length} songs`,
        description: null,
        thumbnail: local.tracks[0]?.thumbnail ?? null,
        artists: [],
        explicit: false,
        library: null,
      }
    : playlist.data?.pages[0]?.header ?? null;
  usePageTint(useArtworkColor(header?.thumbnail));

  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = playlist;
  const onNearEnd = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage && !isFetchNextPageError) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage]);

  if (missing && !isLocal) return <NeedsExtension />;
  if (isLocal && !local) return <LoadError error={new Error('This playlist is no longer saved in this browser.')} onRetry={() => window.location.reload()} />;
  if (!isLocal && playlist.isError && !playlist.data) return <LoadError error={playlist.error} onRetry={() => void playlist.refetch()} />;
  if (!header || (!isLocal && !playlist.data)) return <PlaylistSkeleton />;

  const tracks = local?.tracks ?? playlist.data!.pages.flatMap((page) => page.tracks);
  const context = { source: { title: header.title, type: 'playlist' as const, id } };
  return (
    <>
      <DetailHeader
        info={header}
        canPlay={tracks.length > 0}
        onPlay={() => playList(tracks, 0, context)}
        onShuffle={() => shuffleList(tracks, context)}
      />
      <TrackList tracks={tracks} variant="playlist" onNearEnd={onNearEnd} source={context.source} />
      {!isLocal && isFetchingNextPage && <div className="flex justify-center py-5 type-callout text-secondary">Loading more songs…</div>}
      {!isLocal && playlist.isFetchNextPageError && <LoadMoreError onRetry={() => void fetchNextPage()} />}
      <footer className={detailFooter}>
        <p>{header.meta}</p>
      </footer>
    </>
  );
}
