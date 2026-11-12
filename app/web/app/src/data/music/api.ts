import type { YTMusic, YTNodes } from 'youtubei.js/web';
import type { Candidate } from '@/playback/queue/builder';
import { ensureYtAuth } from '@/extension/useYtAuth';
import { getInnertube, getMusicClient, getSignedInnertube } from '@/playback/youtube/innertube';
import { isExactArtistMatch, normalizedArtistName } from '@/lib/artistCredits';
import { LIBRARY_SECTIONS, LIKED_MUSIC_PLAYLIST, type LibrarySection } from './library';
import {
  mapAlbum,
  mapArtist,
  mapBrowse,
  mapCategories,
  mapHome,
  mapPlaylist,
  mapPlaylistTracks,
  mapSearch,
  mapShelves,
  mapSuggestions,
} from './mappers';
import type {
  AlbumPage,
  MusicItem,
  ArtistPage,
  BrowseLink,
  BrowsePage,
  CategoryGroup,
  PlaylistPage,
  SearchResults,
  Shelf,
  Suggestions,
  Track,
} from './types';

export interface Paged<T> {
  data: T;
  next: (() => Promise<Paged<T>>) | null;
}

async function orEnd<T>(nextPage: () => Promise<Paged<T>>, empty: T): Promise<Paged<T>> {
  try {
    return await nextPage();
  } catch (error) {
    if (/did not have any content|no continuation/i.test(String(error))) return { data: empty, next: null };
    throw error;
  }
}

function homePage(feed: YTMusic.HomeFeed): Paged<Shelf[]> {
  return {
    data: mapHome(feed),
    next: feed.has_continuation ? () => orEnd(async () => homePage(await feed.getContinuation()), []) : null,
  };
}

export async function fetchHome(): Promise<Paged<Shelf[]>> {
  const yt = await getMusicClient();
  return homePage(await yt.music.getHomeFeed());
}

export async function fetchExplore(): Promise<Shelf[]> {
  const yt = await getMusicClient();
  return mapShelves((await yt.music.getExplore()).sections);
}

export async function fetchPodcasts(): Promise<Shelf[]> {
  const auth = await ensureYtAuth().catch(() => null);
  if (auth?.signedIn) {
    try {
      const yt = await getMusicClient();
      const home = await yt.music.getHomeFeed();
      const filter = home.header?.chips?.find((chip) => chip.text.toLowerCase() === 'podcasts');
      if (filter) {
        const shelves = mapShelves((await home.applyFilter(filter)).sections);
        if (shelves.length) return shelves;
      }
    } catch {
      // Fall through to YouTube's public search if the account feed is unavailable.
    }
  }

  const yt = await getInnertube();
  const search = await yt.search('podcast full episodes', { type: 'video', duration: 'long' });
  const items: MusicItem[] = search.videos.map((result) => {
    const video = result as unknown as {
      id?: string;
      title?: { toString(): string };
      author?: { name?: string; id?: string };
      thumbnails?: { url: string; width: number }[];
      duration?: { seconds: number };
    };
    const artist = video.author?.name ?? 'YouTube';
    return {
      kind: 'video',
      id: video.id ?? '',
      title: video.title?.toString().trim() ?? '',
      subtitle: artist,
      artists: [{ name: artist, id: video.author?.id ?? null }],
      album: null,
      thumbnail: [...(video.thumbnails ?? [])].sort((a, b) => b.width - a.width)[0]?.url ?? null,
      wide: true,
      durationSec: video.duration?.seconds ?? null,
      explicit: false,
    };
  }).filter((item) => !!item.id && !!item.title);

  return items.length
    ? [{ title: 'Podcasts on YouTube', strapline: 'Long-form episodes', layout: 'video', items, seeAll: null }]
    : [];
}

interface RadioCountry { name: string; iso_3166_1: string; stationcount: number }
interface RadioStation {
  stationuuid: string; name: string; url_resolved: string; favicon: string;
  country: string; countrycode: string; tags: string; codec: string; bitrate: number; hls: number;
}
const RADIO_HOSTS = ['all.api.radio-browser.info', 'de1.api.radio-browser.info', 'fi1.api.radio-browser.info'];

async function radioRequest<T>(path: string, params: Record<string, string> = {}): Promise<T> {
  let failure: unknown;
  for (const host of RADIO_HOSTS) {
    try {
      const url = new URL(`https://${host}/json/${path}`);
      for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
      const response = await fetch(url);
      if (!response.ok) throw new Error(`Radio Browser answered ${response.status}`);
      return await response.json() as T;
    } catch (error) { failure = error; }
  }
  throw failure ?? new Error('Radio Browser is unavailable');
}

