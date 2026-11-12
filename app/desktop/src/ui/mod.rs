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

#[derive(Default)]
pub struct Selection {
    pub page: Option<Page>,
    pub rows: std::collections::BTreeSet<usize>,
    pub anchor: Option<usize>,
    pub active: bool,
}

impl Selection {
    pub fn on(&self, page: &Page) -> bool {
        self.page.as_ref() == Some(page) && (self.active || !self.rows.is_empty())
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }

    pub fn start(&mut self, page: &Page) {
        *self = Self { page: Some(page.clone()), active: true, ..Self::default() };
    }

    pub fn pick(&mut self, page: &Page, i: usize, range: bool) {
        if self.page.as_ref() != Some(page) {
            *self = Self { page: Some(page.clone()), ..Self::default() };
        }
        match self.anchor.filter(|_| range) {
            Some(a) => self.rows.extend(a.min(i)..=a.max(i)),
            None => {
                if !self.rows.remove(&i) {
                    self.rows.insert(i);
                }
                self.anchor = Some(i);
            }
        }
    }
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

pub struct MotionView {
    motion: std::sync::Arc<crate::motion::Motion>,
    tex: Option<egui::TextureHandle>,
    seen: u64,
    width: u32,
}

impl MotionView {
    fn new(motion: std::sync::Arc<crate::motion::Motion>, width: u32) -> Self {
        Self { motion, tex: None, seen: 0, width }
    }

