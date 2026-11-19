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
    Listening,
    Done,
}

impl Step {
    fn next(self) -> Self {
        match self {
            Self::Welcome => Self::SignIn,
            Self::SignIn => Self::Look,
            Self::Look => Self::Listening,
            Self::Listening | Self::Done => Self::Done,
        }
    }

    fn back(self) -> Self {
        match self {
            Self::Welcome | Self::SignIn => Self::Welcome,
            Self::Look => Self::SignIn,
            Self::Listening => Self::Look,
            Self::Done => Self::Listening,
        }
    }
}

pub(super) fn intro(app: &mut App, ctx: &egui::Context) {
    let Some(step) = app.setup else { return };
    let screen = ctx.content_rect();
    let t = theme::anim_bool(ctx, "setup-in", true, 0.3);
    let mut go = None;
    let mut finish = false;
    // Dark controls on the dark TV background, whatever the pages' mode.
    let pages = theme::set_mode(ctx, false);
    egui::Area::new(egui::Id::unique("setup"))
        .order(egui::Order::Foreground)
        .fixed_pos(screen.min)
        .show(ctx, |ui| {
            ui.set_min_size(screen.size());
            // Covers the app and takes the clicks meant for it.
            ui.interact(screen, egui::Id::unique("setup-scrim"), egui::Sense::click());
            background(ui.painter(), screen, app.accent_color(), t);
            ui.multiply_opacity(t);
            let w = 460.0_f32.min(screen.width() - 64.0);
            let body = egui::Rect::from_center_size(screen.center(), vec2(w, screen.height() - 120.0));
            let mut ui = ui.new_child(egui::UiBuilder::new().max_rect(body).layout(egui::Layout::top_down(egui::Align::Center)));
            let ui = &mut ui;
            let (title, message) = text(app, step);
            ui.add_space((body.height() * 0.22).max(24.0));
            hero(app, ui, step);
            ui.add_space(26.0);
            ui.label(RichText::new(title).font(theme::semibold(30.0)).color(theme::text()));
            ui.add_space(8.0);
            ui.label(RichText::new(message).font(theme::regular(15.0)).color(theme::text_dim()));
            ui.add_space(28.0);
            match step {
                Step::Look => look(app, ui),
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
    for i in 0..24 {
        let f = 1.0 - i as f32 / 24.0;
        p.circle_filled(center, radius * f, accent.gamma_multiply(0.55 / 24.0 * t));
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
        Step::Look => ("Choose a look", "You can change this later in Settings › Appearance.".into()),
        Step::Listening => ("Listening", "Turn these on or off. All of them are in Settings too.".into()),
        Step::Done => ("You're all set", "Find music in Home and Explore. Everything you save is in Library.".into()),
    }
}

fn hero(app: &App, ui: &mut Ui, step: Step) {
    let (rect, _) = ui.allocate_exact_size(vec2(96.0, 96.0), egui::Sense::hover());
    let p = ui.painter();
    p.circle_filled(rect.center(), 48.0, theme::glass(0.08));
    let mark = if step == Step::Done { "✓" } else { "n" };
    p.text(rect.center(), Align2::CENTER_CENTER, mark, theme::semibold(46.0), app.accent_color());
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
    setting_row(ui, "Animated background", Some("The album art slowly drifts behind everything."), |ui| {
        toggle(ui, &mut app.anim_bg, "Animated background");
    });
}

#[cfg(test)]
mod tests {
    use super::Step;

    #[test]
    fn steps_go_forward_and_back_in_order() {
        let mut s = Step::Welcome;
        let mut seen = vec![s];
        while s != Step::Done {
            s = s.next();
            seen.push(s);
        }
        assert_eq!(seen, [Step::Welcome, Step::SignIn, Step::Look, Step::Listening, Step::Done]);
        assert_eq!(Step::Done.next(), Step::Done);
        assert_eq!(Step::Look.back(), Step::SignIn);
        assert_eq!(Step::Welcome.back(), Step::Welcome);
    }
}
