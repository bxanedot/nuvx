//! First-run intro, the same steps as the Android TV app: welcome, sign in, a look, a few listening options, done.
//! Shown once on a fresh install; "Set up later" or finishing hides it for good.

use egui::{Align2, RichText, Ui, vec2};

use super::pages::{setting_row, toggle};
use super::widgets::pill;
use super::App;
use crate::theme;

#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub(crate) enum Step {
    #[default]
    Welcome,
    SignIn,
    Look,
    Tabs,
    Listening,
    Done,
}

impl Step {
    /// `NUVX_SETUP=look` (or `listening`, `done`) opens the intro at that step, for screenshots.
    pub(super) fn from_dev(v: Option<&str>) -> Self {
        match v {
            Some("signin") => Self::SignIn,
            Some("look") => Self::Look,
            Some("tabs") => Self::Tabs,
            Some("listening") => Self::Listening,
            Some("done") => Self::Done,
            _ => Self::Welcome,
        }
    }

    fn next(self) -> Self {
        match self {
            Self::Welcome => Self::SignIn,
            Self::SignIn => Self::Look,
            Self::Look => Self::Tabs,
            Self::Tabs => Self::Listening,
            Self::Listening | Self::Done => Self::Done,
        }
    }

    fn back(self) -> Self {
        match self {
            Self::Welcome | Self::SignIn => Self::Welcome,
            Self::Look => Self::SignIn,
            Self::Tabs => Self::Look,
            Self::Listening => Self::Tabs,
            Self::Done => Self::Listening,
        }
    }
}

/// The intro is laid out on a fixed canvas and scaled to the window, so it always fits, small or large.
const CANVAS: egui::Vec2 = vec2(560.0, 780.0);

/// The whole window while the intro runs: nothing of the app is drawn behind it, so nothing there takes input.
/// Whether the intro opens at start. On a fresh install, always. On a nightly build, once for every new
/// nightly (`nightly.<run>.<attempt>`), because testers get a new install each time; all saved data is kept.
/// Stable builds show it once, on a fresh install only. `forced` is the NUVX_SETUP test switch.
pub(super) fn show_at_start(forced: bool, done_before: bool, has_saved_data: bool, seen_build: Option<&str>, build: &str) -> bool {
    forced || (!done_before && !has_saved_data) || (build.starts_with("nightly.") && seen_build != Some(build))
}

pub(super) fn page(app: &mut App, ui: &mut Ui, ctx: &egui::Context) {
    let Some(step) = app.setup else { return };
    let screen = ctx.content_rect();
    let t = theme::anim_bool(ctx, "setup-in", true, 0.3);
    let mut go = None;
    let mut finish = false;
    // Dark controls on the dark TV background, whatever the pages' mode.
    let pages = theme::set_mode(ctx, false);
    background(ui.painter(), screen, app.accent_color(), 1.0);
    let top = super::titlebar::HEIGHT;
    let avail = vec2(screen.width() - 48.0, screen.height() - top - 32.0);
    let scale = (avail.x / CANVAS.x).min(avail.y / CANVAS.y).clamp(0.4, 2.5);
    let center = egui::pos2(screen.center().x, screen.top() + top + (screen.height() - top) / 2.0);
    let id = egui::Id::unique("setup");
    ctx.set_transform_layer(
        egui::LayerId::new(egui::Order::Middle, id),
        egui::emath::TSTransform::new(center.to_vec2() - CANVAS * (scale / 2.0), scale),
    );
    egui::Area::new(id)
        .order(egui::Order::Middle)
        .constrain(false)
        .fixed_pos(egui::Pos2::ZERO)
        .show(ctx, |ui| {
            ui.set_min_size(CANVAS);
            ui.set_max_width(CANVAS.x);
            ui.multiply_opacity(t);
            let mut ui = ui.new_child(egui::UiBuilder::new().max_rect(egui::Rect::from_min_size(egui::Pos2::ZERO, CANVAS)).layout(egui::Layout::top_down(egui::Align::Center)));
            let ui = &mut ui;
            let (title, message) = text(app, step);
            ui.add_space(24.0);
            hero(app, ui, step, scale);
            ui.add_space(26.0);
            ui.label(RichText::new(title).font(theme::semibold(30.0)).color(theme::text()));
            ui.add_space(8.0);
            ui.label(RichText::new(message).font(theme::regular(15.0)).color(theme::text_dim()));
            ui.add_space(28.0);
            match step {
                Step::Look => look(app, ui),
                Step::Tabs => tabs(app, ui),
                Step::Listening => listening(app, ui),
                _ => {}
            }
            ui.add_space(22.0);
            match step {
                Step::Welcome => {
                    if pill(ui, "Continue", None, true).clicked() {
                        go = Some(step.next());
                    }
                    ui.add_space(8.0);
                    if pill(ui, "Set up later", None, false).clicked() {
                        finish = true;
                    }
                }
                Step::SignIn if !app.logged_in => {
                    if pill(ui, if app.signing_in.load(std::sync::atomic::Ordering::Relaxed) { "Signing in…" } else { "Sign in" }, None, true).clicked() {
                        app.start_sign_in(ui.ctx());
                    }
                    ui.add_space(8.0);
                    if pill(ui, "Not now", None, false).clicked() {
                        go = Some(step.next());
                    }
                }
                Step::Done => {
                    if pill(ui, "Start listening", None, true).clicked() {
                        finish = true;
                    }
                }
                _ => {
                    if pill(ui, "Continue", None, true).clicked() {
                        go = Some(step.next());
                    }
                }
            }
        });
    theme::set_mode(ctx, pages);
    if ctx.input(|i| i.key_pressed(egui::Key::Escape)) && step != Step::Welcome {
        go = Some(step.back());
    }
    if finish {
        app.setup = None;
    } else if let Some(s) = go {
        app.setup = Some(s);
    }
}

