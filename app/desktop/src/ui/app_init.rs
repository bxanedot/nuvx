use super::*;

fn support_prompt_for_launch(launch_count: u32, disabled: bool) -> u8 {
    if disabled || launch_count % 12 != 8 {
        0
    } else if (launch_count / 12) % 2 == 0 {
        1
    } else {
        2
    }
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
        let support_prompts_off = get("support_prompts_off").is_some_and(|v| v == "1");
        let launch_count = get("launch_count")
            .and_then(|value| value.parse::<u32>().ok())
            .unwrap_or_default()
            .wrapping_add(1);
        let support_prompt = support_prompt_for_launch(launch_count, support_prompts_off);
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
            version_alternate: None,
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
            tab_intro_step: [0; 2],
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
            launch_count,
            support_prompt,
            support_prompts_off,
            discord_invite_joined: get("discord_invite_joined").is_some_and(|v| v == "1"),
            discord_prompt_dismissed: false,
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
            video_mode: false,
            video_height: get("video_height").and_then(|v| v.parse().ok()).unwrap_or(480),

            load_both: get("load_both_v2").is_none_or(|v| v == "1"),
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
}

#[cfg(test)]
mod tests {
    use super::support_prompt_for_launch;

    #[test]
    fn support_prompts_follow_the_twelve_launch_cycle() {
        assert_eq!(support_prompt_for_launch(7, false), 0);
        assert_eq!(support_prompt_for_launch(8, false), 1);
        assert_eq!(support_prompt_for_launch(20, false), 2);
        assert_eq!(support_prompt_for_launch(32, false), 1);
        assert_eq!(support_prompt_for_launch(8, true), 0);
    }
}
