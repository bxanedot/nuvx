//! Release notes: a renderer for GitHub release bodies, Markdown or plain text (headings,
//! paragraphs, lists, quotes, rules, pictures, tables, **bold**, `code`, [links](…)), the dialog shown when an update is ready
//! (or to read a version's notes), and the card shown after updating.

use egui::text::{LayoutJob, TextFormat};
use egui::{Color32, Margin, Stroke, Ui, vec2};

use super::{App, widgets};
use crate::theme::{self, icon};

pub enum Block {
    Heading(String),
    /// Its lines, joined by line breaks, as GitHub shows a release body.
    Para(String),
    Bullet(String),
    /// A numbered item, with its number.
    Numbered(String, String),
    Quote(String),
    Rule,
    /// Pictures side by side: (url, description).
    Images(Vec<(String, String)>),
    /// A table's rows, the header first.
    Table(Vec<Vec<String>>),
}

/// The bits of HTML release notes use, as Markdown: `<img>` as a picture (pictures in
/// one `<p>` stay side by side), `<br>` as a line break, and the wrappers dropped.
fn normalize(md: &str) -> String {
    let mut out = String::with_capacity(md.len());
    let mut rest = md;
    while let Some(i) = rest.find('<') {
        out.push_str(&rest[..i]);
        let tag_end = rest[i..].find('>').map(|e| i + e + 1);
        let Some(end) = tag_end else {
            out.push_str(&rest[i..]);
            rest = "";
            break;
        };
        let tag = &rest[i..end];
        let name: String = tag[1..].trim_start_matches('/').chars().take_while(|c| c.is_ascii_alphanumeric()).collect::<String>().to_ascii_lowercase();
        match name.as_str() {
            "img" => {
                let src = attr(tag, "src").unwrap_or_default();
                let alt = attr(tag, "alt").unwrap_or_default();
                out.push_str(&format!(" ![{alt}]({src}) "));
            }
            "br" => out.push('\n'),
            "p" | "div" | "center" | "picture" | "span" | "sup" | "sub" | "details" | "summary" => {}
            // Anything else isn't HTML we know: kept as written.
            _ => out.push_str(tag),
        }
        rest = &rest[end..];
    }
    out.push_str(rest);
    out.replace("&amp;", "&").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
}

/// An HTML attribute's value, quoted either way.
fn attr(tag: &str, name: &str) -> Option<String> {
    let lower = tag.to_ascii_lowercase();
    let at = lower.find(&format!("{name}="))? + name.len() + 1;
    let q = tag[at..].chars().next()?;
    if q == '"' || q == '\'' {
        let v = &tag[at + 1..];
        Some(v[..v.find(q)?].to_owned())
    } else {
        Some(tag[at..].split(|c: char| c.is_whitespace() || c == '>').next()?.to_owned())
    }
}

/// A line that's only pictures (`![alt](url)`, one or more).
fn images(line: &str) -> Option<Vec<(String, String)>> {
    let mut out = Vec::new();
    let mut rest = line.trim();
    while !rest.is_empty() {
        let r = rest.strip_prefix("![")?;
        let close = r.find("](")?;
        let end = r[close + 2..].find(')')? + close + 2;
        // `![alt](url "title")`: the url alone.
        let url = r[close + 2..end].split_whitespace().next().unwrap_or("").to_owned();
        out.push((url, r[..close].to_owned()));
        rest = r[end + 1..].trim_start();
    }
    (!out.is_empty()).then_some(out)
}

fn table_row(line: &str) -> Vec<String> {
    line.trim().trim_matches('|').split('|').map(|c| c.trim().to_owned()).collect()
}