/// The Android TV app's canvas: navy from top to bottom, with a soft glow of the accent colour at the top.
fn background(p: &egui::Painter, r: egui::Rect, accent: egui::Color32, t: f32) {
    use egui::Color32;
    let a = |c: Color32| c.gamma_multiply(t);
    let (top, bottom) = (Color32::from_rgb(0x18, 0x21, 0x3A), Color32::from_rgb(0x07, 0x0A, 0x12));
    let mut mesh = egui::Mesh::default();
    mesh.colored_vertex(r.left_top(), a(top));
    mesh.colored_vertex(r.right_top(), a(top));
    mesh.colored_vertex(r.right_bottom(), a(bottom));
    mesh.colored_vertex(r.left_bottom(), a(bottom));
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(0, 2, 3);
    p.add(mesh);
    // shortcut: a radial gradient made of stacked circles; a shader would be smoother if banding shows.
    let center = egui::pos2(r.center().x, r.top() - r.height() * 0.15);
    let radius = r.width() * 0.75;
    const RINGS: usize = 96;
    for i in 0..RINGS {
        let f = 1.0 - i as f32 / RINGS as f32;
        p.circle_filled(center, radius * f, accent.gamma_multiply(0.45 / RINGS as f32 * t));
    }
}

fn text(app: &App, step: Step) -> (&'static str, String) {
    match step {
        Step::Welcome => ("Welcome to nuvx", "Your music, made for your computer.".into()),
        Step::SignIn if app.logged_in => (
            "Signed in",
            format!("nuvx will use {}'s library and recommendations.", app.account_name.as_deref().filter(|n| !n.is_empty()).unwrap_or("your account")),
        ),
        Step::SignIn => ("Sign in to YouTube Music", "Bring your library, playlists and recommendations. You can also do this later in Settings.".into()),
        Step::Look => ("Choose a look", "Light or dark, and the window's material. You can change these later in Settings.".into()),
        Step::Tabs => ("Your tabs", "Show or hide Radio and Podcasts in the sidebar. Settings › Appearance has them too.".into()),
        Step::Listening => ("Listening", "Turn these on or off. All of them are in Settings too.".into()),
        Step::Done => ("You're all set", "Find music in Home and Explore. Everything you save is in Library.".into()),
    }
}

fn hero(app: &App, ui: &mut Ui, step: Step, scale: f32) {
    let (rect, _) = ui.allocate_exact_size(vec2(96.0, 96.0), egui::Sense::hover());
    if step == Step::Done {
        let p = ui.painter();
        p.circle_filled(rect.center(), 48.0, theme::glass(0.08));
        p.text(rect.center(), Align2::CENTER_CENTER, "✓", theme::semibold(46.0), app.accent_color());
        return;
    }
    let px = (96.0 * scale * ui.ctx().pixels_per_point()).round().clamp(16.0, 1024.0) as usize;
    let tex = mark_texture(ui.ctx(), px);
    ui.painter().image(tex.id(), rect, egui::Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1.0, 1.0)), egui::Color32::WHITE);
}

/// The official nuvx mark (core/brand/mark/nuvx_mark_white.svg): one path of cubic curves in a 1024 box.
const MARK_SVG: &str = include_str!("../../../../core/brand/mark/nuvx_mark_white.svg");

/// The mark rendered from its vector path at exactly `px` pixels, so it is sharp at any size. Cached per size.
fn mark_texture(ctx: &egui::Context, px: usize) -> egui::TextureHandle {
    let key = egui::Id::unique(("nuvx-mark", px));
    if let Some(t) = ctx.data(|d| d.get_temp::<egui::TextureHandle>(key)) {
        return t;
    }
    let image = rasterize(&mark_polygons(), px);
    let t = ctx.load_texture(format!("nuvx-mark-{px}"), image, egui::TextureOptions::LINEAR);
    ctx.data_mut(|d| d.insert_temp(key, t.clone()));
    t
}

