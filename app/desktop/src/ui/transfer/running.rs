use super::*;

pub(super) fn running(app: &mut App, ui: &mut Ui, _now: f64) {
    let xfer = app.transfer.clone();
    let accent = app.accent_color();
    let guard = xfer.job.lock().unwrap();
    let Some(job) = guard.as_ref() else { return };

    ui.add_space(6.0);
    breadcrumb(app, ui);
    let title = match job.phase {
        Phase::Reading if job.name.is_empty() => "Reading from Spotify".to_owned(),
        Phase::Reading => format!("Reading {}", job.name),
        Phase::Saving => match &job.dest {
            Dest::Liked => "Adding to your Liked Music".to_owned(),
            Dest::Playlist(_) => "Creating the playlist".to_owned(),
        },
        _ => format!("Copying {}", job.name),
    };
    let (mut pause, mut cancel, mut check) = (false, false, false);
    let paused = job.paused;
    let to_check = (job.phase == Phase::Review).then(|| job.doubts.iter().filter(|d| d.decision.is_none()).count());
    ui.horizontal(|ui| {
        ui.vertical(|ui| {
            ui.set_width((ui.available_width() - if to_check.is_some() { 400.0 } else { 230.0 }).max(200.0));
            ui.add_space(4.0);
            ui.label(RichText::new(&title).font(theme::bold(28.0)).color(theme::text()));
            ui.add_space(2.0);
            dim(
                ui,
                if to_check.is_some() {
                    "Every song's been searched. Look over the matches below, then check the ones that need a look."
                } else {
                    "Keep listening or browse anywhere. This keeps running, and the sidebar shows its progress."
                },
            );
        });
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            cancel = pill(ui, "Cancel", None, false).clicked();
            ui.add_space(8.0);
            match to_check {
                Some(n) => {
                    let label = if n == 1 { "Check 1 song".to_owned() } else { format!("Check {} songs", fmt_n(n)) };
                    check = big_button(ui, &label, accent, true, 220.0);
                }
                None => pause = pill(ui, if paused { "Resume" } else { "Pause" }, None, false).clicked(),
            }
        });
    });
    ui.add_space(16.0);

    let total = job.tracks.len();
    let searched = job.searched();
    let (big, of, frac, eta) = match job.phase {
        Phase::Reading => (fmt_n(job.read.0), format!("of {} songs read from Spotify", fmt_n(job.read.1)), job.read.0 as f32 / job.read.1.max(1) as f32, None),
        Phase::Saving if job.waiting => ("…".to_owned(), "waiting for the likes being changed in the app to finish".to_owned(), 0.0, None),
        Phase::Saving if let Some((done, of)) = job.redo => (
            fmt_n(done),
            format!("of {} songs liked again: YouTube Music dropped them", fmt_n(of)),
            done as f32 / of.max(1) as f32,
            Some("checking Liked Music".to_owned()),
        ),
        Phase::Saving => (
            fmt_n(job.saving.0),
            match job.dest {
                Dest::Liked => format!("of {} songs liked", fmt_n(job.saving.1)),
                Dest::Playlist(_) => format!("of {} songs added", fmt_n(job.saving.1)),
            },
            job.saving.0 as f32 / job.saving.1.max(1) as f32,
            None,
        ),
        Phase::Checking => (fmt_n(searched), format!("of {} songs searched", fmt_n(total)), 1.0, Some("checking the doubtful ones".to_owned())),
        Phase::Review => (fmt_n(searched), format!("of {} songs searched", fmt_n(total)), 1.0, None),
        _ => (fmt_n(searched), format!("of {} songs searched", fmt_n(total)), searched as f32 / total.max(1) as f32, job.eta().map(|s| format!("about {} left", fmt_secs(s)))),
    };
    let limited = app.backend.api().limited();
    let eta = if paused {
        Some("paused".to_owned())
    } else if limited && job.phase.running() {
        Some("YouTube Music is limiting requests; carrying on in a moment".to_owned())
    } else {
        eta
    };
    if limited {
        ui.ctx().request_repaint_after(std::time::Duration::from_secs(1));
    }
    settings_card(ui, |ui| {
        ui.add_space(16.0);
        ui.horizontal(|ui| {
            ui.label(RichText::new(&big).font(theme::bold(34.0)).color(theme::text()));
            ui.add_space(4.0);
            ui.label(RichText::new(&of).font(theme::regular(15.0)).color(theme::text_dim()));
            if let Some(e) = &eta {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| dim(ui, e));
            }
        });
        ui.add_space(10.0);
        let (bar, _) = ui.allocate_exact_size(vec2(ui.available_width(), 8.0), Sense::hover());
        ui.painter().rect_filled(bar, 4.0, theme::glass(0.12));
        let shown = theme::anim(ui.ctx(), "xfer-bar", frac.clamp(0.0, 1.0), 0.3);
        ui.painter().rect_filled(Rect::from_min_size(bar.min, vec2(bar.width() * shown, bar.height())), 4.0, accent);
        ui.add_space(12.0);
        let steps = [
            (Phase::Reading, match job.read_secs {
                Some(s) => format!("Read from Spotify · {s:.1} s"),
                None => "Read from Spotify".to_owned(),
            }),
            (Phase::Searching, "Search YouTube Music".to_owned()),
            (Phase::Checking, "Check doubtful songs".to_owned()),
            (Phase::Saving, match &job.dest {
                Dest::Liked => "Add to Liked Music".to_owned(),
                Dest::Playlist(_) => "Create playlist".to_owned(),
            }),
        ];
        let order = |p: &Phase| match p {
            Phase::Reading => 0,
            Phase::Searching => 1,
            Phase::Checking | Phase::Review => 2,
            _ => 3,
        };
        let at = order(&job.phase);
        ui.horizontal_wrapped(|ui| {
            for (i, (p, label)) in steps.iter().enumerate() {
                let k = order(p);
                if i > 0 {
                    ui.label(RichText::new("—").font(theme::regular(13.0)).color(theme::text_faint()));
                }
                let (text, font, color) = if k < at {
                    (format!("✓ {label}"), theme::regular(13.0), theme::text())
                } else if k == at {
                    (label.clone(), theme::semibold(13.0), theme::text())
                } else {
                    (label.clone(), theme::regular(13.0), theme::text_dim())
                };
                ui.label(RichText::new(text).font(font).color(color));
            }
        });
        ui.add_space(14.0);
    });
    ui.add_space(14.0);

    let found = job.count(|r| matches!(r, Search::Found(_)));
    let unsure = job.count(|r| matches!(r, Search::Unsure(_)));
    let missing = job.count(|r| *r == Search::Missing);
    let dups = duplicates(job);
    let stats = [
        (fmt_n(found), "Matched", false),
        (fmt_n(unsure), "Need a look at the end", unsure > 0),
        (fmt_n(missing), "Not found yet", false),
        (fmt_n(dups), "Duplicates, added once", false),
    ];
    stat_row(ui, &stats, accent);
    ui.add_space(16.0);
    if check {
        app.xfer.reviewing = true;
    }
    if total == 0 {
        drop(guard);
        apply_controls(app, pause, cancel, paused);
        return;
    }

    let mut tab = app.xfer.tab;
    ui.horizontal(|ui| {
        for (t, label) in [
            (Tab::All, "All".to_owned()),
            (Tab::Matched, format!("Matched {}", fmt_n(found))),
            (Tab::Look, format!("Needs a look {}", fmt_n(unsure))),
            (Tab::Missing, format!("Not found {}", fmt_n(missing))),
        ] {
            if widgets::chip(ui, &label, tab == t).clicked() {
                tab = t;
            }
        }
    });
    app.xfer.tab = tab;
    ui.add_space(10.0);
    let rows: Vec<usize> = (0..total)
        .filter(|&i| match tab {
            Tab::All => true,
            Tab::Matched => matches!(job.results[i], Search::Found(_)),
            Tab::Look => matches!(job.results[i], Search::Unsure(_)),
            Tab::Missing => job.results[i] == Search::Missing,
        })
        .collect();
    let mut acts = Vec::new();
    egui::Frame::new().fill(theme::glass(0.04)).corner_radius(theme::panel_radius()).inner_margin(egui::Margin::symmetric(10, 8)).show(ui, |ui| {
        ui.set_width(ui.available_width());
        list_header(ui);
        if rows.is_empty() {
            ui.add_space(10.0);
            dim(ui, "Nothing here yet.");
            ui.add_space(10.0);
        }
        widgets::visible_rows(ui, rows.len(), |ui, k| {
            let i = rows[k];
            song_row(ui, i, &job.tracks[i], &job.results[i], accent, &mut acts);
        });
    });
    drop(guard);
    app.actions.extend(acts);
    apply_controls(app, pause, cancel, paused);
}