pub fn parse(md: &str) -> Vec<Block> {
    let md = normalize(md);
    let mut out = Vec::new();
    let mut para = String::new();
    let flush = |para: &mut String, out: &mut Vec<Block>| {
        if !para.trim().is_empty() {
            out.push(Block::Para(para.trim().to_owned()));
        }
        para.clear();
    };
    // Pictures stay side by side until a blank line, as a `<p>` of them shows.
    let mut pictures_open = false;
    for line in md.lines() {
        let t = line.trim();
        if t.is_empty() {
            flush(&mut para, &mut out);
            pictures_open = false;
            continue;
        }
        if let Some(found) = images(t) {
            flush(&mut para, &mut out);
            match out.last_mut() {
                Some(Block::Images(row)) if pictures_open => row.extend(found),
                _ => out.push(Block::Images(found)),
            }
            pictures_open = true;
            continue;
        }
        pictures_open = false;
        if t.starts_with('|') {
            flush(&mut para, &mut out);
            let row = table_row(t);
            // The |---|:---:| line under the header.
            if row.iter().all(|c| !c.is_empty() && c.chars().all(|ch| matches!(ch, '-' | ':'))) {
                continue;
            }
            match out.last_mut() {
                Some(Block::Table(rows)) => rows.push(row),
                _ => out.push(Block::Table(vec![row])),
            }
        } else if t.len() >= 3 && (t.chars().all(|c| c == '-') || t.chars().all(|c| c == '*') || t.chars().all(|c| c == '_')) {
            flush(&mut para, &mut out);
            out.push(Block::Rule);
        } else if t.starts_with('#') {
            flush(&mut para, &mut out);
            out.push(Block::Heading(t.trim_start_matches('#').trim().to_owned()));
        } else if let Some(q) = t.strip_prefix('>') {
            flush(&mut para, &mut out);
            match out.last_mut() {
                Some(Block::Quote(prev)) => {
                    prev.push('\n');
                    prev.push_str(q.trim());
                }
                _ => out.push(Block::Quote(q.trim().to_owned())),
            }
        } else if let Some(b) = t.strip_prefix("- ").or_else(|| t.strip_prefix("* ")).or_else(|| t.strip_prefix("+ ")) {
            flush(&mut para, &mut out);
            out.push(Block::Bullet(b.trim().to_owned()));
        } else if let Some((n, item)) = t.split_once(". ").filter(|(n, _)| !n.is_empty() && n.len() <= 3 && n.chars().all(|c| c.is_ascii_digit())) {
            flush(&mut para, &mut out);
            out.push(Block::Numbered(n.to_owned(), item.trim().to_owned()));
        } else if let (true, Some(Block::Bullet(b) | Block::Numbered(_, b))) = (para.is_empty() && line.starts_with([' ', '\t']), out.last_mut()) {
            // An indented line goes on with the item above.
            b.push(' ');
            b.push_str(t);
        } else {
            if !para.is_empty() {
                para.push('\n');
            }
            para.push_str(t);
        }
    }
    flush(&mut para, &mut out);
    out
}

/// Inline spans: (text, bold). Links keep their text; code loses its backticks; an
/// inline picture shows its description.
fn spans(s: &str) -> Vec<(String, bool)> {
    let mut out: Vec<(String, bool)> = Vec::new();
    let mut push = |t: &str, bold: bool| match out.last_mut() {
        Some((prev, b)) if *b == bold => prev.push_str(t),
        _ => out.push((t.to_owned(), bold)),
    };
    let mut rest = s;
    let mut bold = false;
    while !rest.is_empty() {
        if let Some(r) = rest.strip_prefix("**").or_else(|| rest.strip_prefix("__")) {
            bold = !bold;
            rest = r;
        } else if let Some(r) = rest.strip_prefix('`') {
            let end = r.find('`').unwrap_or(r.len());
            push(&r[..end], bold);
            rest = r.get(end + 1..).unwrap_or("");
        } else if let Some(r) = rest.strip_prefix("![").or_else(|| rest.strip_prefix('['))
            && let Some(close) = r.find("](")
            && let Some(end) = r[close..].find(')')
        {
            push(&r[..close], bold);
            rest = &r[close + end + 1..];
        } else {
            let next = rest.char_indices().skip(1).find(|(_, c)| matches!(c, '*' | '_' | '`' | '[' | '!')).map_or(rest.len(), |(i, _)| i);
            push(&rest[..next], bold);
            rest = &rest[next..];
        }
    }
    out
}

