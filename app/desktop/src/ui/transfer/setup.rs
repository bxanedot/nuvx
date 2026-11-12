use super::*;

pub(super) fn setup(app: &mut App, ui: &mut Ui) {
    let xfer = app.transfer.clone();
    let accent = app.accent_color();
    ui.add_space(6.0);
    breadcrumb(app, ui);
    ui.add_space(6.0);
    ui.horizontal(|ui| {
        let (r, _) = ui.allocate_exact_size(vec2(40.0, 40.0), Sense::hover());
        badge(ui, r);
        ui.add_space(6.0);
        ui.label(RichText::new("Transfer from Spotify").font(theme::bold(30.0)).color(theme::text()));
    });
    ui.add_space(4.0);
    dim(ui, "Copy your Liked Songs or any playlist into YouTube Music. It runs in the background, so you can keep listening.");
    ui.add_space(18.0);

    if !app.logged_in {
        settings_card(ui, |ui| {
            setting_row(ui, "Sign in to YouTube Music first", Some("The songs go into your YouTube Music account."), |ui| {
                if pill(ui, "Go to Settings", None, true).clicked() {
                    app.actions.push(Action::Go(Page::Setting(crate::ui::SettingsPane::Account)));
                }
            });
        });
        return;
    }

    let resumable = xfer.resumable.lock().unwrap().clone();
    if let Some(r) = resumable {
        let mut go = None;
        settings_card(ui, |ui| {
            let desc = format!("{} of {} songs searched. Nothing is searched twice.", fmt_n(r.searched), fmt_n(r.total));
            setting_row(ui, &format!("Pick up \"{}\" where it stopped", r.name), Some(&desc), |ui| {
                if pill(ui, "Discard", None, false).clicked() {
                    go = Some(false);
                }
                ui.add_space(8.0);
                if pill(ui, "Resume", None, true).clicked() {
                    go = Some(true);
                }
            });
        });
        if !app.xfer.dev && crate::ui::dev_var("NUVX_TRANSFER_START") == Some("resume") {
            app.xfer.dev = true;
            go = Some(true);
        }
        match go {
            Some(true) => xfer.start(&app.backend, r.source, r.dest, true),
            Some(false) => xfer.discard_saved(&r.source),
            None => {}
        }
        ui.add_space(18.0);
    }

    let account = xfer.account.lock().unwrap().clone();
    let library: &[Entry] = match &account {
        Account::SignedIn { library, .. } => library,
        _ => &[],
    };
    let link_entry = {
        if !app.xfer.link.trim().is_empty() {
            xfer.look_up(&app.backend, &app.xfer.link);
        }
        xfer.lookup.lock().unwrap().clone().filter(|(l, _)| *l == app.xfer.link.trim())
    };
    let pasted = link_entry.as_ref().and_then(|(_, r)| r.as_ref()?.as_ref().ok().cloned());
    let chosen: Option<Entry> = match &app.xfer.picked {
        Some(s) => library.iter().chain(pasted.iter()).find(|e| &e.source == s).cloned(),
        None => None,
    };
    let chosen = chosen.or_else(|| pasted.clone());

    let wide = ui.available_width() > 820.0;
    let gap = 18.0;
    let left_w = if wide { (ui.available_width() - gap) * 0.58 } else { ui.available_width() };
    let mut start = false;
    let mut body = |ui: &mut Ui, col: usize| {
        if col == 0 {
            settings_card(ui, |ui| {
                ui.add_space(14.0);
                small_caps(ui, "1 · WHAT TO COPY");
                match &account {
                    Account::SignedIn { .. } if library.is_empty() => dim(ui, "Your Spotify library has no playlists."),
                    Account::SignedIn { .. } => {
                        let row = 64.0 + ui.spacing().item_spacing.y;
                        let h = row * library.len().min(5) as f32 - ui.spacing().item_spacing.y;
                        egui::ScrollArea::vertical()
                            .id_salt("xfer-library")
                            .auto_shrink([false, true])
                            .min_scrolled_height(h)
                            .max_height(h)
                            .show_rows(ui, 64.0, library.len(), |ui, range| {
                                for e in &library[range] {
                                    source_row(app, ui, e, chosen.as_ref().is_some_and(|c| c.source == e.source), accent);
                                }
                            });
                    }
                    Account::Unknown | Account::Loading => {
                        ui.horizontal(|ui| {
                            ui.spinner();
                            dim(ui, "Reading your Spotify library…");
                        });
                    }
                    Account::SignedOut | Account::Failed(_) => {
                        dim(ui, "Sign in to Spotify to pick from your playlists and Liked Songs. Public playlists work without signing in: paste a link below.");
                    }
                }
                ui.add_space(14.0);
                dim(ui, "Or paste any playlist link");
                ui.add_space(6.0);
                let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
                ui.painter().rect_filled(rect, 10.0, theme::glass(0.08));
                let inner = rect.shrink2(vec2(14.0, 4.0));
                let edit = ui.put(
                    inner,
                    egui::TextEdit::singleline(&mut app.xfer.link)
                        .hint_text(RichText::new("https://open.spotify.com/playlist/…").color(theme::text_faint()))
                        .font(theme::regular(14.0))
                        .frame(egui::Frame::NONE)
                        .align(egui::Align2::LEFT_CENTER)
                        .desired_width(inner.width()),
                );
                if edit.changed() {
                    app.xfer.picked = None;
                }
                match &link_entry {
                    Some((_, None)) => {
                        ui.add_space(8.0);
                        ui.horizontal(|ui| {
                            ui.spinner();
                            dim(ui, "Looking it up…");
                        });
                    }
                    Some((_, Some(Err(e)))) => {
                        ui.add_space(8.0);
                        ui.label(RichText::new(e).font(theme::regular(13.0)).color(theme::RED));
                    }
                    Some((_, Some(Ok(e)))) => {
                        ui.add_space(8.0);
                        source_row(app, ui, e, chosen.as_ref().is_some_and(|c| c.source == e.source), accent);
                    }
                    None => {}
                }
                ui.add_space(14.0);
            });
        } else {
            settings_card(ui, |ui| {
                ui.add_space(14.0);
                small_caps(ui, "2 · SPOTIFY ACCOUNT");
                spotify_account_row(app, ui, &account, true);
                ui.add_space(10.0);
            });
            ui.add_space(gap);
            settings_card(ui, |ui| {
                ui.add_space(14.0);
                destination(app, ui, chosen.as_ref(), accent);
                ui.add_space(14.0);
            });
            ui.add_space(gap);
            let needs_sign_in = chosen.as_ref().is_some_and(|c| c.source == Source::Liked) && !matches!(account, Account::SignedIn { .. });
            let ready = chosen.is_some() && !needs_sign_in;
            if big_button(ui, "Start transfer", accent, ready, ui.available_width()) {
                start = true;
            }
            ui.add_space(8.0);
            let estimate = match chosen.as_ref().and_then(|c| c.count) {
                _ if needs_sign_in => "Sign in to Spotify to copy your Liked Songs.".to_owned(),
                Some(n) => {
                    let secs = (n as u64 / 20).max(5);
                    format!("About {} to search {} songs. You can keep listening meanwhile.", fmt_secs(secs), fmt_n(n))
                }
                None if chosen.is_some() => "You can keep listening while it runs.".to_owned(),
                None => "Pick a playlist to copy.".to_owned(),
            };
            ui.vertical_centered(|ui| dim(ui, &estimate));
        }
    };
    if wide {
        ui.horizontal_top(|ui| {
            ui.vertical(|ui| {
                ui.set_width(left_w);
                body(ui, 0);
            });
            ui.add_space(gap);
            ui.vertical(|ui| {
                ui.set_width(ui.available_width());
                body(ui, 1);
            });
        });
    } else {
        body(ui, 0);
        ui.add_space(gap);
        body(ui, 1);
    }
    if !app.xfer.dev
        && let Some(how) = crate::ui::dev_var("NUVX_TRANSFER_START")
        && chosen.is_some()
    {
        app.xfer.dev = true;
        app.xfer.to_liked = how == "liked";
        start = true;
    }
    if start && let Some(c) = chosen {
        let liked = app.xfer.to_liked && (likes_like(&c) || crate::ui::dev_var("NUVX_TRANSFER_START") == Some("liked"));
        let dest = if liked { Dest::Liked } else { Dest::Playlist(app.xfer.name.trim().to_owned()) };
        xfer.start(&app.backend, c.source.clone(), dest, false);
        app.xfer.tab = Tab::All;
        app.xfer.pick = None;
        app.xfer.focus = None;
        app.xfer.scrolled_to = None;
        app.xfer.show_matched = false;
        app.xfer.seen = None;
    }
}