export async function fetchRadioCountries(): Promise<RadioCountry[]> {
  return (await radioRequest<RadioCountry[]>('countries', { order: 'stationcount', reverse: 'true', hidebroken: 'true' }))
    .filter((country) => country.stationcount > 0 && /^[A-Z]{2}$/i.test(country.iso_3166_1));
}

export async function fetchRadioStations(country: string | null, query: string): Promise<Track[]> {
  const rows = await radioRequest<RadioStation[]>(country ? `stations/bycountrycodeexact/${encodeURIComponent(country)}` : 'stations/search', {
    hidebroken: 'true', order: 'clickcount', reverse: 'true', limit: '100', ...(country ? {} : { name: query }),
  });
  return rows.filter((station) => station.url_resolved.startsWith('https://') && !station.hls && (!station.codec || /^(mp3|aac)/i.test(station.codec)))
    .filter((station, index, all) => all.findIndex((item) => item.url_resolved === station.url_resolved) === index)
    .map((station) => ({
      videoId: `radio:${station.stationuuid}`, streamUrl: station.url_resolved, title: station.name.trim() || 'Radio station',
      artists: [{ name: [station.country || station.countrycode, station.tags.split(',').map((tag) => tag.trim()).filter(Boolean).slice(0, 2).join(' · '), [station.codec, station.bitrate > 0 ? `${station.bitrate} kbps` : ''].filter(Boolean).join(' ')].filter(Boolean).join(' · ') || 'Live radio', id: null }], album: null, durationSec: null,
      explicit: false, thumbnail: station.favicon.startsWith('https://') ? station.favicon : null, index: null, plays: null,
    }));
}

export function countRadioClick(id: string): void {
  void radioRequest<unknown>(`url/${encodeURIComponent(id)}`).catch(() => {});
}

export async function fetchCategories(): Promise<CategoryGroup[]> {
  const yt = await getInnertube();
  const response = await yt.actions.execute('/browse', { browseId: 'FEmusic_moods_and_genres', client: 'YTMUSIC' });
  return mapCategories(response.data);
}

export async function fetchSearch(query: string): Promise<SearchResults> {
  const yt = await getMusicClient();
  return mapSearch(await yt.music.search(query));
}

export async function searchSongs(query: string): Promise<Track[]> {
  const yt = await getMusicClient();
  const search = await yt.music.search(query, { type: 'song' });
  const shelf = search.songs ?? search.contents?.find((c) => c.type === 'MusicShelf');
  return mapPlaylistTracks((shelf as { contents?: readonly unknown[] } | undefined)?.contents ?? []);
}

export async function fetchSuggestions(input: string): Promise<Suggestions> {
  const yt = await getInnertube();
  return mapSuggestions(await yt.music.getSearchSuggestions(input));
}

export async function fetchAlbum(id: string): Promise<AlbumPage> {
  const yt = await getMusicClient();
  return mapAlbum(id, await yt.music.getAlbum(id));
}

const ARTIST_TOP_SONGS = 20;

export async function fetchArtist(id: string): Promise<ArtistPage> {
  const yt = await getMusicClient();
  const page = mapArtist(id, await yt.music.getArtist(id));
  const playlistId = page.topSongsSeeAll?.browseId;
  if (playlistId?.startsWith('VL') && page.topSongs.length < ARTIST_TOP_SONGS) {
    try {
      const { page: playlist } = await fetchPlaylist(playlistId);
      if (playlist.tracks.length > page.topSongs.length) page.topSongs = playlist.tracks.slice(0, ARTIST_TOP_SONGS);
    } catch {
    }
  }
  return page;
}

function playlistTracks(page: YTMusic.Playlist): Paged<Track[]> {
  return {
    data: mapPlaylistTracks(page.items),
    next: page.has_continuation ? () => orEnd(async () => playlistTracks(await page.getContinuation()), []) : null,
  };
}

export async function fetchPlaylist(id: string): Promise<{ page: PlaylistPage; more: Paged<Track[]>['next'] }> {
  const yt = await getMusicClient();
  const playlist = await yt.music.getPlaylist(id);
  return { page: mapPlaylist(id, playlist), more: playlistTracks(playlist).next };
}