    fn texture(&mut self, ctx: &egui::Context) -> Option<&egui::TextureHandle> {
        if let Some((version, frame)) = self.motion.latest()
            && version != self.seen
        {
            self.seen = version;
            let image = egui::ColorImage::from_rgba_unmultiplied([frame.w, frame.h], &frame.rgba);
            match &mut self.tex {
                Some(tex) => tex.set(image, egui::TextureOptions::LINEAR),
                None => self.tex = Some(ctx.load_texture("apple-motion", image, egui::TextureOptions::LINEAR)),
            }
        }
        self.tex.as_ref()
    }
}

pub enum VideoState {
    Saved(std::path::PathBuf),
    Playing(MotionView),
    Failed,
}

impl Drop for MotionView {
    fn drop(&mut self) {
        self.motion.stop();
    }
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

pub(super) struct MixFrom {
    pub(super) track: Track,
    pub(super) total: f32,
    pub(super) mix: f32,
    pub(super) started: bool,
    pub(super) released: bool,
}

impl App {
    pub fn new(
        backend: Backend,
        audio: AudioHandle,
        ch: Channels,
        media: Option<Media>,
        bg: Option<Background>,
        no_tray: bool,
        storage: Option<&dyn eframe::Storage>,
    ) -> Self {
        let get = |k: &str| storage.and_then(|s| s.get_string(k));
        titlebar::set_mac(get("window_buttons").is_none_or(|v| v == "mac"));
        theme::set_reduce_motion(get("reduce_motion").is_some_and(|v| v == "1"));
        theme::set_font_choice(&dev_var("NUVX_FONT").map(str::to_owned).or_else(|| get("font")).unwrap_or_default());
        let discord_enabled = get("discord_enabled").is_none_or(|v| v == "1");
        let app_icon = crate::appicon::load(backend.data_dir());
        let wgpu_renderer = crate::renderer::load(backend.data_dir()) == crate::renderer::Renderer::Wgpu;
        crate::appicon::sync_shortcuts(app_icon, backend.data_dir());
        crate::downloads::init(backend.data_dir(), &get("download_dir").unwrap_or_default());
        crate::lastfm::init(backend.data_dir());
        let logged_in = backend.is_logged_in();
        backend.set_high_quality(get("high_quality").is_none_or(|v| v == "1"));
        let hires_source = get("hires_source").unwrap_or_default();
        let hires_sources: Vec<String> = hires_source.lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_owned).collect();
        backend.set_hires_source(&hires_sources.join("\n"));
        let prefer_atmos = get("prefer_atmos").is_none_or(|v| v == "1");
        backend.set_prefer_atmos(prefer_atmos);
        backend.request(Req::Home(None));
        if logged_in {
            backend.request(Req::Library);
            backend.request(Req::Account);
            backend.warm_signed_in();
        }
        let transfer_handle = {
            let b = backend.clone();
            crate::transfer::Transfer::new(backend.data_dir(), Arc::new(move || b.wake()))
        };
        let mut app = Self {
            backend,
            audio,
            ch,
            media,
            discord: discord_enabled.then(crate::discord::Discord::spawn).flatten(),
            discord_enabled,
            discord_sent: None,
            lastfm_user: crate::lastfm::user(),
            lastfm_busy: Default::default(),
            lastfm_result: Default::default(),
            scrobble: None,
            resume_at: None,
            resume_counts: false,
            listened_at: 0,
            queue_checked: None,
            was_focused: false,
            queue_source: None,
            play_pending: None,
            bg,
            page: Page::Home,
            back: Vec::new(),
            forward: Vec::new(),
            page_changed: 0.0,
            loading: false,
            load_failed: false,
            search_text: String::new(),
            search_synced: String::new(),
            recent_searches: get("recent_searches").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default(),
            search_pending: false,
            suggestions: Default::default(),
            suggest_cache: VecDeque::new(),
            suggest_sel: None,
            suggest_round: 0,
            forgets_pending: 0,
            suggest_due: None,
            home: None,
            home_loading: true,
            explore: None,
            search: None,
            lists: Lists::default(),
            artist: None,
            library: None,
            library_requested: std::time::Instant::now(),
            history: None,
            uploads: None,
            picked_uploads: Default::default(),
            liked: HashSet::new(),
            unliking: HashMap::new(),
            selection: Selection::default(),
            account_name: None,
            account_avatar: None,
            channels: None,
            channels_asked: None,
            switching_to: None,
            sidebar_cache: None,
            queue: Queue::new(match get("repeat").as_deref() {
                Some("all") => Repeat::All,
                Some("one") => Repeat::One,
                _ => Repeat::Off,
            }),
            generation: 0,
            buffering: false,
            skip_streak: 0,
            recoveries: 0,
            autoplay: get("autoplay").is_none_or(|v| v == "1"),
            normalize: get("normalize").is_none_or(|v| v == "1"),
            spatial: get("spatial_audio").is_none_or(|v| v == "1"),
            eq: get("equalizer").and_then(|v| serde_json::from_str(&v).ok()).unwrap_or_default(),
            log_volume: get("log_volume").is_none_or(|v| v == "1"),
            high_quality: get("high_quality").is_none_or(|v| v == "1"),
            play_songs: get("play_songs").is_none_or(|v| v == "1"),
            playing_as: None,
            output_device: get("output_device").filter(|d| !d.is_empty()).map(|id| (id, get("output_device_name").unwrap_or_default())),
            output_devices: Vec::new(),
            updater: None,
            close_to_tray: !no_tray && get("close_to_tray").is_some_and(|v| v == "1"),
            classic_search: get("classic_search").is_some_and(|v| v == "1"),
            youtube_search: get("youtube_search").is_some_and(|v| v == "1"),
            search_youtube: false,
            youtube_results: None,
            youtube_asked: String::new(),
            hide_radio: get("hide_radio_tab").is_some_and(|v| v == "1"),
            hide_podcasts: get("hide_podcasts_tab").is_some_and(|v| v == "1"),
            tab_intros_shown: [get("radio_intro_shown").is_some_and(|v| v == "1"), get("podcasts_intro_shown").is_some_and(|v| v == "1")],
            moods: None,
            moods_asked: false,
            mood: None,
            radio: Default::default(),
            wgpu_renderer,
            app_icon,
            tray: None,
            tray_registered_at: None,
            show_asserts: 0,
            started_at: std::time::Instant::now(),
            exiting: false,
            window_hidden: false,
            good_window: get("window"),
            sidebar_collapsed: get("sidebar_collapsed").is_some_and(|v| v == "1"),
            playlist_order: get("playlist_order").map(|v| v.lines().filter(|l| !l.is_empty()).map(str::to_owned).collect()).unwrap_or_default(),
            hidden_playlists: get("hidden_playlists").map(|v| v.lines().filter(|l| !l.is_empty()).map(str::to_owned).collect()).unwrap_or_default(),
            coll_search: None,
            update_checked: None,
            update_announced: false,
            notes_dialog: None,
            whats_new: {
                let current = crate::update::current_version();
                let last = get("last_version");
                (std::env::var_os("NUVX_WHATS_NEW").is_some() || last.is_some_and(|v| crate::update::newer(&current, &v))).then_some(current)
            },
            restart_for_update: false,
            proxy_saved: crate::proxy::active().clone(),
            proxy_mode: crate::proxy::active().clone(),
            proxy_url: match crate::proxy::active() {
                crate::proxy::Setting::Custom(u) => u.clone(),
                _ => String::new(),
            },
            proxy_error: None,
            proxy_test: Default::default(),
            restart_app: false,
            media_cover: Default::default(),
            radio_pending: false,
            seek_drag: None,
            art_key: None,
            apple_artists: Default::default(),
            apple_covers: Default::default(),
            song_covers: Default::default(),
            video: None,
            next_as: None,
            video_of: None,
            video_wanted: false,
            video_height: get("video_height").and_then(|v| v.parse().ok()).unwrap_or(480),
            load_both: get("load_both").is_some_and(|v| v == "1"),
            apple_missed: Default::default(),
            apple_motion: Default::default(),
            apple_waiting: Default::default(),
            signature_api: get("signature_api").unwrap_or_default(),
            signature_tex: Default::default(),
            signature_images: Default::default(),
            signature_missed: Default::default(),
            signature_waiting: Default::default(),
            hires_sources,
            hires_adding: false,
            hires_draft: String::new(),
            hires_focus: false,
            prefer_atmos,
            audio_info: None,
            art_changed: -10.0,
            accent: theme::RED,
            theme_id: crate::preset::DEFAULT_ID.to_owned(),
            chrome_applied: None,
            appearance: theme::Appearance::from_key(&dev_var("NUVX_APPEARANCE").map(str::to_owned).or_else(|| get("appearance")).unwrap_or_default()),
            anim_bg: get("anim_bg").is_none_or(|v| v == "1"),
            anim_canvas: get("anim_canvas").is_none_or(|v| v == "1"),
            lastfm_now_playing: get("lastfm_now_playing").is_none_or(|v| v == "1"),
            lastfm_primary_only: get("lastfm_primary_only").is_none_or(|v| v == "1"),
            reduce_motion: get("reduce_motion").is_some_and(|v| v == "1"),
            hide_quick_picks: get("hide_quick_picks").is_some_and(|v| v == "1"),
            hide_home_videos: get("hide_home_videos").is_some_and(|v| v == "1"),
            reactive_bg: get("reactive_bg").is_none_or(|v| v == "1"),
            sponsorblock: get("sponsorblock").is_some_and(|v| v == "1"),
            crossfade: get("crossfade").is_some_and(|v| v == "1"),
            automix: get("automix").is_some_and(|v| v == "1"),
            handoff: false,
            pending_mix: None,
            mix_from: None,
            crossfade_secs: get("crossfade_secs").and_then(|v| v.parse().ok()).unwrap_or(5).clamp(1, CROSSFADE_MAX),
            segments: None,
            segment_skipped: f64::NEG_INFINITY,
            bg_time: 0.0,
            last_frame: 0.0,
            search_rect: None,
            search_focused: false,
            search_refocus: false,
            forget_held: false,
            forgetting: None,
            wheel_pending: egui::Vec2::ZERO,
            wheel_mods: egui::Modifiers::NONE,
            np_open: false,
            np_tab: if get("np_art").is_some_and(|v| v == "1") { NpTab::Art } else { NpTab::Lyrics },
            queue_open: false,
            lyrics_open: false,
            fullscreen: false,
            mini: mini::Mini::load(get),
            lyrics: None,
            lyrics_extras: None,
            extras_asked: None,
            show_roman: get("show_roman").is_none_or(|v| v == "1"),
            show_trans: get("show_trans").is_none_or(|v| v == "1"),
            lyric_prefs: lyric_prefs::Prefs::load(&get),
            lyric_view: Default::default(),
            lyric_clock: Default::default(),
            record_angle: 0.0,
            logged_in,
            signing_in: Default::default(),
            transfer: transfer_handle,
            xfer: Default::default(),
            jam: Default::default(),
            toast: None,
            actions: Vec::new(),
            dialog: None,
            share_pending: None,
            smoke: None,
        };
        app.request_liked(false);
        if let Some(s) = get(SESSION_KEY).filter(|_| std::env::var_os("NUVX_SMOKE").is_none()) {
            app.restore_session(&s);
        }
        let smoke = std::env::var("NUVX_SMOKE").ok().filter(|q| !q.is_empty());
        if let Some(ids) = smoke.as_deref().and_then(|q| q.strip_prefix("id:")) {
            let track = |id: &str| Track { id: id.to_owned(), title: id.to_owned(), artists: vec![], album: None, duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false };
            app.play_list(ids.split(',').map(track).collect(), 0);
        } else if let Some(q) = smoke {
            app.go(Page::Search(q.clone()));
            app.smoke = Some(q);
        } else if let Some(q) = std::env::var("NUVX_SEARCH").ok().filter(|q| !q.is_empty()) {
            app.go(Page::Search(q));
        }
        match std::env::var("NUVX_PAGE").as_deref() {
            Ok("home") => app.go(Page::Home),
            Ok("new") | Ok("explore") => app.go(Page::New),
            Ok("search") => app.go(Page::Search(String::new())),
            Ok("library") => app.go(Page::Library(LibTab::Playlists)),
            Ok("uploads") => app.go(Page::Library(LibTab::Uploads)),
            Ok("liked") => app.go(Page::Library(LibTab::Songs)),
            Ok("history") => app.go(Page::Library(LibTab::History)),
            Ok("settings") => app.go(Page::Settings),
            Ok("appearance-settings") => app.go(Page::Setting(SettingsPane::Appearance)),
            Ok("radio") => app.go(Page::Radio),
            Ok("podcasts") => app.go(Page::Mood { browse_id: crate::ytm::PODCASTS.into(), params: None, title: "Podcasts".into() }),
            Ok("equalizer-settings") => app.go(Page::Setting(SettingsPane::Equalizer)),
            Ok("lyrics-settings") => app.go(Page::Setting(SettingsPane::Lyrics)),
            Ok("account-settings") => app.go(Page::Setting(SettingsPane::Account)),
            Ok("import-settings") => app.go(Page::Setting(SettingsPane::Import)),
            Ok("transfer") => app.go(Page::Transfer),
            Ok("jam") => app.go(Page::Jam),
            Ok(p) if p.starts_with("album:") => app.go(Page::Album(p[6..].to_owned())),
            Ok(p) if p.starts_with("artist:") => app.go(Page::Artist(p[7..].to_owned())),
            Ok(p) if p.starts_with("playlist:") => app.go(Page::Playlist(p[9..].to_owned())),
            _ => {}
        }
        if let Some(f) = std::env::var_os("NUVX_UPLOAD") {
            *app.picked_uploads.lock().unwrap() = Some(vec![f.into()]);
        }
        if let Some(v) = dev_var("NUVX_PROXY_INPUT") {
            app.proxy_mode = crate::proxy::Setting::Custom(String::new());
            app.proxy_url = v.to_owned();
        }
        if std::env::var("NUVX_NOWPLAYING").is_ok() {
            app.np_open = true;
        }
        app.jam.name = get("jam_name").unwrap_or_default();
        if app.logged_in
            && let Some(c) = get("account_cache").and_then(|j| serde_json::from_str::<AccountCache>(&j).ok())
        {
            app.account_name = c.name;
            app.account_avatar = c.avatar;
            app.sidebar_cache = Some(c.playlists).filter(|p| !p.is_empty());
        }
        if std::env::var_os("NUVX_QUEUE").is_some() {
            app.queue_open = true;
        }
        jam::dev_hooks(&mut app);
        app.audio.set_normalize(app.normalize);
        app.audio.set_spatial(app.spatial);
        app.audio.set_eq(app.eq.tuning());
        app.set_crossfade();
        app
    }