/// A cell that's a single `[text](url)`: its text and url, to show as a link.
fn sole_link(s: &str) -> Option<(&str, &str)> {
    let r = s.trim().strip_prefix('[')?.strip_suffix(')')?;
    let (text, url) = r.split_once("](")?;
    (!text.contains(']')).then_some((text, url))
}

fn plain(s: &str) -> String {
    spans(s).into_iter().map(|(t, _)| t).collect()
}

fn job(text: &str, size: f32, color: Color32, width: f32) -> LayoutJob {
    let mut job = LayoutJob::default();
    job.wrap.max_width = width;
    for (t, bold) in spans(text) {
        let font = if bold { theme::semibold(size) } else { theme::regular(size) };
        let color = if bold { theme::text() } else { color };
        job.append(&t, 0.0, TextFormat { font_id: font, color, line_height: Some(size * 1.5), ..Default::default() });
    }
    job
}

fn link_color() -> Color32 {
    if theme::light() { Color32::from_rgb(0, 102, 204) } else { Color32::from_rgb(62, 166, 255) }
}

/// The notes laid out as the release page shows them: headings, paragraphs with their
/// line breaks, lists, quotes, rules, pictures and tables.
pub fn show(ui: &mut Ui, blocks: &[Block], size: f32) {
    let width = ui.available_width();
    let body = body_color();
    for (i, b) in blocks.iter().enumerate() {
        match b {
            Block::Heading(t) => {
                if i > 0 {
                    ui.add_space(12.0);
                }
                let g = widgets::galley(ui, &plain(t), theme::semibold(size + 1.0), theme::text(), width);
                ui.label(g);
                ui.add_space(4.0);
            }
            Block::Para(t) => {
                let g = ui.fonts_mut(|f| f.layout_job(job(t, size, body, width)));
                ui.label(g);
                ui.add_space(6.0);
            }
            Block::Bullet(t) | Block::Numbered(_, t) => {
                let g = ui.fonts_mut(|f| f.layout_job(job(t, size, body, width - 18.0)));
                let (rect, _) = ui.allocate_exact_size(vec2(width, g.size().y), egui::Sense::hover());
                if let Block::Numbered(n, _) = b {
                    ui.painter().text(rect.left_top(), egui::Align2::LEFT_TOP, format!("{n}."), theme::regular(size), body);
                } else {
                    ui.painter().circle_filled(egui::pos2(rect.left() + 6.0, rect.top() + size * 0.75), 2.2, body);
                }
                ui.painter().galley(rect.min + vec2(18.0, 0.0), g, body);
                ui.add_space(4.0);
            }
            Block::Quote(t) => {
                let g = ui.fonts_mut(|f| f.layout_job(job(t, size, theme::text_dim(), width - 14.0)));
                let (rect, _) = ui.allocate_exact_size(vec2(width, g.size().y), egui::Sense::hover());
                ui.painter().rect_filled(egui::Rect::from_min_size(rect.min, vec2(3.0, rect.height())), 1.5, rule_color());
                ui.painter().galley(rect.min + vec2(14.0, 0.0), g, theme::text_dim());
                ui.add_space(6.0);
            }
            Block::Rule => {
                ui.add_space(6.0);
                hline(ui);
                ui.add_space(8.0);
            }
            Block::Images(pics) => {
                let gap = 8.0;
                let n = pics.len() as f32;
                let w = ((width - gap * (n - 1.0)) / n).max(20.0);
                // Each picture's own shape once it has loaded; until then a banner's.
                let ctx = ui.ctx().clone();
                let aspect = |url: &str| match egui::Image::new(url).load_for_size(&ctx, vec2(w, w)) {
                    Ok(egui::load::TexturePoll::Ready { texture }) if texture.size.x > 0.0 => texture.size.y / texture.size.x,
                    _ => 0.5,
                };
                let h = pics.iter().map(|(u, _)| w * aspect(u)).fold(0.0, f32::max);
                let (row, _) = ui.allocate_exact_size(vec2(width, h), egui::Sense::hover());
                for (k, (url, alt)) in pics.iter().enumerate() {
                    let ph = w * aspect(url);
                    let r = egui::Rect::from_min_size(row.min + vec2(k as f32 * (w + gap), 0.0), vec2(w, ph));
                    let resp = ui.interact(r, ui.scope_id().with(("note-pic", i, k)), egui::Sense::hover());
                    widgets::named(resp, alt);
                    widgets::paint_remote(ui, url, r, 8.0, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
                }
                ui.add_space(8.0);
            }
            Block::Table(rows) => {
                let cols = rows.iter().map(Vec::len).max().unwrap_or(1).max(1);
                let cw = width / cols as f32;
                for (r, row) in rows.iter().enumerate() {
                    if r > 0 {
                        hline(ui);
                    }
                    ui.add_space(5.0);
                    ui.horizontal_top(|ui| {
                        ui.spacing_mut().item_spacing.x = 0.0;
                        for cell in row {
                            ui.scope(|ui| {
                                ui.set_width(cw);
                                ui.set_max_width(cw);
                                match sole_link(cell) {
                                    Some((text, url)) => {
                                        ui.hyperlink_to(egui::RichText::new(text).font(theme::regular(size)).color(link_color()), url);
                                    }
                                    None => {
                                        let font = if r == 0 { theme::semibold(size) } else { theme::regular(size) };
                                        let g = widgets::galley(ui, &plain(cell), font, if r == 0 { theme::text() } else { body }, cw - 8.0);
                                        ui.label(g);
                                    }
                                }
                            });
                        }
                    });
                    ui.add_space(5.0);
                }
                ui.add_space(6.0);
            }
        }
    }
}

/// The card's short list: feature headings and bullets (first sentence), up to
/// three, plus how many more there are. A "Fixes" heading isn't an item itself.
pub fn summary(blocks: &[Block]) -> (Vec<String>, usize) {
    let mut items = Vec::new();
    for b in blocks {
        match b {
            Block::Heading(t) if !t.to_lowercase().starts_with("fix") => items.push(plain(t)),
            Block::Bullet(t) | Block::Numbered(_, t) => {
                let t = plain(t);
                let first = t.find(". ").map_or(t.as_str(), |i| &t[..i]);
                items.push(first.trim_end_matches('.').to_owned());
            }
            _ => {}
        }
    }
    let more = items.len().saturating_sub(3);
    items.truncate(3);
    (items, more)
}

/// The notes' paragraphs and bullets.
fn body_color() -> Color32 {
    if theme::light() { theme::text() } else { Color32::from_gray(225) }
}

/// Lines between parts of the card.
fn rule_color() -> Color32 {
    if theme::light() { Color32::from_gray(222) } else { Color32::from_gray(44) }
}

pub(super) fn surface() -> egui::Frame {
    egui::Frame::new()
        .fill(if theme::light() { theme::surface_fill() } else { Color32::from_gray(28) })
        .stroke(Stroke::new(1.0, rule_color()))
        .corner_radius(16)
        .shadow(egui::epaint::Shadow { offset: [0, 24], blur: 70, spread: 0, color: theme::shade(0.55) })
}

fn hline(ui: &mut Ui) {
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 1.0), egui::Sense::hover());
    ui.painter().rect_filled(r, 0.0, rule_color());
}

