import type { CSSProperties } from 'react';
import { SfSymbolIcon, type SfSymbolName, type SfSymbolEffect } from './SfSymbol';

/** Minimum common icon props; compatible with legacy SVG and SFStudio renderers. */
export type NavIconProps = { size?: number; className?: string; style?: CSSProperties; effect?: SfSymbolEffect; trigger?: string | number | boolean; active?: boolean };

function symbol(name: SfSymbolName, defaultSize: number) {
  return function NavIcon({ size = defaultSize, ...props }: NavIconProps) {
    return <SfSymbolIcon name={name} size={size} {...props} />;
  };
}

export const RadioIcon = symbol('dot.radiowaves.left.and.right', 24);
export const PodcastIcon = symbol('microphone', 24);
export const SearchIcon = symbol('magnifyingglass', 28);
export const HomeIcon = symbol('house.fill', 28);
export const NewIcon = symbol('square.grid.2x2.fill', 28);
export const RecentlyAddedIcon = symbol('clock.arrow.trianglehead.counterclockwise.rotate.90', 28);
export const ArtistsIcon = symbol('person', 28);
export const AlbumsIcon = symbol('square.stack.3d.up.fill', 28);
export const SongsIcon = symbol('music.note', 28);
export const MusicVideosIcon = symbol('video', 28);
export const MadeForYouIcon = symbol('sparkles', 28);
export const AllPlaylistsIcon = symbol('square.stack.fill', 28);