    fn with_cover(&mut self, t: &Track) -> Track {
        if !t.thumbs.first().is_some_and(|th| th.url.contains("ytimg.com")) {
            return t.clone();
        }
        let song = match self.playing_as().filter(|_| self.current_id() == Some(t.id.as_str())) {
            Some(song) => Some(song.clone()),
            None => match self.song_covers.get(&t.id) {
                Some(found) => found.clone(),
                None => {
                    if self.song_covers.len() >= 512 {
                        self.song_covers.clear();
                    }
                    self.song_covers.insert(t.id.clone(), None);
                    self.backend.request(Req::SongCover { track: t.clone() });
                    None
                }
            },
        };
        match song.filter(|s| !s.thumbs.is_empty()) {
            Some(s) => Track { thumbs: s.thumbs, album: s.album.or_else(|| t.album.clone()), ..t.clone() },
            None => t.clone(),
        }
    }

    fn song_canvas(&mut self, ctx: &egui::Context, t: &Track, max_width: u32) -> Option<widgets::MotionSprite> {
        let album = t.album.as_ref().map_or("", |a| a.name.as_str());
        let artist = t.artists.first().map(|a| a.name.clone()).unwrap_or_else(|| t.artist_line());
        self.canvas(ctx, format!("song\u{1}{}\u{1}{album}", t.id), album, &artist, Some(&t.title), max_width)
    }