/// The path's outlines as polygons (each cubic flattened to short lines), in the SVG's 1024 units.
fn mark_polygons() -> Vec<Vec<[f32; 2]>> {
    let d = MARK_SVG.split(" d=\"").nth(1).and_then(|r| r.split('"').next()).unwrap_or("");
    let mut out: Vec<Vec<[f32; 2]>> = Vec::new();
    let mut nums = Vec::new();
    let mut cmd = ' ';
    let mut at = [0.0f32; 2];
    let flush = |cmd: char, nums: &mut Vec<f32>, out: &mut Vec<Vec<[f32; 2]>>, at: &mut [f32; 2]| {
        match cmd {
            'M' => {
                for p in nums.as_chunks::<2>().0 {
                    *at = [p[0], p[1]];
                    out.push(vec![*at]);
                }
            }
            'L' => {
                for p in nums.as_chunks::<2>().0 {
                    *at = [p[0], p[1]];
                    if let Some(poly) = out.last_mut() { poly.push(*at); }
                }
            }
            'C' => {
                for c in nums.as_chunks::<6>().0 {
                    let (p0, p1, p2, p3) = (*at, [c[0], c[1]], [c[2], c[3]], [c[4], c[5]]);
                    if let Some(poly) = out.last_mut() {
                        for i in 1..=12 {
                            let t = i as f32 / 12.0;
                            let u = 1.0 - t;
                            let b = |k: usize| u * u * u * p0[k] + 3.0 * u * u * t * p1[k] + 3.0 * u * t * t * p2[k] + t * t * t * p3[k];
                            poly.push([b(0), b(1)]);
                        }
                    }
                    *at = p3;
                }
            }
            _ => {}
        }
        nums.clear();
    };
    let mut token = String::new();
    for ch in d.chars().chain(std::iter::once(' ')) {
        if ch.is_ascii_digit() || ch == '.' || (ch == '-' && token.is_empty()) {
            token.push(ch);
            continue;
        }
        if let Ok(v) = token.parse::<f32>() { nums.push(v); }
        token.clear();
        if ch == '-' {
            token.push(ch);
        } else if ch.is_ascii_alphabetic() {
            flush(cmd, &mut nums, &mut out, &mut at);
            cmd = ch;
        }
    }
    flush(cmd, &mut nums, &mut out, &mut at);
    out
}

/// Even-odd fill of `polys` (1024 units) into a white `px`×`px` image; 4×4 samples per pixel for smooth edges.
fn rasterize(polys: &[Vec<[f32; 2]>], px: usize) -> egui::ColorImage {
    const SS: usize = 4;
    let k = px as f32 / 1024.0;
    let mut cover = vec![0u16; px * px];
    let mut xs = Vec::new();
    for row in 0..px * SS {
        let y = (row as f32 + 0.5) / (SS as f32 * k);
        xs.clear();
        for poly in polys {
            for (i, a) in poly.iter().enumerate() {
                let b = poly[(i + 1) % poly.len()];
                if (a[1] <= y) != (b[1] <= y) {
                    xs.push(a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0]));
                }
            }
        }
        xs.sort_by(f32::total_cmp);
        for span in xs.as_chunks::<2>().0 {
            let from = ((span[0] * k * SS as f32).round().max(0.0) as usize).min(px * SS);
            let to = ((span[1] * k * SS as f32).round().max(0.0) as usize).min(px * SS);
            for sx in from..to {
                cover[(row / SS) * px + sx / SS] += 1;
            }
        }
    }
    let pixels = cover.iter().map(|&c| egui::Color32::from_white_alpha((c as usize * 255 / (SS * SS)).min(255) as u8)).collect();
    egui::ColorImage::new([px, px], pixels)
}