pub fn github_link(ui: &mut Ui) {
    ui.hyperlink_to(egui::RichText::new("Full release notes on GitHub").font(theme::regular(13.0)).color(if theme::light() { Color32::from_rgb(0, 102, 204) } else { Color32::from_rgb(62, 166, 255) }), crate::update::RELEASES);
}

/// What the notes dialog shows: a downloaded update (Android's alert), or a version's notes.
#[derive(Clone)]
pub struct NotesDialog {
    pub version: String,
    pub ready: bool,
}

pub fn dialog(app: &mut App, ctx: &egui::Context) {
    let Some(d) = app.notes_dialog.clone() else { return };
    if d.ready {
        return update_alert(app, ctx, &d.version);
    }
    let body = app.updater.as_ref().and_then(|u| u.notes(&d.version));
    let mut close = false;
    let accent = app.accent_color();
    let modal = egui::Modal::new(egui::Id::unique("release-notes")).frame(surface()).backdrop_color(theme::shade(0.55)).show(ctx, |ui| {
        let w = 540.0_f32.min(ctx.content_rect().width() - 48.0);
        ui.set_width(w);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        egui::Frame::new().inner_margin(Margin { left: 28, right: 28, top: 24, bottom: 18 }).show(ui, |ui| {
            ui.horizontal(|ui| {
                widgets::icon_at(ui, ui.cursor().min + vec2(8.0, 8.0), icon::SPARKLES, 15.0, accent);
                ui.add_space(22.0);
                let mut j = LayoutJob::default();
                j.append("WHAT'S NEW", 0.0, TextFormat { font_id: theme::semibold(12.0), color: theme::text_dim(), extra_letter_spacing: 1.0, ..Default::default() });
                ui.label(j);
            });
            ui.add_space(8.0);
            ui.label(egui::RichText::new(format!("nuvx {}", d.version)).font(theme::bold(26.0)).color(theme::text()));
            ui.add_space(6.0);
            ui.label(egui::RichText::new("The version you're running.").font(theme::regular(13.5)).color(theme::text_dim()));
        });
        hline(ui);
        let max_h = (ctx.content_rect().height() - 330.0).clamp(160.0, 460.0);
        egui::ScrollArea::vertical().max_height(max_h).auto_shrink([false, true]).show(ui, |ui| {
            egui::Frame::new().inner_margin(Margin { left: 28, right: 28, top: 18, bottom: 18 }).show(ui, |ui| {
                match &body {
                    Some(md) => show(ui, &parse(md), 14.0),
                    None => {
                        ui.horizontal(|ui| {
                            ui.spinner();
                            ui.add_space(8.0);
                            ui.label(egui::RichText::new("Loading release notes…").color(theme::text_dim()));
                        });
                    }
                }
                ui.add_space(8.0);
                github_link(ui);
            });
        });
        hline(ui);
        egui::Frame::new().inner_margin(Margin { left: 28, right: 28, top: 16, bottom: 16 }).show(ui, |ui| {
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.spacing_mut().item_spacing.x = 10.0;
                close = widgets::pill(ui, "Close", None, true).clicked();
            });
        });
    });
    if close || modal.should_close() {
        app.notes_dialog = None;
    }
}

