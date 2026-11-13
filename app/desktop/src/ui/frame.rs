use super::*;
impl eframe::App for App {
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        if crate::material::see_through() { [0.0; 4] } else { [0.0, 0.0, 0.0, 1.0] }
    }

    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        dev_pace();
        crate::material::set_dark(!theme::light());
        self.lyric_view.release_hidden(ctx.cumulative_pass_nr());
        let now = ctx.input(|i| i.time);
        self.sync_look();
        self.pump(now);
        self.skip_segments(ctx, now);
        let playing = self.playing() || self.buffering;
        if let Some(m) = &mut self.media {
            m.set_playing(playing);
        }
        self.request_lyric_extras();
        let mut files = self.picked_uploads.lock().unwrap().take().unwrap_or_default();
        files.extend(ctx.input(|i| i.raw.dropped_files.iter().map(|f| f.path().to_owned()).filter(|p| !p.as_os_str().is_empty()).collect::<Vec<_>>()));
        if !files.is_empty() {
            self.upload_files(files);
        }
        transfer::poll(self, ctx, now);
        if crate::deeplink::take_show() {
            self.show_window(ctx);
        }
        for code in crate::deeplink::take() {
            self.show_window(ctx);
            jam::open_link(self, code);
        }
        for a in std::mem::take(&mut self.actions) {
            self.apply(a);
        }
        jam::tick(self);
        jam::dev_script(self, ctx, now);
        if self.page_changed < 0.0 {
            self.page_changed = now;
        }
        if let Some(v) = dev_var("NUVX_NP_TOGGLE_AT") {
            let times: Vec<f64> = v.split(',').filter_map(|t| t.parse().ok()).collect();
            let id = egui::Id::unique("dev-np-toggles");
            let done: usize = ctx.data(|d| d.get_temp(id)).unwrap_or(0);
            if let Some(&t) = times.get(done) {
                if now >= t {
                    ctx.data_mut(|d| d.insert_temp(id, done + 1));
                    self.np_tab = if self.np_tab == NpTab::Lyrics { NpTab::Art } else { NpTab::Lyrics };
                } else {
                    ctx.request_repaint_after(Duration::from_secs_f64(t - now));
                }
            }
        }
        fit_window_to_monitor(ctx);
        if let Some(v) = dev_var("NUVX_MINI") {
            let times: Vec<f64> = v.split(',').filter_map(|t| t.parse().ok()).collect();
            let id = egui::Id::unique("dev-mini");
            let done: usize = ctx.data(|d| d.get_temp(id)).unwrap_or(0);
            if let Some(&t) = times.get(done) {
                if now >= t {
                    ctx.data_mut(|d| d.insert_temp(id, done + 1));
                    self.mini.request(done.is_multiple_of(2));
                } else {
                    ctx.request_repaint_after(Duration::from_secs_f64((t - now).min(0.2)));
                }
            }
        }
        if let Some(on) = self.mini.take_request() {
            self.set_mini(ctx, on, now);
        }
        self.mini.tick(ctx, now);
        self.tray_window(ctx);
        self.window_hidden = self.tray.is_some() || ctx.input(|i| i.viewport().minimized == Some(true));
        self.sync_discord();
        self.sync_lastfm();
        self.sync_server_queue(ctx, now);
        self.tick_updates(ctx, now);
        let np = widgets::NowPlaying {
            song: self.current_id().map(str::to_owned),
            source: self.queue_source.clone(),
            playing: self.playing() || self.buffering,
        };
        ctx.data_mut(|d| d.insert_temp(widgets::NowPlaying::id(), np));
        if let Some((_, t)) = &mut self.toast {
            if *t < 0.0 {
                *t = now;
            } else if now - *t > 4.5 {
                self.toast = None;
            }
        }
        let playing = self.playing();
        if self.window_hidden {
            if playing || self.buffering {
                ctx.request_repaint_after(Duration::from_millis(250));
            } else {
                ctx.request_repaint_after(Duration::from_secs(2));
            }
        } else if playing && !self.mini.busy() {
            ctx.request_repaint_after(Duration::from_millis(8));
        } else if playing && self.anim_bg && shell::pages_bg_moves(self.look()) {
            ctx.request_repaint_after(Duration::from_millis(66));
        } else if playing || self.buffering {
            ctx.request_repaint_after(Duration::from_millis(250));
        }
    }

    fn ui(&mut self, ui: &mut Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.shortcuts(&ctx);
        let now = ctx.input(|i| i.time);
        let screen = ctx.content_rect();

        self.sync_look();
        let look = self.look();
        let np = theme::anim_bool(&ctx, "np-open", self.np_open && self.current().is_some(), 0.42);
        let light = self.light_pages(&ctx) && np < 0.5 && !self.fullscreen;
        theme::set_mode(&ctx, light);
        if self.chrome_applied.as_deref() != Some(look.id.as_str()) {
            if self.chrome_applied.is_none() {
                ctx.add_texture_loader(Arc::new(crate::images::Textures::default()));
            }
            theme::apply_chrome(&ctx, look);
            self.chrome_applied = Some(look.id.clone());
        }
        let accent = self.accent_color();
        ctx.data_mut(|d| d.insert_temp(egui::Id::unique("accent"), accent));
        let app_style = &look.app;
        let covered = !self.fullscreen && np >= 1.0;
        let bar_strip = egui::Rect::from_min_size(screen.min, egui::vec2(screen.width(), titlebar::HEIGHT));
        titlebar::set_fullscreen(&ctx, self.fullscreen);
        let layout = self.mini.layout(&ctx);
        crate::fullscreen::set_rounded((layout == mini::Layout::Mini).then(|| (theme::thumb_radius() + 1.0) * ctx.pixels_per_point()));
        if layout != mini::Layout::Full {
            shell::background(self, ui, screen, now, None);
            if layout == mini::Layout::Mini {
                mini::strip(self, ui, now);
                titlebar::resize_sides(&ctx);
                shell::toast(self, &ctx, now);
                self.mini.fade_in(&ctx, now, shell::base_color(self));
            }
            dev_screenshot(&ctx, now);
            return;
        }
        shell::background(self, ui, screen, now, covered.then_some(bar_strip));
        if !self.fullscreen && !covered {
            titlebar::drag_area(ui, egui::Rect::from_min_size(screen.min, egui::vec2(screen.width(), titlebar::HEIGHT)), "main");
        }
        if !self.fullscreen && covered {
            egui::Panel::top("player").resizable(false)
                .exact_size(titlebar::HEIGHT)
                .frame(egui::Frame::new())
                .show_separator_line(false)
                .show(ui, |ui| shell::player_bar(self, ui, now));
        }
        if !self.fullscreen && !covered {
            use crate::preset::SidebarStyle;
            let side_alpha = app_style.sidebar_alpha.clamp(0.0, 1.0);
            let side_margin = egui::Margin { left: 14, right: 14, top: titlebar::top_margin(16, 32.0), bottom: 16 };
            let side_frame = match app_style.sidebar {
                SidebarStyle::Glass => egui::Frame::new().fill(theme::panel(side_alpha)).inner_margin(side_margin),
                SidebarStyle::Transparent => egui::Frame::new().inner_margin(side_margin),
                SidebarStyle::Solid => egui::Frame::new().fill(look.solid()).inner_margin(side_margin),
                SidebarStyle::Floating => egui::Frame::new()
                    .fill(theme::panel(side_alpha.max(0.3)))
                    .stroke(egui::Stroke::new(1.0, theme::glass(0.08)))
                    .corner_radius(egui::CornerRadius::same(theme::panel_radius().max(12)))
                    .outer_margin(egui::Margin { left: 10, right: 4, top: 10, bottom: 10 })
                    .inner_margin(egui::Margin::symmetric(12, 14)),
            };
            egui::Panel::left("nav").resizable(false)
                .exact_size(shell::SIDEBAR_W - (shell::SIDEBAR_W - shell::RAIL_W) * shell::sidebar_collapse(self, &ctx))
                .frame(side_frame)
                .show_separator_line(false)
                .show(ui, |ui| shell::sidebar(self, ui));
            egui::Panel::top("player").resizable(false)
                .exact_size(titlebar::HEIGHT)
                .frame(egui::Frame::new())
                .show_separator_line(false)
                .show(ui, |ui| shell::player_bar(self, ui, now));
            let q = theme::anim_bool(&ctx, "queue-drawer", self.queue_open && !self.np_open, 0.25);
            if q > 0.01 {
                egui::Panel::right("queue").resizable(false)
                    .exact_size(340.0 * q)
                    .frame(egui::Frame::new().fill(theme::panel(0.42)).inner_margin(egui::Margin::symmetric(14, 16)))
                    .show_separator_line(false)
                    .show(ui, |ui| shell::queue_drawer(self, ui));
            }
            let l = theme::anim_bool(&ctx, "lyrics-drawer", self.lyrics_open && !self.np_open, 0.25);
            if l > 0.01 {
                egui::Panel::right("lyrics").resizable(false)
                    .exact_size(380.0 * l)
                    .frame(egui::Frame::new().fill(theme::panel(0.42)).inner_margin(egui::Margin::symmetric(14, 16)))
                    .show_separator_line(false)
                    .show(ui, |ui| shell::lyrics_drawer(self, ui, now));
            }
            egui::CentralPanel::default()
                .frame(egui::Frame::new().inner_margin(egui::Margin { left: 28, right: 28, top: 10, bottom: 0 }))
                .show(ui, |ui| {
                    self.search_rect = None;
                    pages::page(self, ui, now);
                });
        }
        if np > 0.001 {
            let pages = theme::set_mode(&ctx, false);
            nowplaying::overlay(self, &ctx, now);
            theme::set_mode(&ctx, pages);
        }
        self.draw_dialog(&ctx);
        notes::card(self, &ctx);
        notes::dialog(self, &ctx);
        notes::tab_intro(self, &ctx);
        if let Some(url) = self.share_pending.take() { ctx.copy_text(url); }
        shell::suggestions(self, &ctx);
        shell::toast(self, &ctx, now);

        titlebar::window_buttons(&ctx);
        titlebar::resize_edges(&ctx);
        self.mini.fade_in(&ctx, now, shell::base_color(self));

        if dev_var("NUVX_THEME_TOUR").is_some() {
            self.theme_tour(&ctx, now);
        } else {
            dev_screenshot(&ctx, now);
        }
    }

    fn raw_input_hook(&mut self, ctx: &egui::Context, raw: &mut egui::RawInput) {
        if let Some((at, n)) = dev_var("NUVX_FAKE_WHEEL").and_then(|v| {
            let (a, n) = v.split_once(',')?;
            Some((a.parse::<f64>().ok()?, n.parse::<f32>().ok()?))
        }) {
            let fired = egui::Id::unique("dev-wheel-fired");
            let t = raw.time.unwrap_or(0.0);
            if t >= at && !ctx.data(|d| d.get_temp::<bool>(fired).unwrap_or(false)) {
                ctx.data_mut(|d| d.insert_temp(fired, true));
                raw.events.push(egui::Event::MouseWheel {
                    unit: egui::MouseWheelUnit::Line,
                    delta: egui::vec2(0.0, -n),
                    phase: egui::TouchPhase::Move,
                    modifiers: egui::Modifiers::NONE,
                    source: egui::MouseWheelSource::Wheel,
                });
            } else if t < at {
                ctx.request_repaint_after(Duration::from_millis(100));
            }
        }
        smooth_wheel(self, ctx, raw);
        if let Some((x, y)) = dev_var("NUVX_FAKE_POINTER").and_then(|v| {
            let (x, y) = v.split_once(',')?;
            Some((x.trim().parse::<f32>().ok()?, y.trim().parse::<f32>().ok()?))
        }) {
            raw.events.retain(|e| !matches!(e, egui::Event::PointerMoved(_) | egui::Event::PointerGone));
            raw.events.push(egui::Event::PointerMoved(egui::pos2(x, y)));
        }
    }

    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        if std::env::var_os("NUVX_MUTE").is_none() {
            storage.set_string("volume", self.audio.volume().to_string());
        }
        let repeat = match self.queue.repeat {
            Repeat::Off => "off",
            Repeat::All => "all",
            Repeat::One => "one",
        };
        storage.set_string("repeat", repeat.into());
        let b = |v: bool| if v { "1" } else { "0" }.to_owned();
        storage.set_string("autoplay", b(self.autoplay));
        storage.set_string("recent_searches", serde_json::to_string(&self.recent_searches).unwrap_or_default());
        storage.set_string("jam_name", self.jam.name.clone());
        let cache = self.logged_in.then(|| AccountCache {
            name: self.account_name.clone(),
            avatar: self.account_avatar.clone(),
            playlists: self.library.as_ref().map(|l| l.playlists.clone()).or_else(|| self.sidebar_cache.clone()).unwrap_or_default(),
        });
        storage.set_string("account_cache", cache.and_then(|c| serde_json::to_string(&c).ok()).unwrap_or_default());
        storage.set_string("normalize", b(self.normalize));
        storage.set_string("spatial_audio", b(self.spatial));
        storage.set_string("equalizer", serde_json::to_string(&self.eq).unwrap_or_default());
        storage.set_string("log_volume", b(self.log_volume));
        storage.set_string("high_quality", b(self.high_quality));
        storage.set_string("play_songs", b(self.play_songs));
        storage.set_string("video_height", self.video_height.to_string());
        storage.set_string("load_both", b(self.load_both));
        let folder = crate::downloads::folder();
        storage.set_string("download_dir", if folder == crate::downloads::default_folder() { String::new() } else { folder.display().to_string() });
        let (device, device_name) = self.output_device.clone().unzip();
        storage.set_string("output_device", device.unwrap_or_default());
        storage.set_string("output_device_name", device_name.unwrap_or_default());
        if self.window_hidden {
            if let Some(w) = &self.good_window {
                storage.set_string("window", w.clone());
            }
        } else {
            self.good_window = storage.get_string("window");
        }
        self.mini.save(storage);
        if let Some(w) = self.mini.full_window() {
            storage.set_string("window", w.clone());
            self.good_window = Some(w);
        }
        storage.set_string("close_to_tray", b(self.close_to_tray));
        storage.set_string("classic_search", b(self.classic_search));
        storage.set_string("youtube_search", b(self.youtube_search));
        storage.set_string("hide_radio_tab", b(self.hide_radio));
        storage.set_string("hide_podcasts_tab", b(self.hide_podcasts));
        storage.set_string("radio_intro_shown", b(self.tab_intros_shown[0]));
        storage.set_string("podcasts_intro_shown", b(self.tab_intros_shown[1]));
        storage.set_string("sidebar_collapsed", b(self.sidebar_collapsed));
        storage.set_string("window_buttons", if titlebar::mac() { "mac" } else { "windows" }.to_owned());
        if let Some(l) = &self.library {
            for c in &l.playlists {
                if let Target::Playlist(id) = &c.target
                    && !self.playlist_order.contains(id)
                {
                    self.playlist_order.push(id.clone());
                }
            }
        }
        storage.set_string("playlist_order", self.playlist_order.join("\n"));
        storage.set_string("hidden_playlists", self.hidden_playlists.iter().cloned().collect::<Vec<_>>().join("\n"));
        if std::env::var_os("NUVX_UPDATE_AS").is_none() {
            storage.set_string("last_version", crate::update::current_version());
        }
        storage.set_string("discord_enabled", b(self.discord_enabled));
        if dev_var("NUVX_APPEARANCE").is_none() {
            storage.set_string("appearance", self.appearance.key().to_owned());
        }
        if std::env::var_os("NUVX_THEME").is_none() {
            storage.set_string("theme", self.theme_id.clone());
        }
        if dev_var("NUVX_FONT").is_none() {
            storage.set_string("font", theme::font_choice());
        }
        storage.set_string("anim_bg", b(self.anim_bg));
        storage.set_string("anim_canvas", b(self.anim_canvas));
        storage.set_string("lastfm_now_playing", b(self.lastfm_now_playing));
        storage.set_string("lastfm_primary_only", b(self.lastfm_primary_only));
        storage.set_string("reduce_motion", b(self.reduce_motion));
        storage.set_string("hide_quick_picks", b(self.hide_quick_picks));
        storage.set_string("hide_home_videos", b(self.hide_home_videos));
        storage.set_string("reactive_bg", b(self.reactive_bg));
        storage.set_string("signature_api", self.signature_api.clone());
        storage.set_string("hires_source", self.hires_sources.join("\n"));
        storage.set_string("prefer_atmos", b(self.prefer_atmos));
        storage.set_string("sponsorblock", b(self.sponsorblock));
        storage.set_string("crossfade", b(self.crossfade));
        storage.set_string("automix", b(self.automix));
        storage.set_string("crossfade_secs", self.crossfade_secs.to_string());
        storage.set_string("show_roman", b(self.show_roman));
        storage.set_string("show_trans", b(self.show_trans));
        if std::env::var_os("NUVX_BACKGROUND").is_none() || std::env::var_os("NUVX_SAVE_SESSION").is_some() {
            self.lyric_prefs.save(storage);
        }
        storage.set_string("np_art", b(self.np_tab == NpTab::Art));
        let test_run = std::env::var_os("NUVX_BACKGROUND").is_some() && std::env::var_os("NUVX_SAVE_SESSION").is_none();
        if let Some(s) = self.session_json().filter(|_| !test_run) {
            storage.set_string(SESSION_KEY, s);
        }
    }

    fn on_exit(&mut self, gl: Option<&eframe::glow::Context>) {
        if let (Some(bg), Some(gl)) = (&self.bg, gl) {
            bg.destroy(gl);
        }
        let update_ready = self.updater.as_ref().is_some_and(|u| matches!(u.state(), crate::update::State::Ready { .. }));
        if let Some(u) = &self.updater
            && self.restart_for_update
        {
            u.install(true);
        }
        if self.restart_app && !update_ready {
            relaunch();
        }
    }
}

