use super::*;

#[derive(Clone, Copy)]
struct NavLook {
    style: crate::preset::NavStyle,
    radius: f32,
    accent: Color32,
}

impl NavLook {
    fn of(app: &App) -> Self {
        Self { style: app.look().app.nav, radius: (app.look().chrome.radius * 0.6).clamp(4.0, 16.0), accent: app.accent_color() }
    }
}

fn nav_bg(ui: &Ui, look: NavLook, rect: Rect, h: f32, s: f32) -> Color32 {
    use crate::preset::NavStyle;
    let (nav_r, accent) = (look.radius, look.accent);
    let icon_fg = theme::lerp_color(theme::text_dim(), theme::text(), (h + s).min(1.0));
    match look.style {
        NavStyle::Pill => {
            ui.painter().rect_filled(rect, nav_r, theme::glass(0.05 * h + 0.10 * s));
            icon_fg
        }
        NavStyle::Accent => {
            ui.painter().rect_filled(rect, nav_r, theme::glass(0.05 * h));
            ui.painter().rect_filled(rect, nav_r, theme::with_alpha(accent, 0.22 * s));
            theme::lerp_color(icon_fg, accent, s)
        }
        NavStyle::Bar => {
            ui.painter().rect_filled(rect, nav_r, theme::glass(0.05 * h));
            let bar = Rect::from_center_size(pos2(rect.left() + 2.0, rect.center().y), vec2(3.0, 20.0 * s));
            ui.painter().rect_filled(bar, 2.0, accent);
            icon_fg
        }
    }
}

pub const SIDEBAR_W: f32 = 236.0;
pub const RAIL_W: f32 = 76.0;

pub fn sidebar_collapse(app: &App, ctx: &egui::Context) -> f32 {
    let t = ctx.animate_bool_with_time(egui::Id::unique("sidebar-collapse"), app.sidebar_collapsed, 0.28 * theme::speed());
    t * t * (3.0 - 2.0 * t)
}

fn history_button(ui: &mut Ui, glyph: char, name: &str) -> bool {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(22.0), Sense::click());
    let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    if h > 0.0 {
        ui.painter().circle_filled(rect.center(), 11.0, theme::glass(0.10 * h));
    }
    icon_at(ui, rect.center(), glyph, 16.0, theme::lerp_color(theme::text_dim(), theme::text(), h));
    resp.clicked()
}