fn likes_like(e: &Entry) -> bool {
    e.source == Source::Liked || is_likes(&e.name)
}

fn source_row(app: &mut App, ui: &mut Ui, e: &Entry, selected: bool, accent: Color32) {
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 64.0), Sense::click());
    if !ui.is_rect_visible(rect) {
        return;
    }
    let resp = named(resp, &e.name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    let s = theme::anim_bool(ui.ctx(), resp.id.with("s"), selected, 0.15);
    ui.painter().rect_filled(rect, 12.0, theme::glass(0.04 * h + 0.06 * s));
    if s > 0.0 {
        ui.painter().rect_stroke(rect, 12.0, egui::Stroke::new(1.5, theme::with_alpha(accent, s)), egui::StrokeKind::Inside);
    }
    let img = Rect::from_min_size(pos2(rect.left() + 10.0, rect.center().y - 24.0), vec2(48.0, 48.0));
    if e.source == Source::Liked {
        ui.painter().rect_filled(img, 8.0, Color32::from_rgb(80, 56, 200));
        widgets::heart(ui, img.center(), 22.0, 1.0, Color32::WHITE, Color32::WHITE);
    } else {
        paint_cover(ui, img, &spotify_thumbs(&e.art), 8.0, 1.0);
    }
    let x = img.right() + 14.0;
    let tw = rect.right() - x - 44.0;
    text_at(ui, pos2(x, rect.center().y - 19.0), &e.name, theme::semibold(15.0), theme::text(), tw);
    let meta = match (&e.source, e.count) {
        (Source::Liked, Some(n)) => format!("{} songs", fmt_n(n)),
        (Source::Liked, None) => "Your liked songs".to_owned(),
        (_, Some(n)) if !e.owner.is_empty() => format!("Playlist · {} · {} songs", e.owner, fmt_n(n)),
        (_, Some(n)) => format!("Playlist · {} songs", fmt_n(n)),
        _ if !e.owner.is_empty() => format!("Playlist · {}", e.owner),
        _ => "Playlist".to_owned(),
    };
    text_at(ui, pos2(x, rect.center().y + 2.0), &meta, theme::regular(13.0), theme::text_dim(), tw);
    radio(ui, pos2(rect.right() - 22.0, rect.center().y), s, accent);
    if resp.clicked() {
        app.xfer.picked = Some(e.source.clone());
    }
}

