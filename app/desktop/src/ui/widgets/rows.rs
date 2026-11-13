use super::*;

#[derive(Clone, Default)]
pub struct NowPlaying {
    pub song: Option<String>,
    pub source: Option<Target>,
    pub playing: bool,
}

impl NowPlaying {
    pub fn id() -> egui::Id {
        egui::Id::unique("now-playing")
    }
}

pub fn active_state(ui: &Ui, target: &Target) -> Option<bool> {
    let np = ui.ctx().data(|d| d.get_temp::<NowPlaying>(NowPlaying::id()))?;
    let hit = match target {
        Target::Song(t) => np.song.as_deref() == Some(t.id.as_str()),
        t => np.source.as_ref() == Some(t),
    };
    hit.then_some(np.playing)
}

pub fn play_pause_glyph(ui: &Ui, center: Pos2, r: f32, color: Color32, playing: bool) {
    if playing {
        for dx in [-0.45, 0.45] {
            let bar = Rect::from_center_size(center + vec2(dx * r, 0.0), vec2(r * 0.42, r * 1.7));
            ui.painter().rect_filled(bar, r * 0.1, color);
        }
    } else {
        play_triangle(ui, center, r, color);
    }
}

pub fn play_triangle(ui: &Ui, center: Pos2, r: f32, color: Color32) {
    let pts = vec![
        center + vec2(-r * 0.6, -r),
        center + vec2(r * 1.0, 0.0),
        center + vec2(-r * 0.6, r),
    ];
    ui.painter().add(egui::Shape::convex_polygon(pts, color, egui::Stroke::NONE));
}

pub fn equalizer(ui: &Ui, rect: Rect, time: f64, playing: bool, color: Color32) {
    let ctx = ui.ctx();
    let on = theme::anim_bool(ctx, ui.scope_id().with("eq"), playing, 0.35);
    let px = ctx.pixels_per_point();
    let snap = |v: f32| (v * px).round() / px;
    let bar_w = snap((rect.width() * 0.22).max(2.0));
    let gap = snap(bar_w * 0.75);
    let total = bar_w * 3.0 + gap * 2.0;
    let left = snap(rect.center().x - total / 2.0);
    let t = time as f32;
    const WAVES: [(f32, f32, f32); 3] = [(4.1, 2.3, 0.0), (3.3, 5.2, 1.9), (4.7, 2.9, 3.7)];
    for (i, (a, b, off)) in WAVES.into_iter().enumerate() {
        let wave = 0.5 + 0.3 * (t * a + off).sin() + 0.2 * (t * b + off * 1.7).sin();
        let rest = [0.35, 0.6, 0.45][i];
        let amp = rest + (0.3 + 0.7 * wave.clamp(0.0, 1.0) - rest) * on;
        let h = (rect.height() * amp).max(bar_w);
        let x = left + i as f32 * (bar_w + gap);
        let bar = Rect::from_min_max(pos2(x, snap(rect.bottom()) - h), pos2(x + bar_w, snap(rect.bottom())));
        ui.painter().add(egui::epaint::RectShape::filled(bar, bar_w / 2.0, color).with_round_to_pixels(false));
    }
}

pub struct RowOpts {
    pub number: Option<usize>,
    pub show_album: bool,
    pub current: bool,
    pub playing: bool,
    pub accent: Color32,
    pub time: f64,
    pub liked: bool,
    pub added_by: Option<String>,
    pub pick: bool,
    pub selected: Option<bool>,
    pub remove: Option<usize>,
}



pub fn visible_rows(ui: &mut Ui, n: usize, mut row: impl FnMut(&mut Ui, usize)) {
    let h = ROW_H + ui.spacing().item_spacing.y;
    let top = ui.cursor().top();
    let clip = ui.clip_rect();
    let first = (((clip.top() - top) / h).floor().max(0.0) as usize).min(n);
    let last = ((((clip.bottom() - top) / h).ceil().max(0.0)) as usize + 1).clamp(first, n);
    ui.add_space(first as f32 * h);
    for i in first..last {
        ui.push_id(i, |ui| row(ui, i));
    }
    ui.add_space((n - last) as f32 * h);
}

