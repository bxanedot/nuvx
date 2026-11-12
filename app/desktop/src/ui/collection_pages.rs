use egui::{Align2, Color32, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use super::pages::{card_row, list, placeholder, ListCtx, Rows, CARD};
use super::widgets::{self, named, paint_cover, pill, shelf_header, track_row};
use super::{Action, App};
use crate::backend::compact;
use crate::model::{Target, Track};
use crate::theme::{self, icon};

enum CircleGlyph {
    Char(char),
    More,
}

fn circle_action(ui: &mut Ui, glyph: CircleGlyph, name: &str, active: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(38.0), Sense::click());
    let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.15);
    let press = if resp.is_pointer_button_down_on() { 18.3 } else { 19.0 };
    ui.painter().circle_filled(rect.center(), press, theme::glass(0.10 + 0.09 * h));
    let color = theme::lerp_color(theme::text_dim(), theme::text(), h.max(active as u8 as f32));
    match glyph {
        CircleGlyph::Char(g) => widgets::icon_at(ui, rect.center(), g, 16.0, color),
        CircleGlyph::More => {
            for i in -1..=1 {
                ui.painter().circle_filled(rect.center() + vec2(i as f32 * 5.2, 0.0), 1.8, color);
            }
        }
    }
    resp
}

fn play_pill(ui: &mut Ui, acts: &mut Vec<Action>, target: &Target, tracks: &[Track]) {
    let active = widgets::active_state(ui, target);
    let (label, glyph) = if active == Some(true) { ("Pause", icon::PAUSE) } else { ("Play", icon::PLAY) };
    if pill(ui, label, Some(glyph), true).clicked() {
        if active.is_some() {
            acts.push(Action::TogglePlay);
        } else if !tracks.is_empty() {
            acts.push(Action::Play(tracks.to_vec(), 0));
        }
    }
}
pub(super) fn collection(app: &mut App, ui: &mut Ui, id: &str, now: f64) {
    if app.opened(id).is_none() {
        placeholder(app, ui, now, 1, 232.0);
        return;
    }
    let rows = Rows::new(app, now);
    let (release, artist) = match app.opened(id) {
        Some(o) => (o.c.title.clone(), o.c.artists.first().map(|a| a.name.clone()).unwrap_or_else(|| o.c.byline())),
        None => (String::new(), String::new()),
    };
    let px = (232.0 * ui.ctx().pixels_per_point()).round().max(256.0) as u32;
    let sprite = app.album_canvas(ui.ctx(), &release, &artist, px);
    let Some(o) = app.lists.get(id) else { return };
    let (c, more) = (&o.c, app.receiving(id));
    let acts = &mut app.actions;
    let tracks = &c.tracks;
    let is_album = c.is_album;
    let filter_on = app.coll_search.as_ref().is_some_and(|(fid, _)| fid == id);
    let selecting = app.selection.on(&app.page);
    let mut toggle_search = false;
    let mut start_select = false;
    let mut stop_select = false;
    ui.add_space(18.0);
    ui.horizontal_top(|ui| {
        let (img, _) = ui.allocate_exact_size(Vec2::splat(232.0), Sense::hover());
        widgets::soft_shadow(ui, img, 10.0, 40.0, 0.5);
        paint_cover(ui, img, &c.thumbs, theme::card_radius() + 2.0, 1.0);
        if let Some(s) = &sprite {
            widgets::paint_motion(ui, img, theme::card_radius() + 2.0, s);
        }
        ui.add_space(26.0);
        ui.vertical(|ui| {
            ui.set_max_width(ui.available_width().min(760.0));
            ui.add_space(24.0);
            ui.label(RichText::new(if is_album { "ALBUM" } else { "PLAYLIST" }).font(theme::semibold(12.0)).color(theme::text_dim()));
            ui.label(RichText::new(&c.title).font(theme::bold(40.0)).color(theme::text()));
            ui.add_space(2.0);
            let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 20.0), Sense::hover());
            widgets::linked_text(ui, r.min, &c.byline(), &c.artists, theme::regular(15.0), theme::text_dim(), r.width(), acts);
            let total: u32 = tracks.iter().filter_map(|t| t.duration).sum();
            if total > 0 {
                ui.label(
                    RichText::new(format!("{} songs • {} min", tracks.len(), total / 60)).font(theme::regular(13.5)).color(theme::text_faint()),
                );
            }
            if let Some(d) = c.description.as_deref().filter(|d| !d.trim().is_empty()) {
                ui.add_space(6.0);
                let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(13.5), theme::text_dim(), ui.available_width(), 2);
                ui.label(g);
            }
            ui.add_space(16.0);
            let target = if is_album { Target::Album(id.to_owned()) } else { Target::Playlist(id.to_owned()) };
            let auto = matches!(id, "LM" | "SE");
            let saved = app.library.as_ref().is_some_and(|l| {
                let cards = if is_album { &l.albums } else { &l.playlists };
                cards.iter().any(|card| card.target == target)
            });
            let save_id = if is_album { c.save_id.clone() } else { (!c.owned).then(|| id.to_owned()) };
            let owned = !is_album && c.owned;
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing = vec2(10.0, 0.0);
                play_pill(ui, acts, &target, tracks);
                if circle_action(ui, CircleGlyph::Char(icon::SHUFFLE), "Shuffle", false).clicked() && !tracks.is_empty() {
                    let mut t = tracks.clone();
                    super::shuffle(&mut t);
                    acts.push(Action::Play(t, 0));
                }
                if !tracks.is_empty() && circle_action(ui, CircleGlyph::Char(icon::DOWNLOAD), "Download", false).clicked() {
                    acts.push(Action::DownloadAll(c.title.clone(), vec![target.clone()]));
                }
                if circle_action(ui, CircleGlyph::Char(icon::SEARCH), "Search in this list", filter_on).clicked() {
                    toggle_search = true;
                }
                let more = circle_action(ui, CircleGlyph::More, "More", false);
                egui::Popup::menu(&more).show(|ui| {
                    ui.set_width(226.0);
                    ui.add_space(3.0);
                    if let Some(first) = tracks.first()
                        && widgets::menu_action(ui, Some(icon::RADIO), "Start radio")
                    {
                        acts.push(Action::Radio(first.clone()));
                    }
                    if let Some(save_id) = save_id.clone()
                        && widgets::menu_action(ui, Some(icon::LIBRARY), if saved { "Remove from library" } else { "Save to library" })
                    {
                        acts.push(Action::SaveCollection(save_id, !saved));
                    }
                    if app.logged_in && !auto && !tracks.is_empty() {
                        if widgets::menu_action(ui, Some(icon::LIST_CHECKS), if selecting { "Done selecting" } else { "Select" }) {
                            if selecting { stop_select = true } else { start_select = true }
                        }
                    }
                    if app.logged_in && !auto && (save_id.is_some() || owned) {
                        ui.separator();
                    }
                    if owned {
                        if widgets::menu_action(ui, Some(icon::MUSIC), "Edit playlist") {
                            acts.push(Action::EditPlaylist);
                        }
                        if widgets::menu_action(ui, Some(icon::USERS), "Collaborate") {
                            acts.push(Action::Collaborate);
                        }
                        if widgets::menu_action(ui, Some(icon::TRASH_2), "Delete playlist") {
                            acts.push(Action::DeletePlaylist);
                        }
                    }
                    if app.logged_in && !auto && !owned && save_id.is_none() {
                        ui.separator();
                    }
                    if widgets::menu_action(ui, Some(icon::LINK), "Share") {
                        acts.push(Action::Share(target.clone()));
                    }
                    ui.add_space(3.0);
                });
            });
            if toggle_search {
                let closing = app.coll_search.as_ref().is_some_and(|(fid, _)| fid == id);
                app.coll_search = (!closing).then(|| (id.to_owned(), String::new()));
            }
            if start_select {
                app.selection.start(&app.page);
            }
            if stop_select {
                app.selection.clear();
            }

        });
    });
    let mut dismiss_search = false;
    if let Some((fid, query)) = &mut app.coll_search
        && fid == id
    {
        ui.add_space(14.0);
        let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width().min(420.0), 40.0), Sense::hover());
        ui.painter().rect_filled(rect, 20.0, theme::glass(0.09));
        widgets::icon_at(ui, pos2(rect.left() + 20.0, rect.center().y), icon::SEARCH, 16.0, theme::text_dim());
        let clearable = !query.is_empty();
        let inner = Rect::from_min_max(pos2(rect.left() + 40.0, rect.top() + 4.0), pos2(rect.right() - if clearable { 40.0 } else { 16.0 }, rect.bottom() - 4.0));
        let edit = ui.put(
            inner,
            egui::TextEdit::singleline(query)
                .hint_text(egui::RichText::new("Search in this list").color(theme::text_faint()))
                .font(theme::regular(14.0))
                .align(egui::Align2::LEFT_CENTER)
                .frame(egui::Frame::NONE)
                .desired_width(inner.width()),
        );
        if toggle_search {
            edit.request_focus();
        } else if !edit.has_focus() && query.is_empty() {
            dismiss_search = true;
        }
        if clearable {
            let x = Rect::from_center_size(pos2(rect.right() - 20.0, rect.center().y), Vec2::splat(26.0));
            let resp = named(ui.interact(x, ui.scope_id().with("coll-clear"), Sense::click()), "Clear search").on_hover_cursor(egui::CursorIcon::PointingHand);
            widgets::icon_at(ui, x.center(), icon::X, 14.0, theme::text_dim());
            if resp.clicked() {
                query.clear();
            }
        }
        ui.add_space(18.0);
    } else {
        ui.add_space(26.0);
    }
    if dismiss_search {
        app.coll_search = None;
    }
    let needle = app.coll_search.as_ref().filter(|(fid, _)| fid == id).map(|(_, q)| q.trim().to_lowercase()).unwrap_or_default();
    let filtered: Vec<Track> = if needle.is_empty() {
        Vec::new()
    } else {
        c.tracks
            .iter()
            .filter(|t| {
                t.title.to_lowercase().contains(&needle)
                    || t.artist_line().to_lowercase().contains(&needle)
                    || t.album.as_ref().is_some_and(|a| a.name.to_lowercase().contains(&needle))
            })
            .cloned()
            .collect()
    };
    let shown: &[Track] = if needle.is_empty() { tracks } else { &filtered };
    let number = |i: usize, t: &Track| is_album.then(|| t.track_nr.map_or(i + 1, |n| n as usize));
    let own = app.logged_in && c.owned && !is_album;
    list(ui, &rows, &app.liked, shown, !is_album, number, ListCtx { sel: &mut app.selection, page: &app.page, own }, acts);
    if more {
        widgets::skeleton_rows(ui, now, 3);
    }
}

