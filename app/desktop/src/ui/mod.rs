mod account;
mod account_settings;
mod actions;
mod appearance_settings;
mod collections;
mod dev;
mod equalizer_settings;
mod dialogs;
mod glyphs;
mod integrations;
mod jam;
mod karaoke;
mod library_pages;
mod lists;
mod lyric_prefs;
mod lyrics_settings;
mod mini;
mod navigation;
mod network_settings;
mod notes;
mod nowplaying;
mod pages;
mod collection_pages;
mod feed_pages;
mod frame;
mod search_pages;
mod settings_pages;
mod playback;
mod playback_settings;
mod queue;
mod radio;
mod responses;
mod shell;
mod titlebar;
pub use titlebar::{custom as custom_titlebar, set_hwnd as set_titlebar_hwnd};
mod transfer;
mod widgets;
mod persistence;
mod app_motion;
mod app_init;
mod app_appearance;
mod app_notifications;
mod app_playback_state;
mod app_selection;
mod window;

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use crossbeam_channel::Receiver;
use egui::{Color32, Ui};
use souvlaki::MediaControlEvent;

use crate::audio::{AudioEvent, AudioHandle};
use crate::backend::{ArtistPage, Backend, Explore, Library, Req, Resp, SearchResults};
use crate::lyrics::LyricsSet;
use crate::media::Media;
use crate::model::{Card, Chip, Shelf, Target, Track};
use crate::shader::Background;
use crate::theme;
use lists::{LIKED_MUSIC, Lists};
use queue::Queue;
use dev::{dev_pace, dev_screenshot, dev_var};
use dialogs::text_field;
use persistence::{AccountCache, Session, SESSION_KEY};
use app_motion::{MotionView, VideoState};
use app_playback_state::{MixFrom, PlayingAs};
use window::{fit_window_to_monitor, relaunch, smooth_wheel};
pub use app_selection::Selection;
pub use queue::Repeat;

#[derive(Clone, PartialEq, Hash, Debug)]
pub enum Page {
    Home,
    New,
    Library(LibTab),
    Search(String),
    Mood { browse_id: String, params: Option<String>, title: String },
    Radio,
    Downloads,
    Album(String),
    Playlist(String),
    Artist(String),
    Settings,
    Setting(SettingsPane),
    Transfer,
    Jam,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum SettingsPane {
    Account,
    Playback,
    Equalizer,
    Downloads,
    Lyrics,
    Artists,
    Appearance,
    Window,
    Network,
    Import,
    Shortcuts,
}

#[derive(Clone, Copy, PartialEq, Hash, Debug)]
pub enum LibTab {
    Playlists,
    Songs,
    Albums,
    History,
    Uploads,
}

#[derive(Clone, Copy, PartialEq)]
pub enum NpTab {
    Lyrics,
    UpNext,
    Art,
}

pub enum Action {
    Play(Vec<Track>, usize),
    PlayCollection(Target, bool),
    Open(Target),
    PlayNext(Track),
    Enqueue(Track),
    Radio(Track),
    Go(Page),
    QueueJump(usize),
    QueueRemove(usize),
    ViewQueue,
    Seek(f32),
    Like(Track, bool),
    LikeMany(Vec<Track>, bool),
    Dislike(Track),
    Share(Target),
    AddToPlaylist(Vec<Track>),
    RemoveFromPlaylist(Vec<usize>),
    Download(Track),
    DownloadAll(String, Vec<Target>),
    NewPlaylist(Vec<Track>),
    EditPlaylist,
    Collaborate,
    DeletePlaylist,
    SaveCollection(String, bool),
    OpenNowPlaying(bool),
    Mini(bool),
    TogglePlay,
    RevertQuality,
    UpgradeQuality,
    TransferPick(Track),
}

enum Dialog {
    PickPlaylist(Vec<Track>),
    NewPlaylist { title: String, tracks: Vec<Track> },
    EditPlaylist { id: String, title: String, description: String, privacy: Option<String> },
    ConfirmDelete { id: String, title: String },
    Collaborate { id: String, info: Option<crate::ytm::Collaboration>, busy: bool, confirm_off: bool },
}

pub struct HomeState {
    pub chips: Vec<Chip>,
    pub shelves: Vec<Shelf>,
    pub continuation: Option<String>,
    pub loading_more: Option<f64>,
    pub picks_loading: bool,
    pub picks_expanded: bool,
}

pub struct Channels {
    pub resp_rx: Receiver<Resp>,
    pub audio_rx: Receiver<AudioEvent>,
    pub media_rx: Receiver<MediaControlEvent>,
}

type ProxyTest = Option<Option<(bool, String)>>;

#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub struct RecentSearch {
    pub query: String,
    #[serde(default)]
    pub target: Option<Target>,
    pub title: String,
    pub subtitle: String,
    pub thumb: Option<String>,
    pub round: bool,
}