    fn album_canvas(&mut self, ctx: &egui::Context, album: &str, artist: &str, max_width: u32) -> Option<widgets::MotionSprite> {
        if album.is_empty() {
            return None;
        }
        self.canvas(ctx, format!("{album}\u{1}{artist}"), album, artist, None, max_width)
    }

    fn canvas(&mut self, ctx: &egui::Context, key: String, album: &str, artist: &str, title: Option<&str>, max_width: u32) -> Option<widgets::MotionSprite> {
        if !crate::motion::SUPPORTED || !self.anim_canvas || artist.is_empty() {
            return None;
        }
        if let Some(url) = self.apple_covers.get(&key) {
            let url = url.clone()?;
            return self.motion_sprite(ctx, key, &url, max_width);
        }
        let cooling = self.apple_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(300));
        if !cooling && self.apple_waiting.insert(key.clone()) {
            self.backend.request(Req::Canvas { key, album: album.to_owned(), artist: artist.to_owned(), title: title.map(str::to_owned) });
        }
        None
    }

    fn signature_texture(&mut self, ctx: &egui::Context, name: &str) -> Option<egui::TextureHandle> {
        let endpoint = crate::artist_signature::endpoint(&self.signature_api);
        let canon = crate::artist_signature::key(name);
        if endpoint.is_empty() || canon.is_empty() {
            return None;
        }
        let key = format!("{endpoint}\u{1}{canon}");
        if let Some(tex) = self.signature_tex.get(&key) {
            return Some(tex.clone());
        }
        if let Some((w, h, rgba)) = self.signature_images.remove(&key) {
            let image = egui::ColorImage::from_rgba_unmultiplied([w, h], &rgba);
            let tex = ctx.load_texture("artist-signature", image, egui::TextureOptions::LINEAR);
            if self.signature_tex.len() >= 8 {
                self.signature_tex.clear();
            }
            self.signature_tex.insert(key, tex.clone());
            return Some(tex);
        }
        if self.signature_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(60)) {
            return None;
        }
        if self.signature_waiting.insert(key.clone()) {
            self.backend.request(Req::ArtistSignature { key, endpoint, name: name.to_owned() });
        }
        None
    }

    pub(super) fn signature_reset(&mut self) {
        self.signature_tex.clear();
        self.signature_images.clear();
        self.signature_missed.clear();
        self.signature_waiting.clear();
    }

    fn video_sprite(&mut self, ctx: &egui::Context) -> Option<widgets::MotionSprite> {
        let Some(id) = self.playing_track().filter(|t| t.music_video && crate::motion::SUPPORTED).map(|t| t.id.clone()) else {
            self.video = None;
            return None;
        };
        if self.video.as_ref().is_none_or(|(v, _)| *v != id) {
            self.backend.request(Req::Video { id: id.clone(), height: self.video_height });
            self.video = Some((id, None));
            return None;
        }
        let state = self.video.as_mut()?.1.as_mut()?;
        if let VideoState::Saved(path) = state {
            *state = VideoState::Playing(MotionView::new(crate::motion::video(ctx, path.clone(), self.audio.status.clone()), 0));
        }
        let VideoState::Playing(view) = state else { return None };
        let tex = view.texture(ctx)?;
        Some(widgets::MotionSprite { id: tex.id(), size: tex.size_vec2() })
    }

    fn version_loading(&self) -> bool {
        let picture = crate::motion::SUPPORTED && self.playing_track().is_some_and(|t| t.music_video) && self.video.as_ref().is_none_or(|(_, s)| s.is_none());
        self.buffering || self.video_wanted || picture
    }

    pub fn set_video_height(&mut self, height: u32) {
        self.video_height = height;
        self.video = None;
    }

    fn motion_sprite(&mut self, ctx: &egui::Context, key: String, url: &str, max_width: u32) -> Option<widgets::MotionSprite> {
        let stale = self.apple_motion.get(&key).map_or(true, |v| v.width < max_width);
        if stale {
            if self.apple_motion.len() >= 8 {
                self.apple_motion.clear();
            }
            self.apple_motion.insert(key.clone(), MotionView::new(crate::motion::play(ctx, url, max_width), max_width));
        }
        let tex = self.apple_motion.get_mut(&key)?.texture(ctx)?;
        Some(widgets::MotionSprite { id: tex.id(), size: tex.size_vec2() })
    }

    fn accent_color(&self) -> Color32 {
        let c = self.look().accent(self.accent);
        if theme::light() {
            let luma = 0.299 * c.r() as f32 + 0.587 * c.g() as f32 + 0.114 * c.b() as f32;
            if luma > 150.0 {
                return theme::lerp_color(c, Color32::BLACK, ((luma - 150.0) / 105.0 * 0.45).min(0.45));
            }
        }
        c
    }

    fn light_pages(&self, ctx: &egui::Context) -> bool {
        self.look().id == crate::preset::DEFAULT_ID && self.appearance.is_light(ctx)
    }

    fn look(&self) -> &'static crate::preset::Preset {
        crate::preset::active()
    }

    fn sync_look(&self) {
        let id = self.theme_id.as_str();
        if crate::preset::active().id != id {
            crate::preset::set_active(id);
        }
    }

    fn toast(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), -1.0));
    }


}