pub fn track_row(ui: &mut Ui, t: &Track, o: &RowOpts, acts: &mut Vec<Action>) -> Response {
    let (rect, base) = ui.allocate_exact_size(vec2(ui.available_width(), ROW_H), Sense::hover());
    if !ui.is_rect_visible(rect) {
        return base;
    }
    let hit_left = if o.pick && o.number.is_none() { rect.left() + 54.0 } else { rect.left() };
    let hit_rect = Rect::from_min_max(pos2(hit_left, rect.top()), pos2(rect.right() - 42.0, rect.bottom()));
    let resp = named(ui.interact(hit_rect, base.id.with("play"), Sense::click()), &t.title).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), base.id.with("h"), base.contains_pointer(), 0.15);
    if h > 0.0 || o.current {
        let a = if o.current { 0.07 + 0.05 * h } else { 0.07 * h };
        ui.painter().rect_filled(rect, 8.0, theme::glass(a));
    }
    let h = if o.selected.is_some() { 0.0 } else { h };
    if o.selected == Some(true) {
        ui.painter().rect_filled(rect, 8.0, theme::with_alpha(o.accent, 0.14));
    }
    if o.pick {
        let p = theme::anim_bool(ui.ctx(), base.id.with("p"), resp.hovered(), 0.12);
        if p > 0.0 {
            ui.painter().rect_stroke(rect, 8.0, egui::Stroke::new(1.5, theme::with_alpha(o.accent, p)), egui::StrokeKind::Inside);
        }
    }
    let mut x = rect.left() + 10.0;
    let cy = rect.center().y;
    if let Some(on) = o.selected {
        checkbox(ui, pos2(x + 9.0, cy), on);
        x += 30.0;
    }
    if let Some(n) = o.number {
        let slot = Rect::from_center_size(pos2(x + 12.0, cy), vec2(24.0, 24.0));
        if o.current {
            equalizer(ui, slot.shrink(4.0), o.time, o.playing, o.accent);
        } else if h > 0.5 {
            play_triangle(ui, slot.center(), 6.0, theme::text());
        } else {
            ui.painter().text(slot.center(), Align2::CENTER_CENTER, n.to_string(), theme::regular(14.0), theme::text_dim());
        }
        x += 36.0;
    } else {
        let img = Rect::from_min_size(pos2(x, cy - 20.0), Vec2::splat(40.0));
        let tr = theme::thumb_radius();
        paint_cover(ui, img, &t.thumbs, tr, 1.0);
        if o.pick {
            let play = named(ui.interact(img, base.id.with("cover"), Sense::click()), "Play").on_hover_text("Play").on_hover_cursor(egui::CursorIcon::PointingHand);
            if play.clicked() {
                acts.push(Action::Play(vec![t.clone()], 0));
            }
        }
        if o.current {
            ui.painter().rect_filled(img, tr, theme::shade(0.45));
            equalizer(ui, img.shrink(10.0), o.time, o.playing, Color32::WHITE);
        } else if h > 0.0 {
            ui.painter().rect_filled(img, tr, theme::shade(0.5 * h));
            play_triangle(ui, img.center(), 7.0, theme::with_alpha(Color32::WHITE, h));
        }
        x += 54.0;
    }
    let right = rect.right() - 51.0;
    let dur_w = 52.0;
    let avail = right - dur_w - x;
    let wide = avail > 520.0;
    let album_row = o.number.is_some();
    let (title_w, plays_x, album_x) = match (wide, o.show_album, t.plays) {
        (true, true, Some(_)) => (avail * 0.45, Some(x + avail * 0.82), Some(x + avail * 0.5)),
        (true, true, None) => (avail * 0.55, None, Some(x + avail * 0.6)),
        (true, false, Some(_)) => (avail * 0.78, Some(x + avail * 0.82), None),
        _ => (avail, None, None),
    };
    let plays = t.plays.map(|n| format!("{} plays", crate::backend::compact(n)));
    let title_col = if o.current { o.accent } else { theme::text() };
    let sub = match (album_row, &plays, plays_x) {
        (true, Some(p), None) => Some(p.clone()),
        (true, _, _) => None,
        (false, Some(p), None) => Some(format!("{} \u{2022} {p}", t.artist_line())),
        (false, _, _) => Some(t.artist_line()),
    };
    let sub = match (sub, &o.added_by) {
        (Some(s), Some(by)) if !s.is_empty() => Some(format!("{s} \u{2022} Added by {by}")),
        (_, Some(by)) => Some(format!("Added by {by}")),
        (sub, _) => sub,
    };
    let title_y = if sub.is_some() { cy - 18.0 } else { cy - 9.0 };
    text_at(ui, pos2(x, title_y), &t.title, theme::semibold(14.5), title_col, title_w);
    if let Some(sub) = &sub {
        linked_text(ui, pos2(x, cy + 2.0), sub, &t.artists, theme::regular(13.0), theme::text_dim(), title_w, acts);
    }
    if let (Some(px), Some(p)) = (plays_x, &plays) {
        ui.painter().text(pos2(px, cy), Align2::LEFT_CENTER, p, theme::regular(13.5), theme::text_dim());
    }
    if let (Some(ax), Some(album)) = (album_x, &t.album) {
        let w = plays_x.unwrap_or(right - dur_w) - ax - 10.0;
        let g = galley(ui, &album.name, theme::regular(13.5), theme::text_dim(), w);
        let ar = Rect::from_min_size(pos2(ax, cy - 9.0), g.size());
        ui.painter().galley(ar.min, g, theme::text_dim());
        if let Some(id) = &album.id {
            let r = named(ui.interact(ar, resp.id.with("album"), Sense::click()), &album.name);
            let r = r.on_hover_cursor(egui::CursorIcon::PointingHand);
            if r.hovered() {
                ui.painter().hline(ar.x_range(), ar.bottom() - 1.0, egui::Stroke::new(1.0, theme::text_dim()));
            }
            if r.clicked() {
                acts.push(Action::Open(Target::Album(id.clone())));
            }
        }
    }
    if let Some(d) = t.duration {
        ui.painter().text(pos2(right, cy), Align2::RIGHT_CENTER, super::super::fmt_time(d), theme::regular(13.0), theme::text_dim());
    }
    let more_rect = Rect::from_center_size(pos2(rect.right() - 20.0, cy), Vec2::splat(30.0));
    let more = more_button(ui, more_rect, base.id.with("more"), "Song options");
    egui::Popup::menu(&more).show(|ui| song_menu(ui, t, o.liked, o.remove, acts));
    egui::Popup::context_menu(&resp).show(|ui| song_menu(ui, t, o.liked, o.remove, acts));
    resp
}

fn checkbox(ui: &Ui, c: Pos2, on: bool) {
    let r = Rect::from_center_size(c, Vec2::splat(18.0));
    if on {
        ui.painter().rect_filled(r, 5.0, theme::text());
        icon_at(ui, c, icon::CHECK, 13.0, theme::on_text());
    } else {
        ui.painter().rect_stroke(r, 5.0, egui::Stroke::new(1.5, theme::text_dim()), egui::StrokeKind::Inside);
    }
}
