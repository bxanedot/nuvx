export type ItemKind = 'song' | 'video' | 'album' | 'playlist' | 'spotify-playlist' | 'artist' | 'episode' | 'podcast' | 'unknown';

export interface ArtistRef {
  name: string;
  id: string | null;
}

export interface AlbumRef {
  name: string;
  id: string | null;
}

export interface MusicItem {
  kind: ItemKind;
  id: string;
  title: string;
  subtitle: string;
  artists: ArtistRef[];
  album: AlbumRef | null;
  thumbnail: string | null;
  wide: boolean;
  durationSec: number | null;
  explicit: boolean;
}

export type ShelfLayout = 'tracks' | 'square' | 'video' | 'circle';

export interface BrowseLink {
  browseId: string;
  params: string | null;
}

export interface Shelf {
  title: string;
  strapline: string | null;
  layout: ShelfLayout;
  items: MusicItem[];
  seeAll: BrowseLink | null;
}

export interface Track {
  videoId: string;
  streamUrl?: string;
  title: string;
  artists: ArtistRef[];
  album: AlbumRef | null;
  durationSec: number | null;
  explicit: boolean;
  thumbnail: string | null;
  index: number | null;
  plays: string | null;
  unavailable?: 'matching' | 'missing';
}

export interface HeaderInfo {
  title: string;
  subtitle: string;
  meta: string;
  description: string | null;
  thumbnail: string | null;
  artists: ArtistRef[];
  explicit: boolean;
  library: { playlistId: string; saved: boolean } | null;
}

export interface AlbumPage extends HeaderInfo {
  id: string;
  tracks: Track[];
  playlistId: string | null;
  more: Shelf[];
}

export interface PlaylistPage extends HeaderInfo {
  id: string;
  tracks: Track[];
}

export interface ArtistPage {
  id: string;
  name: string;
  description: string | null;
  heroImage: string | null;
  audience: string | null;
  topSongs: Track[];
  topSongsSeeAll: BrowseLink | null;
  shelves: Shelf[];
}

export interface HomePage {
  shelves: Shelf[];
}

export interface Category {
  title: string;
  color: string;
  link: BrowseLink;
}

export interface CategoryGroup {
  title: string;
  categories: Category[];
}

export interface SearchResults {
  top: MusicItem[];
  songs: MusicItem[];
  artists: MusicItem[];
  albums: MusicItem[];
  playlists: MusicItem[];
  videos: MusicItem[];
}

export interface Suggestions {
  queries: { text: string; typed: string; rest: string }[];
  items: MusicItem[];
}

export interface BrowsePage {
  title: string;
  shelves: Shelf[];
  grid: MusicItem[];
  tracks: Track[];
}
