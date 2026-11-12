use super::*;

pub fn card(ui: &mut Ui, c: &Card, width: f32, liked: bool, acts: &mut Vec<Action>) {
    let height = width + 58.0;
    let (rect, resp) = ui.allocate_exact_size(vec2(width, height), Sense::click());
    if !ui.is_rect_visible(rect) {
        return;
    }
    let resp = named(resp, &c.title).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.contains_pointer(), 0.22);
    let img = Rect::from_min_size(rect.min, Vec2::splat(width));
    let radius = if c.round { width / 2.0 } else { theme::card_radius().min(width / 2.0) };
    paint_cover(ui, img, &c.thumbs, radius, 1.0 + 0.06 * h * theme::hover_k());
    if h > 0.0 && !c.round {
        ui.painter().rect_filled(img, radius, theme::shade(0.28 * h));
    }
    let active = active_state(ui, &c.target);
    let h = if active.is_some() { 1.0 } else { h };
    let mut play_hit = false;
    if h > 0.01 {
        let center = img.right_bottom() + vec2(-30.0, -30.0 + 8.0 * (1.0 - h));
        let prect = Rect::from_center_size(center, Vec2::splat(44.0));
        let p = ui.interact(prect, resp.id.with("play"), Sense::click());
        let playing = active == Some(true);
        let p = named(p, &format!("{} {}", if playing { "Pause" } else { "Play" }, c.title)).on_hover_cursor(egui::CursorIcon::PointingHand);
        let ph = theme::anim_bool(ui.ctx(), p.id.with("h"), p.hovered(), 0.12);
        ui.painter().circle_filled(center, 22.0 + 2.0 * ph, theme::shade(0.62 * h));
        play_pause_glyph(ui, center, 10.0, theme::with_alpha(Color32::WHITE, h), playing);
        play_hit = p.clicked();
    }
    let tcolor = theme::text();
    let title = galley_wrapped(ui, &c.title, theme::semibold(14.5), tcolor, width, 2);
    let title_h = title.size().y;
    ui.painter().galley(pos2(rect.left(), img.bottom() + 10.0), title, tcolor);
    let sub_y = img.bottom() + 14.0 + title_h;
    linked_text(ui, pos2(rect.left(), sub_y), &c.subtitle, &c.links, theme::regular(13.0), theme::text_dim(), width - 36.0, acts);
    let more_rect = Rect::from_center_size(pos2(rect.right() - 16.0, sub_y + 8.0), Vec2::splat(30.0));
    let more = more_button(ui, more_rect, resp.id.with("more"), "Options");
    egui::Popup::menu(&more).show(|ui| card_menu_contents(ui, c, liked, acts));
    egui::Popup::context_menu(&resp).show(|ui| card_menu_contents(ui, c, liked, acts));

    if play_hit {
        match &c.target {
            _ if active.is_some() => acts.push(Action::TogglePlay),
            Target::Song(t) => acts.push(Action::Radio(t.clone())),
            t => acts.push(Action::PlayCollection(t.clone(), false)),
        }
    } else if resp.clicked() && !more.clicked() {
        acts.push(Action::Open(c.target.clone()));
    }
}