/// Android's alert: a narrow card with a title, a line of body, anything `content` adds,
/// and full-width stacked actions under hairlines. Which action was clicked, and whether
/// it was dismissed (Escape, or a click outside).
fn alert(ctx: &egui::Context, id: &str, title: &str, body: &str, accent: Color32, content: impl FnOnce(&mut Ui), actions: &[(&str, bool)]) -> (Option<usize>, bool) {
    let mut picked = None;
    let frame = surface().corner_radius(14).shadow(egui::epaint::Shadow { offset: [0, 12], blur: 40, spread: 0, color: theme::shade(0.4) });
    let modal = egui::Modal::new(egui::Id::unique(id)).frame(frame).backdrop_color(theme::shade(0.28)).show(ctx, |ui| {
        ui.set_width(300.0);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        egui::Frame::new().inner_margin(Margin { left: 16, right: 16, top: 20, bottom: 16 }).show(ui, |ui| {
            ui.vertical_centered(|ui| {
                ui.label(egui::RichText::new(title).font(theme::semibold(17.0)).color(theme::text()));
                ui.add_space(4.0);
                ui.label(egui::RichText::new(body).font(theme::regular(13.0)).color(theme::text_dim()));
            });
            content(ui);
        });
        for (i, (label, emphasised)) in actions.iter().enumerate() {
            if alert_action(ui, label, *emphasised, accent).clicked() {
                picked = Some(i);
            }
        }
    });
    (picked, modal.should_close())
}