fn relaunch() {
    let Ok(exe) = std::env::current_exe() else { return };
    log::info!("restarting");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        let script = format!("ping -n 3 127.0.0.1 >NUL & start \"\" \"{}\"", exe.display());
        let _ = std::process::Command::new("cmd").arg("/C").raw_arg(&script).creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS).spawn();
    }
    #[cfg(not(windows))]
    {
        let _ = std::process::Command::new(exe).spawn();
    }
}

struct PlayingAs {
    generation: u64,
    listed: String,
    song: Track,
}


fn fit_window_to_monitor(ctx: &egui::Context) {
    let done = egui::Id::unique("fit-window-checked");
    if ctx.data(|d| d.get_temp::<bool>(done).unwrap_or(false)) {
        return;
    }
    if std::env::var_os("NUVX_BACKGROUND").is_some() {
        ctx.data_mut(|d| d.insert_temp(done, true));
        ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(false));
        ctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(egui::pos2(-4000.0, 0.0)));
        let size = std::env::var("NUVX_SIZE").ok().and_then(|v| {
            let (w, h) = v.split_once('x')?;
            Some(egui::vec2(w.parse().ok()?, h.parse().ok()?))
        });
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(size.unwrap_or(egui::vec2(1920.0, 1057.0))));
        return;
    }
    let (outer, monitor, maximized) = ctx.input(|i| (i.viewport().outer_rect, i.viewport().monitor_size, i.viewport().maximized));
    let (Some(outer), Some(monitor)) = (outer, monitor) else { return };
    ctx.data_mut(|d| d.insert_temp(done, true));
    let screen = egui::Rect::from_min_size(egui::Pos2::ZERO, monitor);
    let min = egui::Vec2::from(crate::MIN_SIZE);
    if maximized != Some(true) && (outer.width() < min.x || outer.height() < min.y) {
        let size = egui::Vec2::from(crate::DEFAULT_SIZE).min(monitor * 0.9).max(min);
        log::info!("window {outer:?} is below the minimum size; resetting to {size:?}");
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(size));
        ctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(((monitor - size) / 2.0).max(egui::Vec2::ZERO).to_pos2()));
        return;
    }
    if maximized != Some(true) && !screen.expand(12.0).contains_rect(outer) {
        log::info!("window {outer:?} exceeds monitor {monitor:?}; maximizing");
        ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(true));
    }
}

