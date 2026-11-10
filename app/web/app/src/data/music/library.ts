export const LIBRARY_SECTIONS = {
  recent: { title: 'Recently Played', browseId: 'FEmusic_history' },
  artists: { title: 'Artists', browseId: 'FEmusic_library_corpus_track_artists' },
  albums: { title: 'Albums', browseId: 'FEmusic_liked_albums' },
  songs: { title: 'Songs', browseId: 'FEmusic_liked_videos' },
  'made-for-you': { title: 'Made for You', browseId: 'FEmusic_mixed_for_you' },
  playlists: { title: 'All Playlists', browseId: 'FEmusic_liked_playlists' },
} as const;

export type LibrarySection = keyof typeof LIBRARY_SECTIONS;

export function isLibrarySection(value: string): value is LibrarySection {
  return Object.hasOwn(LIBRARY_SECTIONS, value);
}

export const LIKED_MUSIC_PLAYLIST = 'LM';