pub fn sidebar(app: &mut App, ui: &mut Ui) {
    const ICON_X: f32 = 24.0;
    let t = sidebar_collapse(app, ui.ctx());
    let collapsed = app.sidebar_collapsed;
    let a = ((1.0 - t) * 1.5 - 0.5).clamp(0.0, 1.0);
    let full_w = ui.available_width() + (SIDEBAR_W - RAIL_W) * t;

    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 0.0;
        if super::super::titlebar::lights_shown(ui.ctx()) {
            ui.add_space(6.0);
            let (r, _) = ui.allocate_exact_size(vec2(52.0, 32.0), Sense::hover());
            super::super::titlebar::traffic_lights(ui, r);
        } else {
            ui.add_space(8.0);
            let (r, resp) = ui.allocate_exact_size(vec2(32.0, 32.0), Sense::click());
            mark(ui, Rect::from_center_size(r.center(), vec2(30.0, 20.0)), theme::text());
            let resp = named(resp, "Home").on_hover_cursor(egui::CursorIcon::PointingHand);
            if resp.clicked() {
                app.actions.push(Action::Go(Page::Home));
            }
        }
        if a > 0.0 {
            ui.multiply_opacity(a);
            ui.add_space((ui.available_width() - 48.0).max(0.0));
            ui.spacing_mut().item_spacing.x = 4.0;
            if history_button(ui, icon::CHEVRON_LEFT, "Back") {
                app.nav_back();
            }
            if history_button(ui, icon::CHEVRON_RIGHT, "Forward") {
                app.nav_forward();
            }
        }
    });
    let row_w = |ui: &Ui| egui::lerp(ui.available_width()..=48.0, t);
    ui.add_space(14.0);

    let mut go_to: Option<Page> = None;
    let look = NavLook::of(app);
    let nav = |ui: &mut Ui, glyph: glyphs::Glyph, size: f32, label: &str, page: Page, selected: bool, go_to: &mut Option<Page>| {
        let (rect, resp) = ui.allocate_exact_size(vec2(row_w(ui), 42.0), Sense::click());
        let mut resp = named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
        if collapsed {
            resp = resp.on_hover_text(label);
        }
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
        let s = theme::anim_bool(ui.ctx(), resp.id.with("s"), selected, 0.2);
        let icon_fg = nav_bg(ui, look, rect, h, s);
        glyphs::draw_bouncing(ui, pos2(rect.left() + ICON_X, rect.center().y), size, glyph, icon_fg, resp.id);
        if a > 0.0 {
            let fg = theme::lerp_color(theme::text_dim(), theme::text(), (h + s).min(1.0));
            let font = if selected { theme::semibold(15.0) } else { theme::regular(15.0) };
            ui.painter().text(pos2(rect.left() + 48.0, rect.center().y), Align2::LEFT_CENTER, label, font, theme::with_alpha(fg, a));
        }
        if resp.clicked() {
            glyphs::bounce(ui.ctx(), resp.id);
            *go_to = Some(page);
        }
    };
    let p = app.page.clone();
    const APPLE: f32 = glyphs::APPLE_GLYPH;
    const LUCIDE: f32 = glyphs::LUCIDE_GLYPH;
    nav(ui, glyphs::SEARCH, APPLE, "Search", Page::Search(String::new()), matches!(p, Page::Search(_)), &mut go_to);
    nav(ui, glyphs::HOME, APPLE, "Home", Page::Home, p == Page::Home, &mut go_to);
    nav(ui, glyphs::EXPLORE, APPLE, "Explore", Page::New, p == Page::New, &mut go_to);
    nav(ui, glyphs::LIBRARY, APPLE, "Library", Page::Library(LibTab::Playlists), matches!(p, Page::Library(_)), &mut go_to);
    let podcasts = matches!(&p, Page::Mood { browse_id, .. } if browse_id == crate::ytm::PODCASTS);
    if !app.hide_radio {
        nav(ui, glyphs::RADIO, APPLE, "Radio", Page::Radio, p == Page::Radio, &mut go_to);
    }
    if !app.hide_podcasts {
        let page = Page::Mood { browse_id: crate::ytm::PODCASTS.into(), params: None, title: "Podcasts".into() };
        nav(ui, glyphs::PODCAST, APPLE, "Podcasts", page, podcasts, &mut go_to);
    }
    if (app.hide_radio && p == Page::Radio) || (app.hide_podcasts && podcasts) {
        go_to = Some(Page::Home);
    }
    nav(ui, glyphs::DOWNLOAD, APPLE, "Downloads", Page::Downloads, p == Page::Downloads, &mut go_to);

    ui.add_space(14.0);
    let (line, _) = ui.allocate_exact_size(vec2(row_w(ui), 1.0), Sense::hover());
    ui.painter().rect_filled(line, 0.0, theme::glass(0.08));
    ui.add_space(14.0 - 4.0 * t);

    if let Some(l) = &app.library {
        for c in &l.playlists {
            if let Target::Playlist(id) = &c.target
                && !app.playlist_order.contains(id)
            {
                app.playlist_order.push(id.clone());
            }
        }
    }
    let mut reorder: Option<(String, String)> = None;
    let mut hide: Option<String> = None;

    let transferring = super::super::transfer::sidebar_visible(app, ui.input(|i| i.time));
    let bottom_h = if transferring { 112.0 } else { 48.0 };
    let list_h = (ui.available_height() - bottom_h).max(0.0);
    ui.allocate_ui(vec2(ui.available_width(), list_h), |ui| {
        if !app.logged_in {
            if t < 0.5 {
                ui.multiply_opacity(1.0 - 2.0 * t);
                ui.label(egui::RichText::new("Sign in to see your playlists").color(theme::text_dim()));
                ui.add_space(8.0);
                if widgets::pill(ui, "Sign in", None, false).clicked() {
                    app.start_sign_in(ui.ctx());
                }
            } else {
                ui.multiply_opacity(2.0 * t - 1.0);
                nav(ui, glyphs::PERSON_CIRCLE, LUCIDE, "Sign in", Page::Setting(super::super::SettingsPane::Account), false, &mut go_to);
            }
            return;
        }
        let (head, _) = ui.allocate_exact_size(vec2(ui.available_width(), 20.0 * (1.0 - t)), Sense::hover());
        if a > 0.0 {
            ui.painter().text(head.left_top() + vec2(8.0, 0.0), Align2::LEFT_TOP, "PLAYLISTS", theme::semibold(11.0), theme::with_alpha(theme::text_faint(), a));
        }
        let bars = if t > 0.0 { egui::scroll_area::ScrollBarVisibility::AlwaysHidden } else { egui::scroll_area::ScrollBarVisibility::VisibleWhenNeeded };
        egui::ScrollArea::vertical().id_salt("sidebar-pl").auto_shrink(false).scroll_bar_visibility(bars).show(ui, |ui| {
            let Some(playlists) = app.library.as_ref().map(|l| &l.playlists).or(app.sidebar_cache.as_ref()) else {
                if app.library_requested.elapsed() < std::time::Duration::from_secs(20) {
                    ui.horizontal(|ui| {
                        ui.add_space(ICON_X - 8.0);
                        ui.spinner();
                    });
                } else if t < 0.5 {
                    ui.multiply_opacity(1.0 - 2.0 * t);
                    ui.label(egui::RichText::new("Couldn't load your playlists").color(theme::text_dim()));
                    ui.add_space(8.0);
                    if widgets::pill(ui, "Try again", None, false).clicked() {
                        app.request_library();
                    }
                }
                return;
            };
            let mut visible: Vec<&crate::model::Card> = playlists
                .iter()
                .filter(|c| !matches!(&c.target, Target::Playlist(id) if app.hidden_playlists.contains(id)))
                .collect();
            visible.sort_by_key(|c| match &c.target {
                Target::Playlist(id) => app.playlist_order.iter().position(|x| x == id).unwrap_or(usize::MAX),
                _ => usize::MAX,
            });
            let drag_key = egui::Id::unique("sidebar-pl-drag");
            let dragging: Option<String> = ui.data(|d| d.get_temp(drag_key));
            let down = ui.input(|i| i.pointer.any_down());
            let pointer = ui.input(|i| i.pointer.interact_pos());
            for c in &visible {
                let (rect, resp) = ui.allocate_exact_size(vec2(row_w(ui), 48.0), Sense::click_and_drag());
                let id = match &c.target {
                    Target::Playlist(id) => id.clone(),
                    _ => continue,
                };
                if !ui.is_rect_visible(rect) {
                    continue;
                }
                let is_dragged = dragging.as_deref() == Some(id.as_str());
                let mut resp = named(resp, &c.title).on_hover_cursor(if is_dragged { egui::CursorIcon::Grabbing } else { egui::CursorIcon::PointingHand });
                if collapsed {
                    resp = resp.on_hover_text(&c.title);
                }
                if resp.drag_started() {
                    ui.data_mut(|d| d.insert_temp(drag_key, id.clone()));
                }
                if let Some(did) = &dragging
                    && did != &id
                    && down
                    && pointer.is_some_and(|p| rect.contains(p))
                {
                    reorder = Some((did.clone(), id.clone()));
                }
                let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
                let selected = matches!(&c.target, Target::Playlist(id) if app.page == Page::Playlist(id.clone()));
                let lift = if is_dragged { 0.10 } else { 0.0 };
                ui.painter().rect_filled(rect, 8.0, theme::glass(0.05 * h + if selected { 0.08 } else { 0.0 } + lift));
                let img = Rect::from_center_size(pos2(rect.left() + ICON_X, rect.center().y), Vec2::splat(36.0));
                paint_cover(ui, img, &c.thumbs, theme::thumb_radius(), 1.0);
                if a > 0.0 {
                    let w = full_w - 58.0;
                    text_at(ui, pos2(img.right() + 10.0, rect.top() + 7.0), &c.title, theme::semibold(13.5), theme::with_alpha(theme::text(), a), w);
                    text_at(ui, pos2(img.right() + 10.0, rect.top() + 26.0), &c.subtitle, theme::regular(12.0), theme::with_alpha(theme::text_dim(), a), w);
                }
                egui::Popup::context_menu(&resp).show(|ui| {
                    ui.set_min_width(190.0);
                    if ui.button("Hide from sidebar").clicked() {
                        hide = Some(id.clone());
                        ui.close();
                    }
                });
                if resp.clicked() && !is_dragged {
                    app.actions.push(Action::Open(c.target.clone()));
                }
            }
            if ui.input(|i| i.pointer.any_released()) {
                ui.data_mut(|d| d.remove_temp::<String>(drag_key));
            }
        });
    });
    if let Some(id) = hide {
        app.hidden_playlists.insert(id);
    }
    if let Some((dragged, target)) = reorder {
        if let (Some(from), Some(to)) = (app.playlist_order.iter().position(|x| x == &dragged), app.playlist_order.iter().position(|x| x == &target)) {
            let id = app.playlist_order.remove(from);
            app.playlist_order.insert(to.min(app.playlist_order.len()), id);
        }
    }
    let ready = app.updater.as_ref().and_then(|u| match u.state() {
        crate::update::State::Ready { version, .. } => Some(version),
        _ => None,
    });
    let mut restart = false;
    ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
        nav(ui, glyphs::SETTINGS, LUCIDE, "Settings", Page::Settings, matches!(p, Page::Settings | Page::Setting(_)), &mut go_to);
        if transferring {
            super::super::transfer::sidebar_card(app, ui, collapsed, a, ICON_X);
        }
        if let Some(v) = &ready {
            ui.add_space(6.0);
            let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 52.0), Sense::click());
            let mut resp = named(resp, "Restart to update").on_hover_cursor(egui::CursorIcon::PointingHand);
            if collapsed {
                resp = resp.on_hover_text(format!("Restart to update to nuvx {v}"));
            }
            let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
            ui.painter().rect_filled(rect, 12.0, theme::with_alpha(look.accent, 0.22 + 0.12 * h));
            glyphs::draw(ui, pos2(rect.left() + ICON_X, rect.center().y), LUCIDE, glyphs::SPARKLES, theme::text());
            if a > 0.0 {
                ui.painter().text(pos2(rect.left() + 46.0, rect.top() + 17.0), Align2::LEFT_CENTER, "Restart to update", theme::semibold(13.5), theme::with_alpha(theme::text(), a));
                ui.painter().text(pos2(rect.left() + 46.0, rect.top() + 35.0), Align2::LEFT_CENTER, format!("nuvx {v}"), theme::regular(12.0), theme::with_alpha(theme::text(), 0.75 * a));
            }
            restart = resp.clicked();
        }
    });
    if restart {
        app.restart_to_update(ui.ctx());
    }
    if let Some(page) = go_to {
        if matches!(page, Page::Search(_)) {
            app.search_refocus = true;
            if !matches!(app.page, Page::Search(_)) {
                app.actions.push(Action::Go(Page::Search(String::new())));
            }
        } else {
            app.actions.push(Action::Go(page));
        }
    }
}