fn smooth_wheel(app: &mut App, ctx: &egui::Context, raw: &mut egui::RawInput) {
    const NOTCH_PX: f32 = 70.0;
    const GLIDE: f32 = 0.06;
    let viewport_h = raw.screen_rect.map_or(800.0, |r| r.height());
    raw.events.retain(|e| match e {
        egui::Event::MouseWheel { unit, delta, modifiers, .. } if !modifiers.command && !modifiers.ctrl => {
            let px = match unit {
                egui::MouseWheelUnit::Line => *delta * NOTCH_PX,
                egui::MouseWheelUnit::Page => *delta * viewport_h * 0.8,
                egui::MouseWheelUnit::Point => return true,
            };
            if app.wheel_pending.y * px.y < 0.0 || app.wheel_pending.x * px.x < 0.0 {
                app.wheel_pending = egui::Vec2::ZERO;
            }
            app.wheel_pending += px;
            app.wheel_mods = *modifiers;
            false
        }
        _ => true,
    });
    if app.wheel_pending.length() < 0.5 {
        app.wheel_pending = egui::Vec2::ZERO;
        return;
    }
    let dt = raw.predicted_dt.clamp(0.001, 0.05);
    let step = app.wheel_pending * (1.0 - (-dt / GLIDE).exp());
    app.wheel_pending -= step;
    raw.events.push(egui::Event::MouseWheel {
        unit: egui::MouseWheelUnit::Point,
        delta: step,
        phase: egui::TouchPhase::Move,
        modifiers: app.wheel_mods,
        source: egui::MouseWheelSource::Wheel,
    });
    ctx.request_repaint();
}

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
