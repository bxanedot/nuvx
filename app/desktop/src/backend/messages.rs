use std::path::PathBuf;

use crate::art::Art;
use crate::lyrics::LyricsSet;
use crate::model::{self, Card, Target, Thumb, Track};
use crate::ytm;

pub enum Req {
    Home(Option<String>),
    HomeMore(String),
    MoreQuickPicks(String),
    Explore(Vec<String>),
    Moods,
    Mood { browse_id: String, params: Option<String> },
    RadioCountries,
    RadioStations { key: String, country: Option<String>, query: Option<String> },
    RadioClick(String),
    Search(String),
    YoutubeSearch(String),
    Suggest { query: String, round: u64 },
    ForgetSearch(String),
    Album { id: String, load: u64 },
    Artist(String),
    Playlist { id: String, load: u64, stream: bool },
    Library,
    History,
    Uploads,
    Account,
    Channels,
    SwitchChannel { page_id: Option<String>, epoch: u64 },
    ServerQueue,
    Upload(Vec<PathBuf>),
    Download { track: Track, for_video: Option<String> },
    DownloadAll { name: String, targets: Vec<model::Target> },
    Lyrics(Track),
    Segments { video_id: String },
    LyricsExtras { video_id: String, source: String, lines: Vec<String>, lang: Option<String>, target: String },
    Art { key: String, url: String },
    AppleArtist { id: String, name: String },
    SongCover { track: Track },
    Canvas { key: String, album: String, artist: String, title: Option<String> },
    Video { id: String, height: u32 },
    VideoOf { generation: u64, track: Track },
    ArtistSignature { key: String, endpoint: String, name: String },
    Radio { seed: String, song_of: Option<Track> },
    PlayCollection { target: Target },
    Play { video_id: String, generation: u64, at: std::time::Duration, report: bool, start_paused: bool, song_of: Option<Track>, song: Option<Track>, track: Option<Track> },
    Prefetch { video_id: String, song_of: Option<Track>, track: Option<Track> },
    Like { video_id: String, like: bool },
    LikeMany { video_ids: Vec<String>, like: bool },
    Dislike(String),
    CreatePlaylist { title: String, video_ids: Vec<String> },
    EditPlaylist { id: String, title: String, description: String, privacy: Option<String> },
    AddToPlaylist { playlist_id: String, video_ids: Vec<String> },
    RemoveFromPlaylist { playlist_id: String, items: Vec<(String, String)> },
    DeletePlaylist(String),
    Collaboration(String),
    SetCollaboration { info: ytm::Collaboration, change: ytm::CollabChange },
    SaveCollection { id: String, save: bool },
    SetCookie(String),
    Logout,
}

impl Req {
    pub(super) fn name(&self) -> &'static str {
        match self {
            Req::Home(_) | Req::HomeMore(_) => "Loading home",
            Req::MoreQuickPicks(_) => "Loading more picks",
            Req::Explore(_) => "Loading explore",
            Req::Moods => "Loading moods",
            Req::Mood { .. } => "Loading the mood",
            Req::RadioCountries | Req::RadioStations { .. } => "Loading radio",
            Req::RadioClick(_) => "Radio",
            Req::Search(_) | Req::YoutubeSearch(_) => "Search",
            Req::Suggest { .. } => "Suggest",
            Req::ForgetSearch(_) => "Removing the search",
            Req::Album { .. } => "Loading album",
            Req::Artist(_) => "Loading artist",
            Req::Playlist { .. } => "Loading playlist",
            Req::Library => "Loading library",
            Req::History => "Loading history",
            Req::Uploads => "Loading uploads",
            Req::Account => "Account",
            Req::Channels => "Channels",
            Req::SwitchChannel { .. } => "Switching channel",
            Req::ServerQueue => "Queue from other devices",
            Req::Upload(_) => "Upload",
            Req::Download { .. } | Req::DownloadAll { .. } => "Download",
            Req::Lyrics(_) => "Lyrics",
            Req::Segments { .. } => "SponsorBlock",
            Req::LyricsExtras { .. } => "Translation",
            Req::Art { .. } => "Art",
            Req::AppleArtist { .. } => "Apple artist",
            Req::Canvas { .. } => "Canvas",
            Req::Video { .. } => "Video",
            Req::VideoOf { .. } => "Finding the video",
            Req::SongCover { .. } => "Cover",
            Req::ArtistSignature { .. } => "Artist signature",
            Req::Radio { .. } => "Loading radio",
            Req::PlayCollection { .. } => "Playback",
            Req::Play { .. } => "Playback",
            Req::Prefetch { .. } => "Prefetch",
            Req::Like { .. } | Req::LikeMany { .. } => "Like",
            Req::Dislike(_) => "Dislike",
            Req::CreatePlaylist { .. } => "Create playlist",
            Req::EditPlaylist { .. } => "Edit playlist",
            Req::AddToPlaylist { .. } => "Add to playlist",
            Req::RemoveFromPlaylist { .. } => "Remove from playlist",
            Req::DeletePlaylist(_) => "Delete playlist",
            Req::Collaboration(_) => "Loading collaboration",
            Req::SetCollaboration { .. } => "Collaboration",
            Req::SaveCollection { .. } => "Save to library",
            Req::SetCookie(_) => "Sign in",
            Req::Logout => "Sign out",
        }
    }

    pub(super) fn quiet(&self) -> bool {
        matches!(self, Req::Suggest { .. } | Req::Moods | Req::RadioClick(_) | Req::Account | Req::Channels | Req::ServerQueue | Req::Art { .. } | Req::AppleArtist { .. } | Req::Canvas { .. } | Req::Video { .. } | Req::SongCover { .. } | Req::ArtistSignature { .. } | Req::Prefetch { .. } | Req::HomeMore(_) | Req::Lyrics(_) | Req::Segments { .. } | Req::LyricsExtras { .. })
    }
}

