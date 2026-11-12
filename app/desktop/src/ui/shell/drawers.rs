use super::*;

pub fn lyrics_drawer(app: &mut App, ui: &mut Ui, now: f64) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new("Lyrics").font(theme::bold(20.0)));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.add_space(super::super::titlebar::right_gap(ui.ctx(), ui.max_rect(), 0.0));
            if icon_button(ui, icon::X, 16.0, "Close lyrics", false).clicked() {
                app.lyrics_open = false;
            }
        });
    });
    ui.add_space(8.0);
    if app.current().is_none() {
        ui.label(egui::RichText::new("Play a song to see its lyrics here.").color(theme::text_dim()));
        return;
    }
    let rect = ui.available_rect_before_wrap();
    super::super::nowplaying::lyrics_panel(app, ui, rect, now, true);
}

pub fn queue_drawer(app: &mut App, ui: &mut Ui) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new("Up next").font(theme::bold(20.0)));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.add_space(super::super::titlebar::right_gap(ui.ctx(), ui.max_rect(), 0.0));
            if icon_button(ui, icon::X, 16.0, "Close queue", false).clicked() {
                app.queue_open = false;
            }
        });
    });
    ui.add_space(8.0);
    queue_list(app, ui, "drawer");
}

pub fn queue_list(app: &mut App, ui: &mut Ui, salt: &str) {
    let rows = super::super::pages::Rows::new(app, ui.input(|i| i.time));
    egui::ScrollArea::vertical().id_salt(("queue", salt)).auto_shrink(false).show(ui, |ui| {
        if app.queue.tracks.is_empty() {
            ui.label(egui::RichText::new("Nothing queued yet").color(theme::text_dim()));
            return;
        }
        let start = app.queue.index.unwrap_or(0);
        ui.checkbox(&mut app.autoplay, egui::RichText::new("Autoplay similar songs").color(theme::text_dim())).on_hover_cursor(egui::CursorIcon::PointingHand);
        ui.add_space(6.0);
        let (queue, liked, acts, has_current, by) = (&app.queue.tracks, &app.liked, &mut app.actions, app.queue.index.is_some(), &app.jam.by);
        widgets::visible_rows(ui, queue.len() - start, |ui, k| {
            let i = start + k;
            let t = &queue[i];
            let current = k == 0 && has_current;
            let o = widgets::RowOpts { current, added_by: by.get(&t.id).cloned(), ..rows.opts(liked, t, None, false) };
            let resp = widgets::track_row(ui, t, &o, acts);
            if resp.clicked() {
                acts.push(if current { Action::TogglePlay } else { Action::QueueJump(i) });
            }
            if !current && ui.rect_contains_pointer(Rect::from_min_max(resp.rect.min, pos2(resp.rect.right() + 42.0, resp.rect.bottom()))) {
                let x = Rect::from_center_size(resp.rect.right_center() - vec2(26.0, 0.0), Vec2::splat(28.0));
                let xr = named(ui.interact(x, resp.id.with("rm"), Sense::click()), "Remove from queue").on_hover_cursor(egui::CursorIcon::PointingHand);
                ui.painter().circle_filled(x.center(), 14.0, theme::panel(0.6));
                icon_at(ui, x.center(), icon::X, 14.0, theme::text());
                if xr.clicked() {
                    acts.push(Action::QueueRemove(i));
                }
            }
        });
    });
}
