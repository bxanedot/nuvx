use super::*;

fn decorations<'a>(
    show_roman: bool,
    show_trans: bool,
    target: &'a str,
    extras: Option<&'a (String, String, String, crate::translate::Extras)>,
    id: &str,
    lyrics: &'a Lyrics,
) -> (Vec<Option<karaoke::Roman<'a>>>, Vec<Option<&'a str>>) {
    let extras = extras.filter(|(v, s, t, _)| v == id && s == lyrics.provider.key() && t == target).map(|(_, _, _, e)| e);
    let same = |a: &str, b: &str| a.trim().eq_ignore_ascii_case(b.trim());
    let roman = lyrics
        .lines
        .iter()
        .enumerate()
        .map(|(i, l)| {
            if !show_roman || l.instrumental {
                return None;
            }
            if let Some(r) = l.romanization.as_deref().filter(|r| !same(r, &l.text)) {
                return Some(if l.timed_roman.is_empty() { karaoke::Roman::Text(r) } else { karaoke::Roman::Timed(&l.timed_roman, r) });
            }
            let r = extras?.roman.get(i)?.as_deref()?;
            (!same(r, &l.text)).then_some(karaoke::Roman::Text(r))
        })
        .collect();
    let trans = lyrics
        .lines
        .iter()
        .enumerate()
        .map(|(i, l)| {
            if !show_trans || l.instrumental {
                return None;
            }
            let t = super::super::lyric_prefs::shipped_translation(l, target).or_else(|| extras?.trans.get(i)?.as_deref())?;
            (!same(t, &l.text)).then_some(t)
        })
        .collect();
    (roman, trans)
}

pub(super) fn synced(app: &mut App, ui: &mut Ui, rect: Rect, id: &str, lyrics: &Lyrics, now: f64, narrow: bool) {
    let style = &app.look().lyrics;
    let accent = app.accent_color();
    let centered = narrow || style.align == crate::preset::Align::Center;
    let playing = if app.mix_hold().is_some() { app.audio.status.outgoing_playing() } else { app.playing() };
    let pos = match app.mix_hold() {
        Some(h) => app.mix_hold_pos(h) as f64 - app.audio.status.latency().as_secs_f64(),
        None => app.audio.status.position_smooth().as_secs_f64() - app.audio.status.latency().as_secs_f64(),
    };
    let pos = app.lyric_clock.tick(pos, playing, now);
    let t = pos - app.lyric_prefs.offset(id, lyrics.provider, lyrics.sync == Sync::Word);
    let (roman, trans) = decorations(app.show_roman, app.show_trans, &app.lyric_prefs.target, app.lyrics_extras.as_ref(), id, lyrics);
    let key = {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        id.hash(&mut h);
        lyrics.provider.key().hash(&mut h);
        app.theme_id.hash(&mut h);
        for r in &roman {
            match r {
                Some(karaoke::Roman::Timed(_, s)) | Some(karaoke::Roman::Text(s)) => s.hash(&mut h),
                None => 0u8.hash(&mut h),
            }
        }
        trans.hash(&mut h);
        h.finish()
    };
    let scale = app.lyric_prefs.text_scale;
    let frame = karaoke::Frame { lyrics, key, roman, trans, t, playing, now, rect, style, accent, centered, scale };
    let mut view = std::mem::take(&mut app.lyric_view);
    let out = if karaoke::dev_off("view") { karaoke::Out { seek: None, animating: true, user_scrolling: false } } else { view.show(ui, frame) };
    app.lyric_view = view;
    if let Some(s) = out.seek {
        app.actions.push(Action::Seek(s as f32));
    }
    if out.animating {
        ui.ctx().request_repaint();
    }
    let shown = theme::anim_bool(ui.ctx(), "lyr-resume", out.user_scrolling, 0.2);
    if shown > 0.0 {
        let font = theme::lyric_font(&style.font, crate::preset::Weight::Semibold, 17.5);
        let g = widgets::galley(ui, "Resume Autoscroll", font, Color32::WHITE, rect.width() - 40.0);
        let size = g.size() + vec2(20.0, 10.0) + Vec2::splat(2.0);
        let em = 17.5;
        let y = rect.top() + 8.0 + egui::lerp(-3.0 * em..=0.5 * em, theme::ease_out(shown));
        let r = Rect::from_min_size(pos2(rect.center().x - size.x / 2.0, y), size);
        let resp = ui.interact(r, ui.scope_id().with("lyr-resume-btn"), if out.user_scrolling { Sense::click() } else { Sense::hover() });
        let resp = named(resp, "Resume Autoscroll").on_hover_cursor(egui::CursorIcon::PointingHand);
        let hover = theme::anim_bool(ui.ctx(), "lyr-resume-h", resp.hovered() && out.user_scrolling, 0.25);
        let p = ui.painter().with_clip_rect(rect.expand2(vec2(0.0, 4.0)));
        p.rect_filled(r, 10.0, theme::with_alpha(Color32::WHITE, 0.10 * shown));
        p.rect_stroke(r, 10.0, egui::Stroke::new(1.0, theme::with_alpha(Color32::WHITE, 0.2 * shown)), egui::StrokeKind::Inside);
        let at = r.center() - g.size() / 2.0;
        if hover > 0.0
            && let Some((tex, glow_rect, uv)) = app.lyric_view.text_glow(ui.ctx(), g.clone(), at, 15.0)
        {
            let glow = crate::preset::paint(&style.glow_color, accent);
            p.image(tex, glow_rect, uv, theme::with_alpha(glow, hover * shown));
        }
        p.galley_with_override_text_color(at, g, theme::with_alpha(Color32::WHITE, shown));
        if shown < 1.0 {
            ui.ctx().request_repaint();
        }
        if resp.clicked() {
            app.lyric_view.resume();
        }
    }
}