pub struct Explore {
    pub new_albums: Vec<Card>,
    pub charts: Vec<Card>,
    pub top: Vec<Track>,
}

pub struct SearchResults {
    pub query: String,
    pub tracks: Vec<Track>,
    pub albums: Vec<Card>,
    pub artists: Vec<Card>,
    pub playlists: Vec<Card>,
}

pub struct Collection {
    pub id: String,
    pub is_album: bool,
    pub save_id: Option<String>,
    pub title: String,
    pub subtitle: String,
    pub artists: Vec<model::Link>,
    pub description: Option<String>,
    pub thumbs: Vec<Thumb>,
    pub tracks: Vec<Track>,
    pub set_ids: Vec<Option<String>>,
    pub count: Option<u64>,
    pub owned: bool,
}

impl Collection {
    pub fn byline(&self) -> String {
        match self.count {
            Some(n) if self.subtitle.is_empty() => format!("{n} songs"),
            Some(n) => format!("{} • {n} songs", self.subtitle),
            None => self.subtitle.clone(),
        }
    }

    pub fn remove_rows(&mut self, rows: &[usize]) {
        for &i in rows.iter().rev() {
            if i < self.tracks.len() {
                self.tracks.remove(i);
                if i < self.set_ids.len() {
                    self.set_ids.remove(i);
                }
                self.count = self.count.map(|n| n.saturating_sub(1));
            }
        }
    }
}

pub struct ArtistPage {
    pub id: String,
    pub name: String,
    pub banner: Vec<Thumb>,
    pub subscribers: Option<u64>,
    pub description: Option<String>,
    pub top: Vec<Track>,
    pub top_playlist: Option<String>,
    pub albums: Vec<Card>,
    pub playlists: Vec<Card>,
    pub similar: Vec<Card>,
}

pub struct Library {
    pub playlists: Vec<Card>,
    pub albums: Vec<Card>,
    pub playlists_failed: bool,
    pub albums_failed: bool,
}

pub struct Channels {
    pub list: Vec<ytm::Channel>,
    pub current: Option<String>,
    pub epoch: u64,
}

pub enum Resp {
    Home(ytm::HomePage),
    HomeMore(ytm::HomePage),
    QuickPicksMore { seed: String, tracks: Vec<Track> },
    Explore(Explore),
    Moods(Vec<model::MoodSection>),
    Mood(model::MoodPage),
    RadioCountries(Result<Vec<crate::radio::Country>, String>),
    RadioStations { key: String, stations: Result<Vec<crate::radio::Station>, String> },
    Search(SearchResults),
    YoutubeSearch { query: String, tracks: Result<Vec<Track>, String> },
    Suggest { query: String, round: u64, terms: Vec<ytm::Suggestion> },
    Collection { load: u64, c: Collection, more: bool },
    CollectionMore { id: String, load: u64, tracks: Vec<Track>, set_ids: Vec<Option<String>>, more: bool },
    Artist(ArtistPage),
    Library(Library),
    History(Vec<Track>),
    Uploads(Vec<Track>),
    Account { name: Option<String>, photo: Option<String> },
    Channels(Channels),
    SwitchedChannel { page_id: Option<String>, epoch: u64, error: Option<String> },
    ServerQueue(ytm::ServerQueue),
    Uploaded { done: usize, failed: Vec<String> },
    Downloaded { title: String, path: PathBuf },
    DownloadedAll { name: String, done: usize, failed: usize },
    Lyrics { video_id: String, lyrics: LyricsSet },
    Segments { video_id: String, segments: Vec<crate::sponsorblock::Segment> },
    LyricsExtras { video_id: String, source: String, target: String, extras: crate::translate::Extras },
    Art { key: String, art: Art },
    AppleArtist { id: String, info: Option<crate::apple::Artwork> },
    SongCover { id: String, song: Option<Track> },
    Canvas { key: String, url: Option<String> },
    Video { id: String, path: Option<PathBuf> },
    VideoOf { generation: u64, listed: String, video: Option<Track> },
    ArtistSignature { key: String, signature: Option<crate::artist_signature::Signature> },
    Radio { seed: String, tracks: Vec<Track> },
    PlayQueue { target: Target, tracks: Vec<Track> },
    Liked { video_id: String, like: bool },
    LikeProgress { taken: Vec<String>, done: usize, total: usize, like: bool },
    LikedMany { video_ids: Vec<String>, failed: Vec<String>, like: bool },
    PlaylistCreated { id: String, songs: usize },
    PlaylistChanged { id: String, message: &'static str },
    Collaboration(ytm::Collaboration),
    PlayError { generation: u64, msg: String },
    AudioInfo { generation: u64, info: crate::hires::Info },
    PlayingAs { generation: u64, listed: String, song: Option<Track> },
    LoggedIn(bool),
    SearchForgotten,
    Error(String),
}

impl Resp {
    pub(super) fn is_account_scoped(&self) -> bool {
        matches!(
            self,
            Resp::Home(_)
                | Resp::HomeMore(_)
                | Resp::QuickPicksMore { .. }
                | Resp::Suggest { .. }
                | Resp::Library(_)
                | Resp::History(_)
                | Resp::Uploads(_)
                | Resp::Account { .. }
                | Resp::Channels(_)
                | Resp::ServerQueue(_)
                | Resp::Liked { .. }
                | Resp::LikeProgress { .. }
                | Resp::LikedMany { .. }
        )
    }

    pub(super) fn ended(id: String, load: u64) -> Self {
        Resp::CollectionMore { id, load, tracks: Vec::new(), set_ids: Vec::new(), more: false }
    }
}
