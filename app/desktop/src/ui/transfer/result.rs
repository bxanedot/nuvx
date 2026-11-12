use super::*;

pub(super) fn done(app: &mut App, ui: &mut Ui) {
    let xfer = app.transfer.clone();
    let accent = app.accent_color();
    let guard = xfer.job.lock().unwrap();
    let Some(job) = guard.as_ref() else { return };
    let Some(sum) = &job.summary else { return };

    ui.add_space(6.0);
    breadcrumb(app, ui);
    ui.add_space(16.0);
    let (mut open, mut another) = (false, false);
    let cover: Vec<Thumb> = job.results.iter().find_map(|r| r.track().map(|t| t.thumbs.clone())).unwrap_or_default();
    let title = match &job.dest {
        Dest::Liked => "Liked Music".to_owned(),
        Dest::Playlist(t) => t.clone(),
    };
    let secs = sum.elapsed.as_secs();
    let line = match &job.dest {
        Dest::Liked if sum.already > 0 => format!("{} songs liked, {} were already · finished in {}", fmt_n(sum.added), fmt_n(sum.already), fmt_secs(secs)),
        Dest::Liked => format!("{} songs liked · finished in {}", fmt_n(sum.added), fmt_secs(secs)),
        Dest::Playlist(_) if sum.playlists.len() > 1 => format!("{} private playlists · {} songs · finished in {}", sum.playlists.len(), fmt_n(sum.added), fmt_secs(secs)),
        Dest::Playlist(_) => format!("Private playlist · {} songs · finished in {}", fmt_n(sum.added), fmt_secs(secs)),
    };
    ui.horizontal(|ui| {
        let (img, _) = ui.allocate_exact_size(vec2(120.0, 120.0), Sense::hover());
        if job.dest == Dest::Liked {
            ui.painter().rect_filled(img, 10.0, Color32::from_rgb(80, 56, 200));
            widgets::heart(ui, img.center(), 48.0, 1.0, Color32::WHITE, Color32::WHITE);
        } else {
            paint_cover(ui, img, &cover, 10.0, 1.0);
        }
        ui.add_space(18.0);
        ui.vertical(|ui| {
            ui.set_width((ui.available_width() - 330.0).max(200.0));
            ui.add_space(22.0);
            ui.label(RichText::new("TRANSFER COMPLETE").font(theme::bold(13.0)).color(accent));
            ui.add_space(2.0);
            ui.label(RichText::new(&title).font(theme::bold(32.0)).color(theme::text()));
            ui.add_space(2.0);
            ui.label(RichText::new(&line).font(theme::regular(14.0)).color(theme::text_dim()));
        });
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            another = pill(ui, "Transfer another", None, false).clicked();
            ui.add_space(8.0);
            open = pill(ui, if job.dest == Dest::Liked { "Open Liked Music" } else { "Open playlist" }, Some(icon::PLAY), true).clicked();
        });
    });
    ui.add_space(20.0);
    let skipped = job.count(|r| *r == Search::Skipped);
    let missing = job.count(|r| *r == Search::Missing);
    let mut stats = vec![(fmt_n(sum.added), if job.dest == Dest::Liked { "Liked" } else { "Added" }, false)];
    if job.dest == Dest::Liked {
        stats.push((fmt_n(sum.already), "Already liked", false));
    }
    stats.extend([(fmt_n(sum.duplicates), "Duplicates, added once", false), (fmt_n(skipped), "Skipped in review", false), (fmt_n(missing), "Not on YouTube Music", false)]);
    stat_row(ui, &stats, accent);
    ui.add_space(20.0);

    let missed: Vec<(&Track, &str)> = job
        .tracks
        .iter()
        .zip(&job.results)
        .filter_map(|(t, r)| match r {
            Search::Missing => Some((t, "Not on YouTube Music")),
            Search::Skipped => Some((t, "Skipped in review")),
            _ => None,
        })
        .collect();
    let mut copy = None;
    let mut search = None;
    if !missed.is_empty() {
        egui::Frame::new().fill(theme::glass(0.04)).corner_radius(theme::panel_radius()).inner_margin(egui::Margin::symmetric(16, 12)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.label(RichText::new(format!("NOT ADDED ({})", fmt_n(missed.len()))).font(theme::bold(11.5)).color(theme::text_faint()));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if pill(ui, "Copy list", None, false).clicked() {
                        copy = Some(missed.iter().map(|(t, _)| format!("{} - {}", t.name, t.artist_line())).collect::<Vec<_>>().join("\n"));
                    }
                });
            });
            ui.add_space(6.0);
            widgets::visible_rows(ui, missed.len(), |ui, k| {
                let (t, why) = missed[k];
                let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 56.0), Sense::hover());
                let img = Rect::from_min_size(pos2(rect.left() + 4.0, rect.center().y - 18.0), vec2(36.0, 36.0));
                paint_cover(ui, img, &spotify_thumbs(&t.art), 4.0, 1.0);
                let tw = rect.width() - 340.0;
                text_at(ui, pos2(img.right() + 12.0, rect.top() + 9.0), &t.name, theme::semibold(13.5), theme::text(), tw);
                text_at(ui, pos2(img.right() + 12.0, rect.top() + 29.0), &t.artist_line(), theme::regular(12.0), theme::text_dim(), tw);
                let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect).layout(egui::Layout::right_to_left(egui::Align::Center)));
                if pill(&mut child, "Search", Some(icon::SEARCH), false).clicked() {
                    search = Some(format!("{} {}", t.name, t.artist_line()));
                }
                child.add_space(14.0);
                child.label(RichText::new(why).font(theme::regular(13.0)).color(theme::text_dim()));
            });
        });
    }
    let first_playlist = sum.playlists.first().cloned();
    let dest = job.dest.clone();
    drop(guard);
    if let Some(text) = copy {
        ui.ctx().copy_text(text);
        app.toast("Copied the list");
    }
    if let Some(q) = search {
        app.actions.push(Action::Go(Page::Search(q)));
    }
    if open {
        match (dest, first_playlist) {
            (Dest::Liked, _) => app.actions.push(Action::Go(Page::Library(LibTab::Songs))),
            (_, Some(id)) => app.actions.push(Action::Open(Target::Playlist(id))),
            _ => {}
        }
    }
    if another {
        xfer.dismiss();
        app.xfer.picked = None;
        app.xfer.link.clear();
        app.xfer.name_for = None;
    }
}

pub(super) fn failed(app: &mut App, ui: &mut Ui, e: &str) {
    let xfer = app.transfer.clone();
    ui.add_space(6.0);
    breadcrumb(app, ui);
    page_title(ui, "Transfer stopped");
    ui.add_space(10.0);
    let source = xfer.job.lock().unwrap().as_ref().map(|j| j.source.clone());
    let resumable = xfer.resumable.lock().unwrap().clone().filter(|r| Some(&r.source) == source.as_ref());
    let (mut retry, mut close) = (false, false);
    settings_card(ui, |ui| {
        setting_row(ui, "Something went wrong", Some(e), |ui| {
            close = pill(ui, "Start over", None, false).clicked();
            if resumable.is_some() {
                ui.add_space(8.0);
                retry = pill(ui, "Try again", None, true).clicked();
            }
        });
    });
    if retry && let Some(r) = resumable {
        xfer.dismiss();
        xfer.start(&app.backend, r.source, r.dest, true);
    } else if close {
        xfer.dismiss();
    }
}