fn look(app: &mut App, ui: &mut Ui) {
    ui.horizontal(|ui| {
        let row_w = theme::Appearance::ALL.len() as f32 * 110.0;
        ui.add_space(((ui.available_width() - row_w) / 2.0).max(0.0));
        for a in theme::Appearance::ALL {
            if pill(ui, a.label(), None, a == app.appearance).clicked() {
                app.appearance = a;
            }
            ui.add_space(12.0);
        }
    });
    if crate::material::SUPPORTED {
        ui.add_space(18.0);
        setting_row(ui, "Window material", Some("Dynamic uses nuvx's own background; the others let your desktop tint or show through."), |ui| {
            let now = crate::material::active();
            for m in crate::material::Material::ALL.into_iter().rev() {
                if super::widgets::chip(ui, m.name(), now == m).clicked() && now != m {
                    crate::material::set(m);
                    if let Err(e) = crate::material::save(app.backend.data_dir(), m) {
                        log::warn!("material: couldn't save the setting: {e}");
                    }
                }
            }
        });
    }
    if super::titlebar::custom() {
        setting_row(ui, "Window buttons", Some("At the top right like Windows, or macOS's coloured buttons at the top left."), |ui| {
            let mac = super::titlebar::mac();
            if super::widgets::chip(ui, "macOS", mac).clicked() {
                super::titlebar::set_mac(true);
            }
            if super::widgets::chip(ui, "Windows", !mac).clicked() {
                super::titlebar::set_mac(false);
            }
        });
    }
}

fn tabs(app: &mut App, ui: &mut Ui) {
    let mut radio = !app.hide_radio;
    setting_row(ui, "Radio", Some("Live stations from around the world."), |ui| {
        toggle(ui, &mut radio, "Radio tab");
    });
    let mut podcasts = !app.hide_podcasts;
    setting_row(ui, "Podcasts", Some("Shows and episodes from YouTube Music."), |ui| {
        toggle(ui, &mut podcasts, "Podcasts tab");
    });
    app.hide_radio = !radio;
    app.hide_podcasts = !podcasts;
    // Chosen here, so the tabs do not ask again the first time they open.
    app.tab_intros_shown = [true, true];
}

fn listening(app: &mut App, ui: &mut Ui) {
    setting_row(ui, "Autoplay", Some("Keep playing similar songs when the queue ends."), |ui| {
        toggle(ui, &mut app.autoplay, "Autoplay");
    });
    setting_row(ui, "Spatial audio", Some("Wider sound on headphones."), |ui| {
        if toggle(ui, &mut app.spatial, "Spatial audio").changed() {
            app.audio.set_spatial(app.spatial);
        }
    });
    if crate::motion::SUPPORTED {
        setting_row(ui, "Animated artwork", Some("Covers move where a moving version exists."), |ui| {
            toggle(ui, &mut app.anim_canvas, "Animated artwork");
        });
    }
    setting_row(ui, "Nightly updates", Some("Get test builds as soon as they are published. Off: stable releases only."), |ui| {
        if toggle(ui, &mut app.nightly_updates, "Nightly updates").changed() {
            app.update_checked = None;
        }
    });
    setting_row(ui, "Animated background", Some("The album art slowly drifts behind everything."), |ui| {
        toggle(ui, &mut app.anim_bg, "Animated background");
    });
}

#[cfg(test)]
mod tests {
    use super::{Step, show_at_start};

    #[test]
    fn the_intro_shows_on_fresh_installs_and_once_per_new_nightly() {
        // Fresh install.
        assert!(show_at_start(false, false, false, None, "v1.0.3"));
        // Existing user on a stable build: not again, even if the build changes.
        assert!(!show_at_start(false, true, true, Some("v1.0.2"), "v1.0.3"));
        assert!(!show_at_start(false, false, true, None, "v1.0.3"), "saved data but no marker: an old install, not fresh");
        // A nightly: shown for a build not yet seen, never twice for the same one.
        assert!(show_at_start(false, true, true, None, "nightly.325.1"));
        assert!(show_at_start(false, true, true, Some("nightly.319.1"), "nightly.325.1"));
        assert!(!show_at_start(false, true, true, Some("nightly.325.1"), "nightly.325.1"));
        // The test switch always shows it.
        assert!(show_at_start(true, true, true, Some("v1.0.3"), "v1.0.3"));
    }

    #[test]
    fn steps_go_forward_and_back_in_order() {
        let mut s = Step::Welcome;
        let mut seen = vec![s];
        while s != Step::Done {
            s = s.next();
            seen.push(s);
        }
        assert_eq!(seen, [Step::Welcome, Step::SignIn, Step::Look, Step::Tabs, Step::Listening, Step::Done]);
        assert_eq!(Step::Done.next(), Step::Done);
        assert_eq!(Step::Look.back(), Step::SignIn);
        assert_eq!(Step::Welcome.back(), Step::Welcome);
    }
}

#[cfg(test)]
mod mark_tests {
    #[test]
    fn the_vector_mark_fills_some_pixels_and_not_the_corners() {
        let polys = super::mark_polygons();
        assert!(!polys.is_empty() && polys.iter().all(|p| p.len() > 2));
        let img = super::rasterize(&polys, 64);
        let filled = img.pixels.iter().filter(|c| c.a() > 128).count();
        assert!(filled > 200 && filled < 64 * 64 / 2, "{filled}");
        assert_eq!(img.pixels[0].a(), 0, "transparent corner");
    }
}