export async function fetchBrowse({ browseId, params }: BrowseLink): Promise<BrowsePage> {
  const yt = await getMusicClient();
  const { YTNodes } = await import('youtubei.js/web');
  const response = await yt.actions.execute('/browse', {
    browseId,
    ...(params && { params }),
    client: 'YTMUSIC',
    parse: true,
  });
  const memo = response.contents_memo;
  const sections = [
    ...(memo?.getType(YTNodes.Grid) ?? []),
    ...(memo?.getType(YTNodes.MusicCarouselShelf) ?? []),
    ...(memo?.getType(YTNodes.MusicShelf) ?? []),
    ...(memo?.getType(YTNodes.MusicPlaylistShelf) ?? []),
  ];
  const header = memo?.getType(YTNodes.MusicHeader)?.[0] ?? (response.header as unknown);
  return mapBrowse({ header, sections }, '');
}

type Raw = Record<string, unknown>;

function collect(root: unknown, key: string, out: Raw[] = []): Raw[] {
  if (Array.isArray(root)) for (const item of root) collect(item, key, out);
  else if (root && typeof root === 'object') {
    for (const [k, v] of Object.entries(root)) {
      if (k === key && v && typeof v === 'object' && !Array.isArray(v)) out.push(v as Raw);
      else collect(v, key, out);
    }
  }
  return out;
}

function runsText(node: unknown): string {
  const n = node as { runs?: { text?: string }[]; simpleText?: string } | undefined;
  return n?.runs?.map((r) => r.text ?? '').join('') ?? n?.simpleText ?? '';
}

export async function fetchLibrary(section: LibrarySection): Promise<Paged<BrowsePage>> {
  const yt = await getSignedInnertube();
  const { YTNodes } = await import('youtubei.js/web');
  const { title, browseId } = LIBRARY_SECTIONS[section];
  const response = await yt.actions.execute('/browse', { browseId, client: 'YTMUSIC', parse: true });
  const memo = response.contents_memo;
  const grids = memo?.getType(YTNodes.Grid) ?? [];
  const lists = [...(memo?.getType(YTNodes.MusicShelf) ?? []), ...(memo?.getType(YTNodes.MusicPlaylistShelf) ?? [])];
  const carousels = memo?.getType(YTNodes.MusicCarouselShelf) ?? [];
  const page = mapBrowse({ sections: [...grids, ...lists, ...carousels] }, title);
  const token = (grids[0]?.continuation ?? (lists.length === 1 ? lists[0]?.continuation : null)) || null;
  return { data: { ...page, title }, next: token ? () => orEnd(() => libraryContinuation(token, title), emptyPage(title)) : null };
}

const emptyPage = (title: string): BrowsePage => ({ title, shelves: [], grid: [], tracks: [] });

async function libraryContinuation(token: string, title: string): Promise<Paged<BrowsePage>> {
  const yt = await getSignedInnertube();
  const response = await yt.actions.execute('/browse', { continuation: token, client: 'YTMUSIC', parse: true });
  const contents = response.continuation_contents as unknown as
    | { type: string; items?: unknown[]; contents?: unknown[]; continuation?: string | null }
    | undefined;
  const section =
    contents?.type === 'GridContinuation'
      ? { type: 'Grid', items: contents.items ?? [] }
      : { type: 'MusicShelf', contents: contents?.contents ?? [] };
  const next = contents?.continuation || null;
  return {
    data: mapBrowse({ sections: [section] }, title),
    next: next ? () => orEnd(() => libraryContinuation(next, title), emptyPage(title)) : null,
  };
}

export async function fetchLibraryPlaylists(): Promise<MusicItem[]> {
  const { data } = await fetchLibrary('playlists');
  return data.grid.filter((item) => item.kind === 'playlist');
}

export type LikeStatus = 'LIKE' | 'DISLIKE' | 'INDIFFERENT';

export async function fetchLikeStatus(videoId: string): Promise<LikeStatus> {
  const yt = await getSignedInnertube();
  const response = await yt.actions.execute('/next', { videoId, client: 'YTMUSIC' });
  const button = collect((response.data as Raw).playerOverlays, 'likeButtonRenderer')[0];
  const status = button?.likeStatus;
  return status === 'LIKE' || status === 'DISLIKE' ? status : 'INDIFFERENT';
}

export async function setTrackLiked(videoId: string, liked: boolean): Promise<void> {
  const yt = await getSignedInnertube();
  await yt.actions.execute(liked ? '/like/like' : '/like/removelike', { target: { videoId }, client: 'YTMUSIC' });
}

export async function setListSaved(playlistId: string, saved: boolean): Promise<void> {
  const yt = await getSignedInnertube();
  await yt.actions.execute(saved ? '/like/like' : '/like/removelike', { target: { playlistId }, client: 'YTMUSIC' });
}

export interface PlaylistOption {
  playlistId: string;
  title: string;
}