pub struct App {
    backend: Backend,
    audio: AudioHandle,
    ch: Channels,
    media: Option<Media>,
    discord: Option<crate::discord::Discord>,
    discord_enabled: bool,
    discord_sent: Option<crate::discord::Presence>,
    lastfm_user: Option<String>,
    lastfm_busy: Arc<AtomicBool>,
    lastfm_result: Arc<std::sync::Mutex<Option<Result<String, String>>>>,
    scrobble: Option<(u64, bool)>,
    resume_at: Option<Duration>,
    resume_counts: bool,
    listened_at: u64,
    queue_checked: Option<f64>,
    was_focused: bool,
    queue_source: Option<Target>,
    play_pending: Option<(Target, bool)>,
    bg: Option<Background>,

    page: Page,
    back: Vec<Page>,
    forward: Vec<Page>,
    page_changed: f64,
    loading: bool,
    load_failed: bool,
    search_text: String,
    search_synced: String,
    recent_searches: Vec<RecentSearch>,
    search_pending: bool,
    suggestions: (String, Vec<crate::ytm::Suggestion>),
    suggest_cache: VecDeque<(String, Vec<crate::ytm::Suggestion>)>,
    suggest_sel: Option<usize>,
    suggest_round: u64,
    forgets_pending: u32,
    suggest_due: Option<f64>,

    home: Option<HomeState>,
    home_loading: bool,
    explore: Option<Explore>,
    search: Option<SearchResults>,
    lists: Lists,
    artist: Option<ArtistPage>,
    library: Option<Library>,
    library_requested: std::time::Instant,
    history: Option<Vec<Track>>,
    uploads: Option<Vec<Track>>,
    picked_uploads: Arc<std::sync::Mutex<Option<Vec<std::path::PathBuf>>>>,
    liked: HashSet<String>,
    unliking: HashMap<String, Track>,
    selection: Selection,
    account_name: Option<String>,
    account_avatar: Option<String>,
    channels: Option<crate::backend::Channels>,
    channels_asked: Option<f64>,
    switching_to: Option<String>,
    sidebar_cache: Option<Vec<Card>>,

    queue: Queue,
    generation: u64,
    buffering: bool,
    skip_streak: u32,
    recoveries: u32,
    autoplay: bool,
    normalize: bool,
    spatial: bool,
    eq: crate::eq::Prefs,
    log_volume: bool,
    high_quality: bool,
    play_songs: bool,
    playing_as: Option<PlayingAs>,
    version_alternate: Option<(String, Track)>,
    version_positions: std::collections::HashMap<String, std::time::Duration>,
    output_device: Option<(String, String)>,
    output_devices: Vec<crate::audio::OutputDevice>,
    radio_pending: bool,
    seek_drag: Option<f32>,

    art_key: Option<String>,
    art_changed: f64,
    apple_artists: std::collections::HashMap<String, Option<crate::apple::Artwork>>,
    apple_covers: std::collections::HashMap<String, Option<String>>,
    song_covers: std::collections::HashMap<String, Option<Track>>,
    video: Option<(String, Option<VideoState>)>,
    next_as: Option<Track>,
    video_of: Option<(String, Option<Track>)>,
    video_wanted: bool,
    pub video_height: u32,
    pub load_both: bool,
    apple_missed: std::collections::HashMap<String, std::time::Instant>,
    apple_motion: std::collections::HashMap<String, MotionView>,
    apple_waiting: std::collections::HashSet<String>,
    signature_api: String,
    signature_tex: std::collections::HashMap<String, egui::TextureHandle>,
    signature_images: std::collections::HashMap<String, (usize, usize, Vec<u8>)>,
    signature_missed: std::collections::HashMap<String, std::time::Instant>,
    signature_waiting: std::collections::HashSet<String>,
    hires_sources: Vec<String>,
    hires_adding: bool,
    hires_draft: String,
    hires_focus: bool,
    prefer_atmos: bool,
    audio_info: Option<(u64, crate::hires::Info)>,
    accent: Color32,
    theme_id: String,
    chrome_applied: Option<String>,
    appearance: theme::Appearance,
    anim_bg: bool,
    anim_canvas: bool,
    lastfm_now_playing: bool,
    lastfm_primary_only: bool,
    pub(super) reduce_motion: bool,
    pub(super) hide_quick_picks: bool,
    pub(super) hide_home_videos: bool,
    reactive_bg: bool,
    sponsorblock: bool,
    crossfade: bool,
    automix: bool,
    crossfade_secs: u32,
    handoff: bool,
    pending_mix: Option<MixFrom>,
    mix_from: Option<MixFrom>,
    segments: Option<(String, Vec<crate::sponsorblock::Segment>)>,
    segment_skipped: f64,
    bg_time: f64,
    last_frame: f64,
    search_rect: Option<egui::Rect>,
    search_focused: bool,
    search_refocus: bool,
    forget_held: bool,
    forgetting: Option<(String, f64)>,
    wheel_pending: egui::Vec2,
    wheel_mods: egui::Modifiers,

