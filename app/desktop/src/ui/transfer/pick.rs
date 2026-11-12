use super::*;

pub fn pick_banner(app: &mut App, ui: &mut Ui) -> bool {
    let Some(at) = app.xfer.picking else { return false };
    let song = {
        let guard = app.transfer.job.lock().unwrap();
        guard.as_ref().filter(|j| j.phase == Phase::Review).and_then(|j| j.tracks.get(j.doubts.get(at)?.index).cloned())
    };
    let Some(sp) = song else {
        app.xfer.picking = None;
        return false;
    };
    let accent = app.accent_color();
    let mut back = false;
    ui.add_space(8.0);
    egui::Frame::new()
        .fill(theme::with_alpha(accent, 0.12))
        .stroke(egui::Stroke::new(1.0, theme::with_alpha(accent, 0.35)))
        .corner_radius(theme::panel_radius())
        .inner_margin(14)
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                let (img, _) = ui.allocate_exact_size(vec2(52.0, 52.0), Sense::hover());
                paint_cover(ui, img, &spotify_thumbs(&sp.art), 6.0, 1.0);
                ui.add_space(8.0);
                let text_w = (ui.available_width() - 200.0).max(120.0);
                ui.vertical(|ui| {
                    ui.set_width(text_w);
                    ui.label(RichText::new("CHOOSING A MATCH FOR").font(theme::bold(11.5)).color(theme::text_faint()));
                    ui.add(egui::Label::new(RichText::new(format!("{} · {}", sp.name, sp.artist_line())).font(theme::semibold(15.0)).color(theme::text())).truncate());
                    dim(ui, "Click a song to use it. Click a cover to play it.");
                });
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    back = pill(ui, "Back to transfer", Some(icon::CHEVRON_LEFT), false).clicked();
                });
            });
        });
    ui.add_space(6.0);
    if back {
        app.actions.push(Action::Go(Page::Transfer));
    }
    true
}

pub fn use_pick(app: &mut App, t: YtTrack) {
    let Some(at) = app.xfer.picking.take() else { return };
    app.transfer.decide_with(at, t);
    app.xfer.focus = app.transfer.job.lock().unwrap().as_ref().and_then(|j| {
        let n = j.doubts.len();
        (at + 1..n).chain(0..at.min(n)).find(|&k| j.doubts[k].decision.is_none())
    });
    app.xfer.pick = None;
    app.go(Page::Transfer);
}
