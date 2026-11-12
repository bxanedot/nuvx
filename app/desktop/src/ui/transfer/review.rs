use super::*;

fn columns(rect: Rect) -> (f32, f32, f32, f32, f32) {
    let status_w = 140.0;
    let num = rect.left() + 8.0;
    let sp = rect.left() + 52.0;
    let half = (rect.width() - 52.0 - 28.0 - status_w - 24.0) / 2.0;
    let arrow = sp + half + 12.0;
    let yt = arrow + 28.0;
    let status = rect.right() - status_w;
    (num, sp, arrow, yt, status.max(yt + 40.0))
}

pub(super) fn list_header(ui: &mut Ui) {
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 28.0), Sense::hover());
    let (num, sp, _, yt, status) = columns(rect);
    let c = theme::text_faint();
    let f = theme::bold(11.0);
    for (x, t) in [(num, "#"), (sp, "ON SPOTIFY"), (yt, "ON YOUTUBE MUSIC"), (status, "STATUS")] {
        ui.painter().text(pos2(x, rect.center().y), Align2::LEFT_CENTER, t, f.clone(), c);
    }
}

pub(super) fn song_row(ui: &mut Ui, i: usize, sp: &Track, result: &Search, accent: Color32, acts: &mut Vec<super::Action>) {
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 56.0), Sense::hover());
    let (num, spx, arrow, ytx, status) = columns(rect);
    let half = arrow - spx - 12.0;
    if resp.hovered() {
        ui.painter().rect_filled(rect, 8.0, theme::glass(0.04));
    }
    ui.painter().text(pos2(num, rect.center().y), Align2::LEFT_CENTER, fmt_n(i + 1), theme::regular(12.5), theme::text_faint());
    let img = Rect::from_min_size(pos2(spx, rect.center().y - 19.0), vec2(38.0, 38.0));
    paint_cover(ui, img, &spotify_thumbs(&sp.art), theme::thumb_radius(), 1.0);
    let tw = half - 50.0;
    text_at(ui, pos2(img.right() + 10.0, rect.top() + 9.0), &sp.name, theme::semibold(13.5), theme::text(), tw);
    let artist = if sp.local { format!("{} · local file", sp.artist_line()) } else { sp.artist_line() };
    text_at(ui, pos2(img.right() + 10.0, rect.top() + 29.0), &artist, theme::regular(12.0), theme::text_dim(), tw);
    icon_at(ui, pos2(arrow + 10.0, rect.center().y), icon::CHEVRON_RIGHT, 16.0, theme::text_faint());
    let yw = status - ytx - 16.0;
    let (label, dot, fg) = match result {
        Search::Pending => ("Searching…", theme::glass(0.2), theme::text_dim()),
        Search::Found(_) => ("Matched", theme::text(), theme::text()),
        Search::Unsure(_) => ("Needs a look", accent, accent),
        Search::Missing => ("Not found", theme::text_faint(), theme::text_dim()),
        Search::Skipped => ("Skipped", theme::text_faint(), theme::text_dim()),
    };
    match result.track() {
        Some(t) => {
            let img = Rect::from_min_size(pos2(ytx, rect.center().y - 19.0), vec2(38.0, 38.0));
            paint_cover(ui, img, &t.thumbs, theme::thumb_radius(), 1.0);
            let r = ui.interact(img, ui.scope_id().with(("prev", i)), Sense::click());
            if named(r.clone(), "Play this match").on_hover_text("Play").on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                acts.push(Action::Play(vec![t.clone()], 0));
            }
            if r.hovered() {
                ui.painter().rect_filled(img, theme::thumb_radius(), theme::shade(0.45));
                widgets::play_triangle(ui, img.center(), 7.0, Color32::WHITE);
            }
            text_at(ui, pos2(img.right() + 10.0, rect.top() + 9.0), &t.title, theme::semibold(13.5), theme::text(), yw - 48.0);
            let meta = match &t.album {
                Some(a) => format!("{} • {}", t.artist_line(), a.name),
                None => t.artist_line(),
            };
            text_at(ui, pos2(img.right() + 10.0, rect.top() + 29.0), &meta, theme::regular(12.0), theme::text_dim(), yw - 48.0);
        }
        None => {
            let t = if *result == Search::Pending { "" } else { "No result" };
            ui.painter().text(pos2(ytx, rect.center().y), Align2::LEFT_CENTER, t, theme::regular(13.0), theme::text_faint());
        }
    }
    ui.painter().circle_filled(pos2(status + 4.0, rect.center().y), 4.0, dot);
    ui.painter().text(pos2(status + 16.0, rect.center().y), Align2::LEFT_CENTER, label, theme::semibold(13.0), fg);
}

