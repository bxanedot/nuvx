import { useEffect, useRef } from 'react';
import { Navigate, useParams } from 'react-router';
import { PageHeader } from '@/components/PageHeader';
import { CircleLockup, SquareLockup, VideoLockup } from '@/components/music/Lockups';
import { EmptyState, LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { isLibrarySection, LIBRARY_SECTIONS } from '@/data/music/library';
import { useNuvxPlaylists } from '@/data/music/localPlaylists';
import { useLibrary, useSignedIn, useYouTubeReady } from '@/data/music/queries';
import { useYtAuth } from '@/extension/useYtAuth';
import { PlainSkeleton } from '@/skeletons/PageSkeletons';
import { seeAllGrid, seeAllTracks } from './pageStyles';

function useLoadMore(onVisible: (() => void) | null) {
  const target = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = target.current;
    if (!node || !onVisible) return;
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && onVisible(), {
      rootMargin: '600px 0px',
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [onVisible]);
  return target;
}

export default function LibraryPage() {
  const section = useParams().section ?? '';
  const { missing } = useYouTubeReady();
  const auth = useYtAuth();
  const signedIn = useSignedIn();
  const localPlaylists = useNuvxPlaylists();
  const localGrid = section === 'playlists' ? localPlaylists.map((playlist) => ({
    kind: 'playlist' as const,
    id: playlist.id,
    title: playlist.title,
    subtitle: `${playlist.tracks.length} songs · This device`,
    artists: [],
    album: null,
    thumbnail: playlist.tracks[0]?.thumbnail ?? null,
    wide: false,
    durationSec: null,
    explicit: false,
  })) : [];
  const library = useLibrary(isLibrarySection(section) ? section : 'playlists');
  const loadMore = useLoadMore(
    library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
      ? () => void library.fetchNextPage()
      : null,
  );

  if (!isLibrarySection(section)) return <Navigate to="/home" replace />;
  const title = LIBRARY_SECTIONS[section].title;
  if (missing) return <><NeedsExtension what="your library" />{localGrid.length > 0 && <ul className={seeAllGrid}>{localGrid.map((item) => <li key={item.id}><SquareLockup item={item} /></li>)}</ul>}</>;
  if (auth.state === 'ready' && !signedIn) {
    return (
      <>
        <PageHeader title={title} />
        <EmptyState
          title="Sign in to see your library"
          detail="nuvx uses the YouTube Music account this browser is signed in to. Sign in on music.youtube.com, then come back."
        />
        {localGrid.length > 0 && <ul className={seeAllGrid}>{localGrid.map((item) => <li key={item.id}><SquareLockup item={item} /></li>)}</ul>}
      </>
    );
  }
  if (library.isError && !library.data) return <><LoadError error={library.error} onRetry={() => void library.refetch()} />{localGrid.length > 0 && <ul className={seeAllGrid}>{localGrid.map((item) => <li key={item.id}><SquareLockup item={item} /></li>)}</ul>}</>;
  if (!library.data && localGrid.length === 0) return <PlainSkeleton />;

  const grid = [...localGrid, ...(library.data?.pages.flatMap((page) => page.data.grid) ?? [])];
  const tracks = library.data?.pages.flatMap((page) => page.data.tracks) ?? [];
  return (
    <>
      <PageHeader title={title} />
      {!grid.length && !tracks.length && <EmptyState title="Nothing here yet" />}
      {grid.length > 0 && (
        <ul className={seeAllGrid} data-wide={grid.every((i) => i.wide) || undefined}>
          {grid.map((item, i) => (
            <li key={`${item.id}-${i}`}>
              {item.kind === 'artist' ? (
                <CircleLockup item={item} />
              ) : item.wide ? (
                <VideoLockup item={item} />
              ) : (
                <SquareLockup item={item} />
              )}
            </li>
          ))}
        </ul>
      )}
      {tracks.length > 0 && (
        <div className={seeAllTracks}>
          <TrackList
            tracks={tracks}
            variant="playlist"
            source={{ title, type: 'library', id: section }}
            onNearEnd={
              library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
                ? () => void library.fetchNextPage()
                : undefined
            }
          />
        </div>
      )}
      {grid.length > 0 && <div ref={loadMore} aria-hidden="true" />}
      {library.isFetchNextPageError && <LoadMoreError onRetry={() => void library.fetchNextPage()} />}
    </>
  );
}