pub(super) fn plain_lyrics(app: &mut App, ui: &mut Ui, rect: Rect, lyrics: &Lyrics) {
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect));
    egui::ScrollArea::vertical().id_salt("plain-lyrics").auto_shrink(false).show(&mut child, |ui| {
        ui.label(egui::RichText::new("These lyrics aren't synced").font(theme::regular(13.0)).color(theme::text_faint()));
        ui.add_space(14.0);
        let style = &app.look().lyrics;
        let size = (32.0 * style.size.clamp(0.6, 1.6)).min(rect.width() / 12.5).max(18.0);
        for l in &lyrics.lines {
            let font = theme::lyric_font_for(&style.font, style.weight, size, &l.text);
            let color = theme::with_alpha(Color32::WHITE, 0.85);
            if crate::bidi::has_rtl(&l.text) {
                let width = ui.available_width();
                let g = super::widgets::galley_wrapped(ui, &l.text, font, color, width, usize::MAX);
                let (r, _) = ui.allocate_exact_size(vec2(width, g.size().y), Sense::hover());
                let x = if crate::bidi::rtl_base(&l.text) { r.right() - g.size().x } else { r.left() };
                ui.painter().galley(pos2(x, r.top()), g, color);
            } else {
                ui.label(egui::RichText::new(&l.text).font(font).color(color));
            }
            ui.add_space(size * 0.25);
        }
        ui.add_space(80.0);
    });
}

pub(super) fn sync_color(k: SyncKind) -> Color32 {
    match k {
        SyncKind::Syllable => Color32::from_rgb(196, 160, 255),
        SyncKind::Word => Color32::from_rgb(130, 200, 255),
        SyncKind::Line => Color32::from_rgb(255, 205, 110),
        SyncKind::Unsynced => Color32::from_rgb(170, 170, 170),
    }
}

pub(super) fn sync_label(k: SyncKind) -> &'static str {
    match k {
        SyncKind::Syllable => "Syllable synced",
        SyncKind::Word => "Word synced",
        SyncKind::Line => "Line synced",
        SyncKind::Unsynced => "Not synced",
    }
}