pub(super) fn review(app: &mut App, ui: &mut Ui) {
    let xfer = app.transfer.clone();
    let accent = app.accent_color();
    let guard = xfer.job.lock().unwrap();
    let Some(job) = guard.as_ref() else { return };
    let total = job.doubts.len();
    let left = job.doubts.iter().filter(|d| d.decision.is_none()).count();
    let Some(at) = app.xfer.focus.filter(|&k| k < total).or_else(|| job.current_doubt()) else { return };
    let d = &job.doubts[at];
    let sp = &job.tracks[d.index];
    let current = job.results[d.index].track().and_then(|t| d.candidates.iter().position(|c| c.id == t.id));
    let current_label = job.results[d.index].track().filter(|_| current.is_none()).cloned();
    let decided_pick = match d.decision {
        Some(Decision::Pick(k)) => Some(k),
        _ => None,
    };
    let picked = app.xfer.pick.filter(|(k, _)| *k == at).map(|(_, c)| c).or(decided_pick).or(current);
    let query = match sp.artists.first() {
        Some(a) if !a.is_empty() => format!("{} {a}", sp.name),
        _ => sp.name.clone(),
    };
    let mut search = false;

    ui.add_space(6.0);
    breadcrumb(app, ui);
    let mut finish = false;
    let mut list = false;
    ui.horizontal(|ui| {
        ui.vertical(|ui| {
            ui.set_width((ui.available_width() - 300.0).max(200.0));
            ui.add_space(4.0);
            let title = match left {
                0 => "Everything's checked".to_owned(),
                1 => "Check 1 song".to_owned(),
                n => format!("Check {} songs", fmt_n(n)),
            };
            ui.label(RichText::new(title).font(theme::bold(28.0)).color(theme::text()));
            ui.add_space(2.0);
            dim(ui, "These didn't match cleanly. Pick the right one, skip it, or keep the current pick. Click a cover to play it.");
        });
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            finish = pill(ui, "Keep current picks for the rest", None, false).clicked();
            ui.add_space(8.0);
            if pill(ui, "All songs", Some(icon::CHEVRON_LEFT), false).on_hover_text("Back to the full list of matches").clicked() {
                list = true;
            }
        });
    });
    ui.add_space(12.0);
    let (bar, _) = ui.allocate_exact_size(vec2(ui.available_width(), 6.0), Sense::hover());
    ui.painter().rect_filled(bar, 3.0, theme::glass(0.12));
    ui.painter().rect_filled(Rect::from_min_size(bar.min, vec2(bar.width() * (total - left) as f32 / total.max(1) as f32, 6.0)), 3.0, accent);
    ui.add_space(14.0);

    let mut decision = None;
    let mut back = false;
    let mut focus_to: Option<usize> = None;
    let mut edit: Option<usize> = None;
    let mut acts = Vec::new();
    let wide = ui.available_width() > 820.0;
    let queue_w = if wide { 260.0 } else { 0.0 };
    ui.horizontal_top(|ui| {
        if wide {
            ui.vertical(|ui| {
                ui.set_width(queue_w);
                let card_h = if app.xfer.card_h > 0.0 { app.xfer.card_h } else { 560.0 };
                egui::Frame::new().fill(theme::glass(0.04)).corner_radius(theme::panel_radius()).inner_margin(10).show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.set_height(card_h - 20.0);
                    let matched: Vec<usize> = (0..job.results.len()).filter(|&i| matches!(job.results[i], Search::Found(_))).collect();
                    ui.horizontal(|ui| {
                        if widgets::chip(ui, &format!("To check {}", fmt_n(total)), !app.xfer.show_matched).clicked() {
                            app.xfer.show_matched = false;
                        }
                        if widgets::chip(ui, &format!("Matched {}", fmt_n(matched.len())), app.xfer.show_matched).clicked() {
                            app.xfer.show_matched = true;
                        }
                    });
                    ui.add_space(8.0);
                    const ROW: f32 = 46.0;
                    let list_h = ui.available_height();
                    let n = if app.xfer.show_matched { matched.len() } else { total };
                    let mut area = egui::ScrollArea::vertical().id_salt(("xfer-queue", app.xfer.show_matched)).auto_shrink([false, false]).max_height(list_h);
                    if !app.xfer.show_matched && app.xfer.scrolled_to != Some(at) {
                        app.xfer.scrolled_to = Some(at);
                        let spacing = ui.spacing().item_spacing.y;
                        area = area.vertical_scroll_offset((at as f32 * (ROW + spacing) - list_h / 2.0 + ROW / 2.0).max(0.0));
                    }
                    area.show_rows(ui, ROW, n, |ui, range| {
                        for r in range {
                            let (i, mark, on) = if app.xfer.show_matched {
                                let i = matched[r];
                                (i, "", job.doubts.get(at).is_some_and(|d| d.index == i))
                            } else {
                                let mark = match job.doubts[r].decision {
                                    Some(Decision::Skip) => "Skipped",
                                    Some(_) => "✓",
                                    None => "",
                                };
                                (job.doubts[r].index, mark, r == at)
                            };
                            let t = &job.tracks[i];
                            let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), ROW), Sense::click());
                            let resp = named(resp, &t.name).on_hover_cursor(egui::CursorIcon::PointingHand);
                            let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
                            ui.painter().rect_filled(rect, 8.0, theme::glass(if on { 0.08 } else { 0.04 * h }));
                            let img = Rect::from_min_size(pos2(rect.left() + 6.0, rect.center().y - 17.0), vec2(34.0, 34.0));
                            paint_cover(ui, img, &spotify_thumbs(&t.art), 4.0, 1.0);
                            let mw = if mark.is_empty() { 0.0 } else { 54.0 };
                            let tw = rect.right() - img.right() - 16.0 - mw;
                            text_at(ui, pos2(img.right() + 10.0, rect.top() + 6.0), &t.name, theme::semibold(13.0), theme::text(), tw);
                            text_at(ui, pos2(img.right() + 10.0, rect.top() + 25.0), &t.artist_line(), theme::regular(12.0), theme::text_dim(), tw);
                            if !mark.is_empty() {
                                ui.painter().text(pos2(rect.right() - 6.0, rect.center().y), Align2::RIGHT_CENTER, mark, theme::regular(12.0), theme::text_dim());
                            }
                            if resp.clicked() {
                                if app.xfer.show_matched {
                                    edit = Some(i);
                                } else {
                                    focus_to = Some(r);
                                }
                            }
                        }
                    });
                });
            });
            ui.add_space(18.0);
        }
        let card = ui.vertical(|ui| {
            ui.set_width(ui.available_width());
            settings_card(ui, |ui| {
                ui.add_space(18.0);
                let (head, _) = ui.allocate_exact_size(vec2(ui.available_width(), 64.0), Sense::hover());
                let img = Rect::from_min_size(head.min, vec2(64.0, 64.0));
                paint_cover(ui, img, &spotify_thumbs(&sp.art), 8.0, 1.0);
                let x = img.right() + 16.0;
                let tw = head.right() - x;
                let state = match d.decision {
                    Some(Decision::Skip) => " · SKIPPED",
                    Some(_) => " · CHECKED",
                    None => "",
                };
                ui.painter().text(pos2(x, head.top() + 1.0), Align2::LEFT_TOP, format!("ON SPOTIFY · {} OF {}{state}", fmt_n(at + 1), fmt_n(total)), theme::bold(11.5), theme::text_faint());
                text_at(ui, pos2(x, head.top() + 17.0), &sp.name, theme::bold(21.0), theme::text(), tw);
                let r = text_at(ui, pos2(x, head.top() + 45.0), &sp.artist_line(), theme::regular(14.0), theme::text_dim(), tw - 100.0);
                if sp.local {
                    let gx = if sp.artists.is_empty() { x } else { r.right() + 10.0 };
                    let g = widgets::galley(ui, "LOCAL FILE", theme::bold(10.5), theme::text(), 200.0);
                    tag(ui, pos2(gx + g.size().x + 14.0, head.top() + 54.0), "LOCAL FILE", theme::glass(0.14));
                }
                ui.add_space(10.0);
                let (line, _) = ui.allocate_exact_size(vec2(ui.available_width(), 1.0), Sense::hover());
                ui.painter().rect_filled(line, 0.0, theme::glass(0.10));
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new("PICK THE RIGHT ONE ON YOUTUBE MUSIC").font(theme::bold(11.5)).color(theme::text_faint()));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        search = pill(ui, "Search YouTube Music", Some(icon::SEARCH), false).on_hover_text("Not in the list? Find it yourself").clicked();
                    });
                });
                ui.add_space(8.0);
                let room = (ui.ctx().content_rect().height() - 560.0).clamp(52.0 * 3.5, 52.0 * 8.0);
                egui::ScrollArea::vertical()
                    .id_salt(("xfer-cands", at))
                    .auto_shrink([false, false])
                    .min_scrolled_height(room)
                    .max_height(room)
                    .show(ui, |ui| {
                        if let Some(t) = &current_label {
                            let _ = candidate_row(ui, t, false, true, false, accent, &mut acts);
                        }
                        if d.loading {
                            ui.add_space(8.0);
                            ui.horizontal(|ui| {
                                ui.spinner();
                                dim(ui, "Finding other versions…");
                            });
                        } else if d.candidates.is_empty() && current_label.is_none() {
                            dim(ui, "Nothing on YouTube Music looks like this song.");
                        }
                        for (k, c) in d.candidates.iter().enumerate() {
                            if candidate_row(ui, c, d.videos[k], current == Some(k), picked == Some(k), accent, &mut acts) {
                                app.xfer.pick = Some((at, k));
                            }
                        }
                    });
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    if pill(ui, "Skip this song", None, false).on_hover_text("Skipped songs are listed at the end").clicked() {
                        decision = Some(Decision::Skip);
                    }
                    ui.add_space(4.0);
                    if at > 0 && pill(ui, "Previous song", Some(icon::CHEVRON_LEFT), false).clicked() {
                        back = true;
                    }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let has_current = current.is_some() || current_label.is_some();
                        let (label, choice) = match picked {
                            Some(k) if Some(k) != current => ("Use selected", Decision::Pick(k)),
                            _ if has_current => ("Keep current pick", Decision::Keep),
                            _ => ("Leave it out", Decision::Skip),
                        };
                        if big_button(ui, label, accent, true, 200.0) {
                            decision = Some(choice);
                        }
                    });
                });
                ui.add_space(14.0);
            });
        });
        app.xfer.card_h = card.response.rect.height();
    });
    let next_open = |from: usize| (from + 1..total).chain(0..from).find(|&k| job.doubts[k].decision.is_none());
    let next = next_open(at);
    drop(guard);
    app.actions.extend(acts);
    if list {
        app.xfer.reviewing = false;
        return;
    }
    if search {
        app.xfer.picking = Some(at);
        app.xfer.pick = None;
        app.actions.push(Action::Go(Page::Search(query)));
        return;
    }
    if let Some(k) = focus_to {
        app.xfer.focus = Some(k);
        app.xfer.pick = None;
        return;
    }
    if let Some(i) = edit {
        app.xfer.focus = xfer.edit_match(&app.backend, i);
        app.xfer.pick = None;
        app.xfer.show_matched = false;
        return;
    }
    let now = ui.input(|i| i.time);
    if (back || decision.is_some() || finish) && now - app.xfer.decided_at < 0.4 {
        return;
    }
    if back || decision.is_some() {
        app.xfer.decided_at = now;
    }
    if back {
        app.xfer.focus = Some(at - 1);
        app.xfer.pick = None;
    } else if let Some(d) = decision {
        app.xfer.focus = next;
        xfer.decide(at, d);
        app.xfer.pick = None;
    } else if finish {
        xfer.finish_review();
    }
}
