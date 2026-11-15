import { ChevronLeft, ChevronRight, ListEnd, ListPlus, ListStart, MoreHorizontal, Search, Star } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator, type MenuPlacement } from '@/components/Menu';
import {
  useAddToPlaylist,
  useLikeStatus,
  usePlaylistOptions,
  useSetLiked,
  useSignedIn,
} from '@/data/music/queries';
import type { Track } from '@/data/music/types';
import { addToNuvxPlaylist, createNuvxPlaylist, createPlaylistsLocally, useNuvxPlaylists } from '@/data/music/localPlaylists';
import { usePlayer } from '@/state/player';

function PlaylistChoices({ track, onDone, onBack }: { track: Track; onDone: () => void; onBack: () => void }) {
  const { videoId } = track;
  const options = usePlaylistOptions(videoId, true);
  const add = useAddToPlaylist();
  const localPlaylists = useNuvxPlaylists();
  const localOnly = createPlaylistsLocally();
  const signedIn = useSignedIn();
  const [query, setQuery] = useState('');
  const matches = (title: string) => title.toLowerCase().includes(query.trim().toLowerCase());
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>
        Back
      </MenuItem>
      <MenuSeparator />
      <MenuHeader>Add to Playlist</MenuHeader>
      <div className="relative mx-1 mb-1 flex items-center">
        <Search size={13} strokeWidth={2.4} className="pointer-events-none absolute left-2 text-secondary" aria-hidden="true" />
        <input
          className="h-7 w-full rounded-[5px] border border-search-border bg-page pl-6 pr-2 type-body text-primary placeholder:text-tertiary [&::-webkit-search-cancel-button]:hidden"
          type="search"
          value={query}
          placeholder="Find a playlist"
          aria-label="Find a playlist"
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {localOnly && (
        <MenuItem
          onSelect={() => {
            const title = window.prompt('Name this Nuvx playlist');
            if (title?.trim()) createNuvxPlaylist(title, track);
            onDone();
          }}
        >
          New Nuvx playlist…
        </MenuItem>
      )}
      {localPlaylists.filter((playlist) => matches(playlist.title)).map((playlist) => (
        <MenuItem
          key={playlist.id}
          onSelect={() => {
            addToNuvxPlaylist(playlist.id, track);
            onDone();
          }}
        >
          {playlist.title} · This device
        </MenuItem>
      ))}
      {signedIn && options.isPending && (
        <MenuItem disabled onSelect={() => {}}>
          Loading…
        </MenuItem>
      )}
      {signedIn && options.isError && (
        <MenuItem disabled onSelect={() => {}}>
          Couldn’t load your playlists
        </MenuItem>
      )}
      {signedIn && options.data?.length === 0 && localPlaylists.length === 0 && (
        <MenuItem disabled onSelect={() => {}}>
          No playlists yet
        </MenuItem>
      )}
      {signedIn && options.data?.filter((option) => matches(option.title)).map((option) => (
        <MenuItem
          key={option.playlistId}
          onSelect={() => {
            add.mutate({ playlistId: option.playlistId, videoId });
            onDone();
          }}
        >
          {option.title}
        </MenuItem>
      ))}
      {query.trim() && !localPlaylists.some((p) => matches(p.title)) && !options.data?.some((o) => matches(o.title)) && (
        <MenuItem disabled onSelect={() => {}}>
          No matching playlists
        </MenuItem>
      )}
    </>
  );
}

function AccountItems({ track, signedIn, onClose, onPlaylists }: { track: Track; signedIn: boolean; onClose: () => void; onPlaylists: () => void }) {
  const status = useLikeStatus(track.videoId);
  const setLiked = useSetLiked();
  const liked = status.data === 'LIKE';
  return (
    <>
      <MenuSeparator />
      {signedIn && <MenuItem
        icon={<Star size={15} fill={liked ? 'currentColor' : 'none'} />}
        disabled={status.isPending}
        onSelect={() => {
          setLiked.mutate({ videoId: track.videoId, liked: !liked });
          onClose();
        }}
      >
        {liked ? 'Unfavorite' : 'Favorite'}
      </MenuItem>}
      <MenuItem icon={<ListPlus size={15} />} trailing={<ChevronRight size={14} />} onSelect={onPlaylists}>
        Add to Playlist
      </MenuItem>
    </>
  );
}

function TrackMenuItems({ track, onClose }: { track: Track; onClose: () => void }) {
  const [view, setView] = useState<'main' | 'playlists'>('main');
  const signedIn = useSignedIn();
  const localOnly = createPlaylistsLocally();
  const localPlaylists = useNuvxPlaylists();
  if (view === 'playlists') {
    return <PlaylistChoices track={track} onDone={onClose} onBack={() => setView('main')} />;
  }
  const { playNext, addToQueue } = usePlayer.getState();
  return (
    <>
      <MenuItem icon={<ListStart size={15} />} onSelect={() => (onClose(), playNext([track]))}>
        Play Next
      </MenuItem>
      <MenuItem icon={<ListEnd size={15} />} onSelect={() => (onClose(), addToQueue([track]))}>
        Add to Queue
      </MenuItem>
      {(signedIn || localOnly || localPlaylists.length > 0) && <AccountItems track={track} signedIn={signedIn} onClose={onClose} onPlaylists={() => setView('playlists')} />}
    </>
  );
}

export function TrackMenu({
  track,
  className,
  placement,
  onOpenChange,
}: {
  track: Track;
  className?: string;
  placement?: MenuPlacement;
  onOpenChange?: (open: boolean) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  useEffect(() => {
    onOpenChange?.(anchor !== null);
  }, [anchor, onOpenChange]);
  return (
    <>
      <button
        className={className}
        aria-label={`More options for ${track.title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        data-open={anchor !== null || undefined}
        onClick={(event) => {
          event.stopPropagation();
          setAnchor(anchor ? null : event.currentTarget);
        }}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <MoreHorizontal size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement={placement} onClose={close} label={track.title}>
          <TrackMenuItems track={track} onClose={close} />
        </Menu>
      )}
    </>
  );
}