export async function fetchPlaylistOptions(videoId: string): Promise<PlaylistOption[]> {
  const yt = await getSignedInnertube();
  const response = await yt.actions.execute('/playlist/get_add_to_playlist', { videoIds: [videoId], client: 'YTMUSIC' });
  return collect(response.data, 'playlistAddToOptionRenderer')
    .map((option) => ({
      playlistId: typeof option.playlistId === 'string' ? option.playlistId : '',
      title: runsText(option.title),
    }))
    .filter((option) => option.playlistId && option.playlistId !== LIKED_MUSIC_PLAYLIST && option.title);
}

export async function addToPlaylist(playlistId: string, videoId: string): Promise<void> {
  const yt = await getSignedInnertube();
  await yt.actions.execute('/browse/edit_playlist', {
    playlistId: playlistId.replace(/^VL/, ''),
    actions: [{ action: 'ACTION_ADD_VIDEO', addedVideoId: videoId, dedupeOption: 'DEDUPE_OPTION_SKIP' }],
    client: 'YTMUSIC',
  });
}

export async function createPlaylist(title: string, videoIds: string[], description = ''): Promise<string> {
  const yt = await getSignedInnertube();
  const response = await yt.actions.execute('/playlist/create', {
    title,
    description,
    privacyStatus: 'PRIVATE',
    videoIds,
    client: 'YTMUSIC',
  });
  const id = (response.data as { playlistId?: string } | undefined)?.playlistId;
  if (!id) throw new Error('YouTube Music did not make the playlist');
  return id;
}

function isMusicVideo(item: YTNodes.PlaylistPanelVideo): boolean {
  const type = (item.endpoint?.payload as { watchEndpointMusicSupportedConfigs?: { watchEndpointMusicConfig?: { musicVideoType?: string } } } | undefined)
    ?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType;
  return type !== undefined ? type !== 'MUSIC_VIDEO_TYPE_ATV' : !item.album;
}

export interface Radio {
  candidates: Candidate[];
  seedIsVideo: boolean;
}

export async function fetchRadio(videoId: string): Promise<Radio> {
  const yt = await getMusicClient();
  const panel = await yt.music.getUpNext(videoId, true);
  const rows = panel.contents as unknown as Raw[];
  const seedCut = rows.flatMap(panelCuts).find((cut) => cut.video_id === videoId);
  const seedIsVideo = seedCut ? isMusicVideo(seedCut) : false;
  return {
    candidates: rows.map((raw) => panelCandidate(raw, seedIsVideo)).filter((c): c is Candidate => c !== null),
    seedIsVideo,
  };
}

function panelCuts(raw: Raw): YTNodes.PlaylistPanelVideo[] {
  const cuts = (raw.type === 'PlaylistPanelVideoWrapper'
    ? [raw.primary, ...((raw.counterpart as unknown[] | undefined) ?? [])]
    : [raw]) as (YTNodes.PlaylistPanelVideo | null | undefined)[];
  return cuts.filter((c): c is YTNodes.PlaylistPanelVideo => c?.type === 'PlaylistPanelVideo');
}

function panelCandidate(raw: Raw, wantVideo = false): Candidate | null {
  const panelVideos = panelCuts(raw);
  const item = panelVideos.find((c) => isMusicVideo(c) === wantVideo) ?? panelVideos[0];
  if (!item?.video_id) return null;
  const thumbs = [...(item.thumbnail ?? [])].sort((a, b) => b.width - a.width);
  return {
    track: {
      videoId: item.video_id,
      title: item.title?.toString() ?? '',
      artists: (item.artists ?? []).map((a) => ({ name: a.name, id: a.channel_id ?? null })),
      album: item.album ? { name: item.album.name, id: item.album.id ?? null } : null,
      durationSec: item.duration?.seconds || null,
      explicit: (item.badges ?? []).some((b) => /explicit/i.test(JSON.stringify(b))),
      thumbnail: thumbs[0]?.url ?? null,
      index: null,
      plays: null,
    },
    isVideo: isMusicVideo(item),
  };
}

export async function fetchTrackLinks(videoId: string): Promise<Track | null> {
  const yt = await getMusicClient();
  const panel = await yt.music.getUpNext(videoId, false);
  const row = (panel.contents as unknown as Raw[]).find((raw) => panelCuts(raw).some((cut) => cut.video_id === videoId));
  return (row && panelCandidate(row)?.track) ?? null;
}

export async function findArtistPageId(name: string): Promise<string | null> {
  if (!normalizedArtistName(name)) return null;
  const results = await fetchSearch(name);
  return [...results.top, ...results.artists].find((item) => item.kind === 'artist' && isExactArtistMatch(item.title, name))?.id ?? null;
}