fn apply_controls(app: &mut App, pause: bool, cancel: bool, paused: bool) {
    if pause {
        app.transfer.set_paused(!paused);
    }
    if cancel {
        app.transfer.cancel();
        app.toast("Transfer cancelled");
    }
}

fn duplicates(job: &Job) -> usize {
    let mut seen = std::collections::HashSet::new();
    job.results.iter().filter_map(Search::track).filter(|t| !seen.insert(t.id.as_str())).count()
}

pub(super) fn stat_row(ui: &mut Ui, stats: &[(String, &str, bool)], accent: Color32) {
    let n = stats.len() as f32;
    let gap = 12.0;
    let w = (ui.available_width() - gap * (n - 1.0)) / n;
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = gap;
        for (value, label, ring) in stats {
            let (rect, _) = ui.allocate_exact_size(vec2(w, 66.0), Sense::hover());
            ui.painter().rect_filled(rect, 12.0, theme::glass(0.06));
            if *ring {
                ui.painter().rect_stroke(rect, 12.0, egui::Stroke::new(1.5, accent), egui::StrokeKind::Inside);
            }
            ui.painter().text(pos2(rect.left() + 16.0, rect.top() + 12.0), Align2::LEFT_TOP, value, theme::bold(22.0), theme::text());
            text_at(ui, pos2(rect.left() + 16.0, rect.top() + 41.0), label, theme::regular(12.5), theme::text_dim(), w - 24.0);
        }
    });
}