/// A downloaded update, as Android's Software Update alert, with What's New.
fn update_alert(app: &mut App, ctx: &egui::Context, version: &str) {
    let body = app.updater.as_ref().and_then(|u| u.notes(version));
    let line = format!("nuvx {version} is ready to install. You're on {}.", crate::update::current_version());
    let content = |ui: &mut Ui| {
        if let Some(md) = &body {
            ui.add_space(14.0);
            ui.label(egui::RichText::new("What's New").font(theme::semibold(13.0)).color(theme::text()));
            ui.add_space(6.0);
            egui::ScrollArea::vertical().max_height(220.0).show(ui, |ui| show(ui, &parse(md), 13.0));
        }
    };
    let (picked, dismissed) = alert(ctx, "update-alert", "Software Update", &line, app.accent_color(), content, &[("Later", false), ("Install Now", true)]);
    if picked.is_some() || dismissed {
        app.notes_dialog = None;
    }
    if picked == Some(1) {
        app.restart_to_update(ctx);
    }
}

/// Radio's or Podcasts' first visit, as Android's Radio: keep the tab, or hide it.
pub fn tab_intro(app: &mut App, ctx: &egui::Context) {
    let podcasts = matches!(&app.page, super::Page::Mood { browse_id, .. } if browse_id == crate::ytm::PODCASTS);
    let i = match (&app.page, podcasts) {
        (super::Page::Radio, _) => 0,
        (_, true) => 1,
        _ => return,
    };
    if app.tab_intros_shown[i] {
        return;
    }
    let (title, body, actions) = if i == 0 {
        ("Live Radio", "Live stations from around the world. Not your thing? You can hide this tab, and bring it back anytime in Settings › Appearance.", [("Keep Radio", true), ("Hide Radio tab", false)])
    } else {
        ("Podcasts", "Shows and episodes from YouTube Music. Not your thing? You can hide this tab, and bring it back anytime in Settings › Appearance.", [("Keep Podcasts", true), ("Hide Podcasts tab", false)])
    };
    let (picked, dismissed) = alert(ctx, "tab-intro", title, body, app.accent_color(), |_| {}, &actions);
    if picked.is_some() || dismissed {
        app.tab_intros_shown[i] = true;
    }
    if picked == Some(1) {
        if i == 0 {
            app.hide_radio = true;
        } else {
            app.hide_podcasts = true;
        }
    }
}

/// One of the alert's stacked 44pt buttons, under a hairline.
fn alert_action(ui: &mut Ui, label: &str, emphasised: bool, accent: Color32) -> egui::Response {
    hline(ui);
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 44.0), egui::Sense::click());
    if resp.hovered() {
        ui.painter().rect_filled(rect, 0.0, theme::glass(0.05));
    }
    let font = if emphasised { theme::semibold(16.0) } else { theme::regular(16.0) };
    ui.painter().text(rect.center(), egui::Align2::CENTER_CENTER, label, font, accent);
    widgets::named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand)
}