fn wordmark(ui: &Ui, base: egui::Pos2, logo: &crate::apple::LogoSrc, max_w: f32) -> bool {
    let aspect = logo.w.max(1) as f32 / logo.h.max(1) as f32;
    let mut size = vec2(72.0 * aspect, 72.0);
    if size.x > max_w {
        size = vec2(max_w, max_w / aspect);
    }
    let url = logo.at_height((size.y * 2.0) as u32);
    let ready = matches!(egui::Image::new(url.as_str()).load_for_size(ui.ctx(), size), Ok(egui::load::TexturePoll::Ready { .. }));
    if ready {
        let rect = Rect::from_min_size(pos2(base.x, base.y - 40.0 - size.y), size);
        let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0));
        widgets::paint_remote(ui, &url, rect, 0.0, uv, Color32::WHITE);
    }
    ready
}

pub(super) fn artist(app: &mut App, ui: &mut Ui, id: &str, now: f64) {
    if app.artist.as_ref().is_none_or(|a| a.id != id) {
        placeholder(app, ui, now, 2, CARD);
        return;
    }
    let rows = Rows::new(app, now);
    let accent = app.accent_color();
    let apple = app.apple_artists.get(id).and_then(|o| o.clone());
    let name = apple.as_ref().map(|x| x.name.clone()).unwrap_or_else(|| app.artist.as_ref().map(|a| a.name.clone()).unwrap_or_default());
    if apple.is_none() {
        let key = format!("artist:{id}");
        let cooling = app.apple_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(120));
        if !cooling && app.apple_waiting.insert(key) {
            app.backend.request(crate::backend::Req::AppleArtist { id: id.to_owned(), name: name.clone() });
        }
    }
    let signature = app.signature_texture(ui.ctx(), &name);
    let Some(a) = &app.artist else { return };
    let acts = &mut app.actions;
    let top = &a.top;
    let w = ui.available_width();
    let hero_h = (w * 0.34).clamp(240.0, 380.0);
    let (hero, _) = ui.allocate_exact_size(vec2(w, hero_h), Sense::hover());
    if let Some(t) = a.banner.iter().max_by_key(|t| t.width) {
        let img_ratio = if t.height > 0 { t.width as f32 / t.height as f32 } else { 16.0 / 9.0 };
        let vis_h = (img_ratio / (w / hero_h)).min(1.0);
        let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, vis_h));
        ui.painter().rect_filled(hero, 14.0, theme::glass(0.07));
        widgets::paint_remote(ui, &t.url, hero, 14.0, uv, Color32::WHITE);
    } else {
        ui.painter().rect_filled(hero, 14.0, theme::with_alpha(accent, 0.25));
    }
    super::shell::vgradient(ui.painter(), Rect::from_min_max(pos2(hero.left(), hero.center().y - 40.0), hero.max), Color32::TRANSPARENT, theme::shade(0.85));
    let base = hero.left_bottom() + vec2(28.0, -30.0);
    let drawn = apple.as_ref().and_then(|x| x.logo.as_ref()).is_some_and(|logo| wordmark(ui, base, logo, w * 0.62));
    if !drawn {
        match &signature {
            Some(tex) => {
                let tex_size = tex.size_vec2();
                let aspect = tex_size.x / tex_size.y.max(1.0);
                let mut size = vec2(96.0 * aspect, 96.0);
                let max_w = (w * 0.42).min(340.0);
                if size.x > max_w {
                    size = vec2(max_w, max_w / aspect);
                }
                let rect = Rect::from_min_size(pos2(base.x, base.y - 40.0 - size.y), size);
                ui.painter().text(pos2(base.x, rect.top() - 8.0), Align2::LEFT_BOTTOM, format!("Signed by {name}"), theme::regular(12.0), Color32::from_white_alpha(199));
                let tex = egui::load::SizedTexture::from_handle(tex);
                egui::Image::from_texture(tex).uv(Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))).paint_at(ui, rect);
            }
            None => {
                ui.painter().text(base - vec2(0.0, 44.0), Align2::LEFT_BOTTOM, &name, theme::bold(58.0), Color32::WHITE);
            }
        }
    }
    if let Some(s) = a.subscribers {
        ui.painter().text(base - vec2(0.0, 18.0), Align2::LEFT_BOTTOM, format!("{} subscribers", compact(s)), theme::regular(14.0), theme::text_dim());
    }
    ui.add_space(18.0);
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        play_pill(ui, acts, &Target::Artist(id.to_owned()), top);
        if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() && !top.is_empty() {
            let mut t = top.clone();
            super::shuffle(&mut t);
            acts.push(Action::Play(t, 0));
        }
        if let Some(first) = top.first()
            && pill(ui, "Radio", Some(icon::RADIO), false).clicked() {
                acts.push(Action::Radio(first.clone()));
            }
        let mut releases: Vec<Target> = a.albums.iter().map(|c| c.target.clone()).filter(|t| matches!(t, Target::Album(_))).collect();
        if releases.is_empty() {
            releases.extend(a.top_playlist.clone().map(Target::Playlist));
        }
        if !releases.is_empty() && pill(ui, "Download", Some(icon::DOWNLOAD), false).clicked() {
            acts.push(Action::DownloadAll(a.name.clone(), releases));
        }
    });
    if !top.is_empty() {
        ui.add_space(26.0);
        ui.horizontal(|ui| {
            ui.label(RichText::new("Top songs").font(theme::bold(24.0)).color(theme::text()));
            if let Some(pl) = &a.top_playlist {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if pill(ui, "Show all", None, false).clicked() {
                        acts.push(Action::Open(Target::Playlist(pl.clone())));
                    }
                });
            }
        });
        ui.add_space(12.0);
        for (i, t) in top.iter().enumerate().take(5) {
            let o = rows.opts(&app.liked, t, None, true);
            if track_row(ui, t, &o, acts).clicked() {
                acts.push(Action::Play(top.clone(), i));
            }
        }
    }
    card_row(ui, "a-albums", "Albums & singles", &a.albums, acts);
    card_row(ui, "a-pl", "Featured on", &a.playlists, acts);
    card_row(ui, "a-sim", "Fans might also like", &a.similar, acts);
    if let Some(d) = a.description.as_deref().filter(|d| !d.trim().is_empty()) {
        shelf_header(ui, "About", None, None, false);
        let full = ui.available_width().min(820.0);
        let beside = drawn.then_some(signature.as_ref()).flatten();
        if let Some(tex) = beside {
            ui.horizontal_top(|ui| {
                let sig_w = (full * 0.28).clamp(120.0, 210.0);
                let text_w = (full - sig_w - 32.0).max(220.0);
                let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(14.5), theme::text_dim(), text_w, 8);
                ui.label(g);
                ui.add_space(32.0);
                let tex_size = tex.size_vec2();
                let aspect = tex_size.x / tex_size.y.max(1.0);
                let mut size = vec2(sig_w, sig_w / aspect);
                if size.y > 140.0 {
                    size = vec2(140.0 * aspect, 140.0);
                }
                let (rect, _) = ui.allocate_exact_size(vec2(size.x, size.y + 22.0), Sense::hover());
                ui.painter().text(rect.left_top(), Align2::LEFT_TOP, format!("Signed by {name}"), theme::regular(12.0), Color32::from_white_alpha(199));
                let img = Rect::from_min_size(pos2(rect.left(), rect.top() + 22.0), size);
                let tex = egui::load::SizedTexture::from_handle(tex);
                egui::Image::from_texture(tex).uv(Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))).paint_at(ui, img);
            });
        } else {
            let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(14.5), theme::text_dim(), full, 8);
            ui.label(g);
        }
    }
}