pub(super) fn spotify_account_row(app: &mut App, ui: &mut Ui, account: &Account, compact: bool) {
    let xfer = app.transfer.clone();
    let busy = xfer.signing_in.load(Ordering::Relaxed);
    let (mut sign_in, mut sign_out) = (false, false);
    let (title, desc) = match account {
        Account::SignedIn { name, .. } if compact => (
            if name.is_empty() { "Signed in".to_owned() } else { name.clone() },
            "Signed in · works with Free and Premium".to_owned(),
        ),
        Account::SignedIn { name, .. } => {
            let who = if name.is_empty() { "Signed in".to_owned() } else { format!("Signed in as {name}") };
            ("Spotify".to_owned(), format!("{who}. Saved encrypted in Windows Credential Manager."))
        }
        Account::Unknown | Account::Loading => ("Spotify".to_owned(), "Checking your sign-in…".to_owned()),
        Account::SignedOut if compact => ("Not signed in".to_owned(), "Needed for Liked Songs and private playlists. Public playlists work without it.".to_owned()),
        Account::SignedOut => ("Spotify".to_owned(), "Sign in to load your playlists and Liked Songs. Playback uses YouTube Music.".to_owned()),
        Account::Failed(e) => ("Spotify".to_owned(), e.clone()),
    };
    let signed_in = matches!(account, Account::SignedIn { .. } | Account::Unknown | Account::Loading);
    crate::ui::pages::glyph_row(ui, (!compact).then_some(crate::ui::glyphs::SPOTIFY), &title, Some(&desc), |ui| {
        if busy {
            ui.spinner();
        } else if signed_in {
            sign_out = pill(ui, if compact { "Change" } else { "Sign out" }, None, false).clicked();
        } else {
            sign_in = pill(ui, "Sign in to Spotify", None, true).clicked();
        }
    });
    if busy {
        dim(ui, "Finish signing in in the window that opened.");
    }
    if sign_out {
        xfer.set_cookie(None, &app.backend);
        app.xfer.picked = None;
        if compact {
            sign_in = true;
        }
    }
    if sign_in {
        app.start_spotify_sign_in(ui.ctx());
    }
}