/// "Updated to X" in the corner on the first launch of a new version.
pub fn card(app: &mut App, ctx: &egui::Context) {
    let Some(version) = app.whats_new.clone() else { return };
    let Some(md) = app.updater.as_ref().and_then(|u| u.notes(&version)) else { return };
    let (items, more) = summary(&parse(&md));
    if items.is_empty() {
        app.whats_new = None;
        return;
    }
    let screen = ctx.content_rect();
    let at = egui::pos2(screen.right() - 24.0, screen.bottom() - 20.0);
    let t = theme::anim_bool(ctx, "whats-new-in", true, 0.35);
    let (mut dismiss, mut all) = (false, false);
    egui::Area::new(egui::Id::unique("whats-new"))
        .order(egui::Order::Foreground)
        .pivot(egui::Align2::RIGHT_BOTTOM)
        .fixed_pos(at + vec2(0.0, 16.0 * (1.0 - t)))
        .show(ctx, |ui| {
            ui.multiply_opacity(t);
            surface().corner_radius(14).inner_margin(Margin { left: 18, right: 12, top: 14, bottom: 16 }).show(ui, |ui| {
                ui.set_width(330.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                ui.horizontal_top(|ui| {
                    ui.vertical(|ui| {
                        ui.add_space(4.0);
                        ui.label(egui::RichText::new(format!("Updated to {version}")).font(theme::semibold(15.0)).color(theme::text()));
                        ui.add_space(3.0);
                        ui.label(egui::RichText::new("Here's what changed.").font(theme::regular(13.0)).color(theme::text_dim()));
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Min), |ui| {
                        dismiss = widgets::icon_button(ui, icon::X, 16.0, "Dismiss", false).clicked();
                    });
                });
                ui.add_space(10.0);
                let mut lines: Vec<String> = items.clone();
                if more > 0 {
                    lines.push(if more == 1 { "+ 1 more change".to_owned() } else { format!("+ {more} more changes") });
                }
                for (i, l) in lines.iter().enumerate() {
                    let faint = more > 0 && i == lines.len() - 1;
                    let color = if faint { theme::text_dim() } else { body_color() };
                    let mut j = LayoutJob::single_section(l.clone(), TextFormat { font_id: theme::regular(13.5), color, ..Default::default() });
                    j.wrap.max_width = 300.0;
                    j.wrap.max_rows = 1;
                    let g = ui.fonts_mut(|f| f.layout_job(j));
                    let (rect, _) = ui.allocate_exact_size(vec2(318.0, 22.0), egui::Sense::hover());
                    if !faint {
                        ui.painter().circle_filled(egui::pos2(rect.left() + 5.0, rect.center().y), 2.2, color);
                    }
                    ui.painter().galley(egui::pos2(rect.left() + 16.0, rect.center().y - g.size().y / 2.0), g, color);
                }
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    ui.spacing_mut().item_spacing.x = 8.0;
                    all = widgets::pill(ui, "See all notes", None, true).clicked();
                    dismiss |= widgets::pill(ui, "Got it", None, false).clicked();
                });
            });
        });
    if all {
        app.notes_dialog = Some(NotesDialog { version: version.clone(), ready: false });
    }
    if all || dismiss {
        app.whats_new = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MD: &str = "## Uploads\n\nPick **files** or drop them.\n\n## Fixes\n\n- Mixes play again. They broke in 0.2.9.\n- Icons are `centred`\n  in their buttons.\n- See [the notes](https://example.com).\n";

    #[test]
    fn parses_release_markdown() {
        let b = parse(MD);
        assert!(matches!(&b[0], Block::Heading(t) if t == "Uploads"));
        assert!(matches!(&b[1], Block::Para(t) if t == "Pick **files** or drop them."));
        assert!(matches!(&b[4], Block::Bullet(t) if t == "Icons are `centred` in their buttons."));
        assert_eq!(spans("Pick **files** now"), vec![("Pick ".into(), false), ("files".into(), true), (" now".into(), false)]);
        assert_eq!(plain("See [the notes](https://example.com)."), "See the notes.");
    }

    #[test]
    fn summary_takes_features_then_first_sentences() {
        let (items, more) = summary(&parse(MD));
        assert_eq!(items, vec!["Uploads", "Mixes play again", "Icons are centred in their buttons"]);
        assert_eq!(more, 1);
    }

    #[test]
    fn parses_pictures_tables_and_html() {
        let md = "Line one
line two

<p><img src=\"https://a/1.png\" alt=\"One\"> <img src='https://a/2.png'></p>

| Platform | File |
|---|:-:|
| Windows | [setup.exe](https://x/s.exe) |

1. First
2. Second<br>still

> Quoted

---
";
        let b = parse(md);
        assert!(matches!(&b[0], Block::Para(t) if t == "Line one
line two"));
        assert!(matches!(&b[1], Block::Images(p) if p.len() == 2 && p[0] == ("https://a/1.png".into(), "One".into())));
        assert!(matches!(&b[2], Block::Table(r) if r.len() == 2 && r[1][1] == "[setup.exe](https://x/s.exe)"));
        assert_eq!(sole_link("[setup.exe](https://x/s.exe)"), Some(("setup.exe", "https://x/s.exe")));
        assert!(matches!(&b[3], Block::Numbered(n, t) if n == "1" && t == "First"));
        assert!(matches!(&b[4], Block::Numbered(_, t) if t == "Second"));
        assert!(matches!(&b[5], Block::Para(t) if t == "still"));
        assert!(matches!(&b[6], Block::Quote(t) if t == "Quoted"));
        assert!(matches!(&b[7], Block::Rule));
    }
}
