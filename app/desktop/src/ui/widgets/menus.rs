use super::*;

pub(super) fn card_menu_contents(ui: &mut Ui, c: &Card, liked: bool, acts: &mut Vec<Action>) {
    match &c.target {
        Target::Song(t) => song_menu(ui, t, liked, None, acts),
        t => {
            ui.set_width(226.0);
            ui.add_space(3.0);
            if menu_action(ui, Some(icon::PLAY), "Play") {
                acts.push(Action::PlayCollection(t.clone(), false));
            }
            if menu_action(ui, Some(icon::SHUFFLE), "Shuffle play") {
                acts.push(Action::PlayCollection(t.clone(), true));
            }
            if menu_action(ui, None, "Open") {
                acts.push(Action::Open(t.clone()));
            }
            ui.separator();
            if menu_action(ui, None, "Copy link") {
                acts.push(Action::Share(t.clone()));
            }
            ui.add_space(3.0);
        }
    }
}

pub(super) fn more_button(ui: &mut Ui, rect: Rect, id: egui::Id, label: &str) -> Response {
    let response = named(ui.interact(rect, id, Sense::click()), label)
        .on_hover_cursor(egui::CursorIcon::PointingHand);
    if response.hovered() || egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&response)) {
        ui.painter().rect_filled(rect, 7.0, theme::glass(0.16));
    }
    let color = if response.hovered() { theme::text() } else { theme::text_dim() };
    icon_at(ui, rect.center(), icon::ELLIPSIS_VERTICAL, 18.0, color);
    response
}

fn menu_row(ui: &mut Ui, label: &str, draw: impl FnOnce(&Ui, Pos2, Color32)) -> bool {
    let (rect, response) = ui.allocate_exact_size(vec2(ui.available_width(), 34.0), Sense::click());
    let response = named(response, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    if response.hovered() {
        ui.painter().rect_filled(rect, 7.0, theme::glass(0.12));
    }
    let color = if response.hovered() { theme::text() } else { theme::text_dim() };
    draw(ui, pos2(rect.right() - 19.0, rect.center().y), color);
    ui.painter().text(pos2(rect.left() + 12.0, rect.center().y), Align2::LEFT_CENTER, label, theme::regular(14.0), theme::text());
    if response.clicked() { ui.close(); }
    response.clicked()
}

pub fn menu_action(ui: &mut Ui, glyph: Option<char>, label: &str) -> bool {
    menu_row(ui, label, |ui, at, color| match glyph {
        Some(glyph) => icon_at(ui, at, glyph, 16.0, color),
        None => {
            ui.painter().text(at, Align2::CENTER_CENTER, "↗", theme::semibold(17.0), color);
        }
    })
}

pub fn menu_choice(ui: &mut Ui, picked: bool, label: &str) -> bool {
    menu_row(ui, label, |ui, at, color| {
        if picked {
            icon_at(ui, at, icon::CHECK, 16.0, color);
        }
    })
}

pub fn menu_glyph_action(ui: &mut Ui, glyph: super::super::glyphs::Glyph, label: &str) -> bool {
    menu_row(ui, label, |ui, at, color| super::super::glyphs::draw(ui, at, 16.0, glyph, color))
}

fn menu_action_star(ui: &mut Ui, liked: bool, label: &str) -> bool {
    menu_glyph_action(ui, if liked { super::super::glyphs::STAR_FILLED } else { super::super::glyphs::STAR }, label)
}

pub fn song_menu(ui: &mut Ui, t: &Track, liked: bool, remove: Option<usize>, acts: &mut Vec<Action>) {
    ui.set_width(226.0);
    ui.add_space(3.0);
    song_actions(ui, t, liked, remove, acts);
    ui.separator();
    if menu_action(ui, Some(icon::LIST_PLUS), "Play next") {
        acts.push(Action::PlayNext(t.clone()));
    }
    if menu_action(ui, Some(icon::LIST_MUSIC), "Add to queue") {
        acts.push(Action::Enqueue(t.clone()));
    }
    if menu_action(ui, Some(icon::LIST_MUSIC), "View queue") {
        acts.push(Action::ViewQueue);
    }
    if menu_action(ui, Some(icon::RADIO), "Start radio") {
        acts.push(Action::Radio(t.clone()));
    }
    ui.add_space(3.0);
}

pub fn song_actions(ui: &mut Ui, t: &Track, liked: bool, remove: Option<usize>, acts: &mut Vec<Action>) {
    if menu_action_star(ui, liked, if liked { "Remove like" } else { "Like song" }) {
        acts.push(Action::Like(t.clone(), !liked));
    }
    if menu_action(ui, Some(icon::THUMBS_DOWN), "Dislike") {
        acts.push(Action::Dislike(t.clone()));
    }
    ui.separator();
    if menu_action(ui, Some(icon::LIST_PLUS), "Add to playlist") {
        acts.push(Action::AddToPlaylist(vec![t.clone()]));
    }
    if let Some(i) = remove
        && menu_action(ui, Some(icon::LIST_MINUS), "Remove from playlist")
    {
        acts.push(Action::RemoveFromPlaylist(vec![i]));
    }
    match crate::downloads::path(&t.id) {
        Some(path) => {
            if menu_action(ui, Some(icon::FOLDER_OPEN), "Show in folder") {
                crate::downloads::show_file(&path);
            }
        }
        None => {
            if menu_action(ui, Some(icon::DOWNLOAD), "Download") {
                acts.push(Action::Download(t.clone()));
            }
        }
    }
}