    np_open: bool,
    np_tab: NpTab,
    queue_open: bool,
    lyrics_open: bool,
    fullscreen: bool,
    mini: mini::Mini,
    lyrics: Option<(String, LyricsSet)>,
    lyrics_extras: Option<(String, String, String, crate::translate::Extras)>,
    extras_asked: Option<(String, String, String)>,
    show_roman: bool,
    show_trans: bool,
    lyric_prefs: lyric_prefs::Prefs,
    lyric_view: karaoke::View,
    lyric_clock: lyric_prefs::Clock,
    record_angle: f64,

    logged_in: bool,
    transfer: Arc<crate::transfer::Transfer>,
    xfer: transfer::Setup,
    jam: jam::Session,
    signing_in: Arc<AtomicBool>,
    toast: Option<(String, f64)>,

    updater: Option<crate::update::Updater>,
    update_checked: Option<f64>,
    update_announced: bool,
    notes_dialog: Option<notes::NotesDialog>,
    whats_new: Option<String>,
    restart_for_update: bool,
    proxy_saved: crate::proxy::Setting,
    proxy_mode: crate::proxy::Setting,
    proxy_url: String,
    proxy_error: Option<String>,
    proxy_test: Arc<std::sync::Mutex<ProxyTest>>,
    restart_app: bool,
    media_cover: Arc<std::sync::Mutex<Option<(String, String)>>>,

    close_to_tray: bool,
    pub(super) classic_search: bool,
    pub(super) youtube_search: bool,
    pub(super) search_youtube: bool,
    pub(super) youtube_results: Option<(String, Result<Vec<crate::model::Track>, String>)>,
    pub(super) youtube_asked: String,
    pub(super) hide_radio: bool,
    pub(super) hide_podcasts: bool,
    pub(super) tab_intros_shown: [bool; 2],
    pub(super) moods: Option<Vec<crate::model::MoodSection>>,
    moods_asked: bool,
    pub(super) mood: Option<crate::model::MoodPage>,
    pub(super) radio: radio::RadioState,
    pub(super) wgpu_renderer: bool,
    app_icon: &'static str,
    tray: Option<crate::tray::Tray>,
    #[cfg_attr(not(windows), allow(dead_code))]
    tray_registered_at: Option<std::time::Instant>,
    show_asserts: u8,
    started_at: std::time::Instant,
    exiting: bool,
    window_hidden: bool,
    good_window: Option<String>,
    sidebar_collapsed: bool,
    playlist_order: Vec<String>,
    hidden_playlists: HashSet<String>,
    coll_search: Option<(String, String)>,
    actions: Vec<Action>,
    dialog: Option<Dialog>,
    share_pending: Option<String>,
    smoke: Option<String>,
}

pub(super) const CROSSFADE_MAX: u32 = 12;

pub fn shuffle<T>(v: &mut [T]) {
    let mut x = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0x9e37_79b9_7f4a_7c15, |d| d.as_nanos() as u64)
        | 1;
    for i in (1..v.len()).rev() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        v.swap(i, (x % (i as u64 + 1)) as usize);
    }
}

fn updatable() -> bool {
    static UPDATABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *UPDATABLE.get_or_init(|| crate::update::installed() || crate::update::portable_dir().is_some())
}

pub fn fmt_time(secs: u32) -> String {
    format!("{}:{:02}", secs / 60, secs % 60)
}