fn destination(app: &mut App, ui: &mut Ui, chosen: Option<&Entry>, accent: Color32) {
    let src = chosen.map(|c| c.source.clone());
    if app.xfer.name_for != src {
        if let Some(c) = chosen {
            app.xfer.name = format!("{} from Spotify", c.name);
            app.xfer.to_liked = likes_like(c);
        }
        app.xfer.name_for = src;
    }
    let liked_offer = chosen.is_some_and(likes_like);
    if liked_offer {
        small_caps(ui, "3 · WHERE SHOULD THEY GO?");
        for (to_liked, title, desc) in [
            (true, "Add to your Liked Music", "Likes each song on YouTube Music, in Spotify's order. Songs you already like stay as they are."),
            (false, "Make a new playlist", "A private playlist with these songs."),
        ] {
            let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 62.0), Sense::click());
            let resp = named(resp, title).on_hover_cursor(egui::CursorIcon::PointingHand);
            let on = theme::anim_bool(ui.ctx(), resp.id.with("on"), app.xfer.to_liked == to_liked, 0.15);
            let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
            ui.painter().rect_filled(rect, 10.0, theme::glass(0.03 * h + 0.05 * on));
            radio(ui, pos2(rect.left() + 18.0, rect.center().y), on, accent);
            let x = rect.left() + 38.0;
            text_at(ui, pos2(x, rect.top() + 11.0), title, theme::semibold(14.5), theme::text(), rect.width() - 46.0);
            let g = widgets::galley_wrapped(ui, desc, theme::regular(12.5), theme::text_dim(), rect.width() - 46.0, 2);
            ui.painter().galley(pos2(x, rect.top() + 31.0), g, theme::text_dim());
            if resp.clicked() {
                app.xfer.to_liked = to_liked;
            }
        }
        if app.xfer.to_liked {
            return;
        }
        ui.add_space(10.0);
    } else {
        small_caps(ui, "3 · NEW YOUTUBE MUSIC PLAYLIST");
    }
    dim(ui, "Name");
    ui.add_space(6.0);
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
    ui.painter().rect_filled(rect, 10.0, theme::glass(0.08));
    let inner = rect.shrink2(vec2(14.0, 4.0));
    ui.put(
        inner,
        egui::TextEdit::singleline(&mut app.xfer.name)
            .hint_text(RichText::new("Same as on Spotify").color(theme::text_faint()))
            .font(theme::regular(14.0))
            .frame(egui::Frame::NONE)
            .align(egui::Align2::LEFT_CENTER)
            .desired_width(inner.width()),
    );
    ui.add_space(10.0);
    dim(ui, "Private · only you can see it");
}
