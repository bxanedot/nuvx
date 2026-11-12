use std::time::Duration;

use egui::{Color32, Pos2, Rect, Vec2, ViewportCommand, WindowLevel, pos2, vec2};
use serde::{Deserialize, Serialize};

use crate::theme;

mod strip;
pub use strip::strip;

pub const SIZE: Vec2 = vec2(400.0, 112.0);
const MIN_W: f32 = 330.0;
const MAX_W: f32 = 720.0;
const PINNED_BY_DEFAULT: bool = true;
const CLOSE: CloseButton = CloseButton::LikeFullWindow;
const MARGIN: f32 = 16.0;

#[allow(dead_code)]
#[derive(Clone, Copy, PartialEq)]
enum CloseButton {
    LikeFullWindow,
    Expand,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Place {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    pub scale: f32,
    #[serde(default = "unzoomed")]
    pub zoom: f32,
}

fn unzoomed() -> f32 {
    1.0
}

impl Place {
    fn current(ctx: &egui::Context) -> Option<Place> {
        let ppp = ctx.pixels_per_point();
        let size = ctx.content_rect().size();
        let zoom = ctx.zoom_factor();
        ctx.input(|i| {
            let vp = i.viewport();
            let pos = vp.outer_rect.map_or(Pos2::ZERO, |r| r.min * ppp);
            Some(Place { x: pos.x, y: pos.y, w: size.x, h: size.y, scale: vp.native_pixels_per_point?, zoom })
        })
    }

    fn size(&self) -> Vec2 {
        vec2(self.w, self.h)
    }

    fn size_at(&self, zoom: f32) -> Vec2 {
        self.size() * (self.zoom / zoom)
    }

    fn rect_px(&self, frame: Vec2) -> Rect {
        Rect::from_min_size(pos2(self.x, self.y), (self.size() + frame) * self.scale * self.zoom)
    }

    fn eframe_window(&self, maximized: bool) -> String {
        format!(
            "(normal_geometry:(outer_position_pixels:Some((x:{:?},y:{:?})),scale_factor:{:?},inner_size_points:(x:{:?},y:{:?})),fullscreen:false,maximized:{maximized})",
            self.x, self.y, self.scale, self.w, self.h
        )
    }
}

fn corner_place(area: Rect, frame: Vec2, scale: f32, zoom: f32) -> Place {
    let outer = (SIZE + frame) * scale * zoom;
    let p = area.max - outer - Vec2::splat(MARGIN * scale);
    let p = p.max(area.min);
    Place { x: p.x.round(), y: p.y.round(), w: SIZE.x, h: SIZE.y, scale, zoom }
}

fn centered(area: Rect, size: Vec2, frame: Vec2, scale: f32, zoom: f32) -> Place {
    let outer = (size + frame) * scale * zoom;
    let p = (area.center() - outer / 2.0).max(area.min);
    Place { x: p.x.round(), y: p.y.round(), w: size.x, h: size.y, scale, zoom }
}

#[derive(Clone, Copy)]
struct Screens {
    on_a_monitor: fn(Rect) -> bool,
    work_area: fn() -> Option<Rect>,
}

const SYSTEM: Screens = Screens { on_a_monitor: crate::fullscreen::on_a_monitor, work_area: crate::fullscreen::work_area };

fn clamp_size(p: Place) -> Place {
    Place { w: p.w.clamp(MIN_W, MAX_W), h: SIZE.y, ..p }
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Step {
    Unmaximize,
    Resize,
    Rescale(Place),
    Settle(Option<Vec2>),
}

struct Switch {
    to_mini: bool,
    step: Step,
    since: f64,
    from: Vec2,
}

#[derive(Clone, Copy, PartialEq)]
pub enum Layout {
    Full,
    Mini,
    Blank,
}

pub struct Mini {
    on: bool,
    pub pinned: bool,
    place: Option<Place>,
    full: Option<(Place, bool)>,
    was_maximized: bool,
    switch: Option<Switch>,
    wanted: Option<bool>,
    shown_at: f64,
    limits_scale: Option<f32>,
    screens: Screens,
    restored_max: Option<Place>,
}

impl Mini {
    pub fn load(get: impl Fn(&str) -> Option<String>) -> Self {
        Mini {
            on: false,
            pinned: get("mini_pinned").map_or(PINNED_BY_DEFAULT, |v| v == "1"),
            place: get("mini_window").and_then(|v| serde_json::from_str(&v).ok()).map(clamp_size),
            full: None,
            was_maximized: false,
            switch: None,
            wanted: None,
            shown_at: f64::NEG_INFINITY,
            limits_scale: None,
            screens: SYSTEM,
            restored_max: None,
        }
    }

    pub fn save(&self, storage: &mut dyn eframe::Storage) {
        storage.set_string("mini_pinned", if self.pinned { "1" } else { "0" }.into());
        if let Some(p) = &self.place {
            storage.set_string("mini_window", serde_json::to_string(p).unwrap_or_default());
        }
    }

    pub fn request(&mut self, on: bool) {
        self.wanted = Some(on);
    }

    pub fn take_request(&mut self) -> Option<bool> {
        if self.switch.is_some() {
            return None;
        }
        self.wanted.take()
    }

    pub fn target(&self) -> bool {
        self.wanted.unwrap_or_else(|| self.active())
    }

    pub fn active(&self) -> bool {
        self.switch.as_ref().map_or(self.on, |s| s.to_mini)
    }

    pub fn busy(&self) -> bool {
        self.on || self.switch.is_some()
    }

    pub fn showing(&self) -> bool {
        self.on && self.switch.is_none()
    }

    pub fn layout(&self, ctx: &egui::Context) -> Layout {
        match &self.switch {
            Some(sw) if (ctx.content_rect().size() - sw.from).length() > 1.0 => Layout::Blank,
            _ if self.on => Layout::Mini,
            _ => Layout::Full,
        }
    }

    pub fn full_window(&self) -> Option<String> {
        if self.active() || self.switch.is_some() {
            return self.full.map(|(p, max)| p.eframe_window(max));
        }
        self.restored_max.map(|p| p.eframe_window(true))
    }

    pub fn fade_in(&self, ctx: &egui::Context, now: f64, base: Color32) {
        let t = ((now - self.shown_at) / 0.18).clamp(0.0, 1.0) as f32;
        if t >= 1.0 {
            return;
        }
        ctx.request_repaint();
        let layer = egui::LayerId::new(egui::Order::Tooltip, egui::Id::unique("mini-fade"));
        ctx.layer_painter(layer).rect_filled(ctx.content_rect(), 0.0, base.gamma_multiply(1.0 - theme::ease_out(t)));
    }

    pub fn toggle(&mut self, ctx: &egui::Context, now: f64) {
        if self.switch.is_some() {
            return;
        }
        let to_mini = !self.on;
        let mut step = Step::Resize;
        if to_mini {
            self.was_maximized = ctx.input(|i| i.viewport().maximized == Some(true));
            if self.was_maximized {
                ctx.send_viewport_cmd(ViewportCommand::Maximized(false));
                step = Step::Unmaximize;
            }
        }
        self.switch = Some(Switch { to_mini, step, since: now, from: ctx.content_rect().size() });
        ctx.request_repaint();
    }

    pub fn set_pinned(&mut self, ctx: &egui::Context, on: bool) {
        self.pinned = on;
        if self.active() {
            ctx.send_viewport_cmd(ViewportCommand::WindowLevel(if on { WindowLevel::AlwaysOnTop } else { WindowLevel::Normal }));
        }
    }

    pub fn tick(&mut self, ctx: &egui::Context, now: f64) {
        let scale = ctx.input(|i| i.viewport().native_pixels_per_point);
        if self.limits_scale.is_some_and(|s| s != ctx.pixels_per_point()) && self.switch.is_none() {
            self.limits(ctx, self.on);
        }
        if self.restored_max.is_some() && self.switch.is_none() && ctx.input(|i| i.viewport().maximized == Some(false)) {
            self.restored_max = None;
        }
        let (minimized, maximized) = ctx.input(|i| (i.viewport().minimized == Some(true), i.viewport().maximized == Some(true)));
        if self.showing() && maximized && !minimized {
            ctx.send_viewport_cmd(ViewportCommand::Maximized(false));
        }
        if self.showing()
            && !minimized
            && !maximized
            && let Some(p) = Place::current(ctx).filter(|p| p.w >= MIN_W - 1.0)
        {
            self.place = Some(clamp_size(p));
        }
        let Some(sw) = &mut self.switch else { return };
        ctx.request_repaint_after(Duration::from_millis(16));
        let waited = now - sw.since;
        let to_mini = sw.to_mini;
        let next = match sw.step {
            Step::Unmaximize => {
                let down = ctx.input(|i| i.viewport().maximized != Some(true));
                if down {
                    self.full = Place::current(ctx).map(|p| (p, true));
                }
                (down || waited > 0.5).then_some(Step::Resize)
            }
            Step::Resize => Some(self.resize(ctx, to_mini)),
            Step::Rescale(target) => (scale.is_some_and(|s| (s - target.scale).abs() < 0.01) || waited > 0.6).then(|| {
                self.limits(ctx, to_mini);
                let size = target.size_at(ctx.zoom_factor());
                ctx.send_viewport_cmd(ViewportCommand::InnerSize(size));
                Step::Settle(Some(size))
            }),
            Step::Settle(size) => {
                let done = match size {
                    Some(size) => (ctx.content_rect().size() - size).length() < 2.0,
                    None => ctx.input(|i| i.viewport().maximized == Some(true)),
                };
                if done || waited > 0.4 {
                    log::info!("{}: {:?}{}", if to_mini { "mini player" } else { "full window" }, Place::current(ctx), if done { "" } else { " (timed out)" });
                    self.on = to_mini;
                    if !to_mini {
                        self.restored_max = self.full.take().filter(|(_, max)| *max).map(|(p, _)| p);
                    }
                    self.switch = None;
                    self.shown_at = now;
                    return;
                }
                None
            }
        };
        if let (Some(step), Some(sw)) = (next, &mut self.switch) {
            sw.step = step;
            sw.since = now;
        }
    }

    fn visible(&self, p: Place, frame: Vec2) -> bool {
        let r = p.rect_px(frame);
        let grip = Rect::from_center_size(pos2(r.center().x, r.top() + 12.0 * p.scale), vec2(r.width().min(120.0 * p.scale), 16.0 * p.scale));
        std::env::var_os("NUVX_BACKGROUND").is_some() || (self.screens.on_a_monitor)(grip)
    }

    fn resize(&mut self, ctx: &egui::Context, to_mini: bool) -> Step {
        let now_place = Place::current(ctx);
        let frame = ctx.input(|i| {
            let vp = i.viewport();
            vp.outer_rect.zip(vp.inner_rect).map_or(Vec2::ZERO, |(o, i)| o.size() - i.size())
        });
        if to_mini {
            self.full = now_place.map(|p| (p, self.was_maximized));
            let target = match (self.place, now_place) {
                (_, Some(here)) if std::env::var_os("NUVX_BACKGROUND").is_some() => {
                    let w = std::env::var("NUVX_MINI_W").ok().and_then(|w| w.parse().ok()).unwrap_or(SIZE.x);
                    clamp_size(Place { w, ..here })
                }
                (Some(p), _) if self.visible(p, frame) => p,
                (_, here) => {
                    let scale = here.map_or(1.0, |h| h.scale);
                    let area = (self.screens.work_area)()
                        .or_else(|| here.map(|h| h.rect_px(frame)))
                        .unwrap_or(Rect::from_min_size(Pos2::ZERO, SIZE * scale));
                    corner_place(area, frame, scale, ctx.zoom_factor())
                }
            };
            ctx.send_viewport_cmd(ViewportCommand::WindowLevel(if self.pinned { WindowLevel::AlwaysOnTop } else { WindowLevel::Normal }));
            self.go(ctx, target, false, true)
        } else {
            if let Some(p) = now_place {
                self.place = Some(clamp_size(p));
            }
            ctx.send_viewport_cmd(ViewportCommand::WindowLevel(WindowLevel::Normal));
            match self.full {
                Some((p, max)) if self.visible(p, frame) => self.go(ctx, p, max, false),
                full => {
                    let zoom = ctx.zoom_factor();
                    let size = full.map_or(Vec2::from(crate::DEFAULT_SIZE), |(p, _)| p.size_at(zoom));
                    let max = full.is_some_and(|(_, max)| max);
                    match ((self.screens.work_area)(), now_place) {
                        (Some(area), Some(here)) => {
                            let target = centered(area, size, frame, here.scale, zoom);
                            self.full = Some((target, max));
                            self.go(ctx, target, max, false)
                        }
                        _ => {
                            self.limits(ctx, false);
                            ctx.send_viewport_cmd(ViewportCommand::InnerSize(size));
                            Step::Settle(Some(size))
                        }
                    }
                }
            }
        }
    }

    fn go(&mut self, ctx: &egui::Context, target: Place, maximize: bool, mini: bool) -> Step {
        let ppp = ctx.pixels_per_point();
        ctx.send_viewport_cmd(ViewportCommand::OuterPosition(pos2(target.x, target.y) / ppp));
        self.limits(ctx, mini);
        let scale = ctx.input(|i| i.viewport().native_pixels_per_point);
        if scale.is_some_and(|s| (s - target.scale).abs() >= 0.01) && !maximize {
            return Step::Rescale(target);
        }
        let size = target.size_at(ctx.zoom_factor());
        ctx.send_viewport_cmd(ViewportCommand::InnerSize(size));
        if maximize {
            ctx.send_viewport_cmd(ViewportCommand::Maximized(true));
            return Step::Settle(None);
        }
        Step::Settle(Some(size))
    }

    fn limits(&mut self, ctx: &egui::Context, mini: bool) {
        if mini {
            ctx.send_viewport_cmd(ViewportCommand::MinInnerSize(vec2(MIN_W, SIZE.y)));
            ctx.send_viewport_cmd(ViewportCommand::MaxInnerSize(vec2(MAX_W, SIZE.y)));
        } else {
            ctx.send_viewport_cmd(ViewportCommand::MaxInnerSize(Vec2::INFINITY));
            ctx.send_viewport_cmd(ViewportCommand::MinInnerSize(Vec2::from(crate::MIN_SIZE)));
        }
        self.limits_scale = Some(ctx.pixels_per_point());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_place_is_the_work_areas_corner() {
        let area = Rect::from_min_max(pos2(0.0, 0.0), pos2(2560.0, 1400.0));
        let p = corner_place(area, Vec2::ZERO, 1.0, 1.0);
        assert_eq!((p.x, p.y, p.w, p.h), (2560.0 - 400.0 - 16.0, 1400.0 - 112.0 - 16.0, SIZE.x, SIZE.y));
        let p = corner_place(area, vec2(0.0, 0.0), 1.5, 1.0);
        assert_eq!((p.x, p.y, p.w), (2560.0 - 600.0 - 24.0, 1400.0 - 168.0 - 24.0, SIZE.x));
        let left = Rect::from_min_max(pos2(-1920.0, 100.0), pos2(0.0, 1140.0));
        let p = corner_place(left, vec2(2.0, 2.0), 1.0, 1.0);
        assert_eq!((p.x, p.y), (-402.0 - 16.0, 1140.0 - 114.0 - 16.0));
        let tiny = Rect::from_min_max(pos2(10.0, 20.0), pos2(200.0, 50.0));
        let p = corner_place(tiny, Vec2::ZERO, 1.0, 1.0);
        assert_eq!((p.x, p.y), (10.0, 20.0));
        let p = corner_place(area, Vec2::ZERO, 1.0, 2.0);
        assert_eq!((p.x, p.y, p.zoom), (2560.0 - 800.0 - 16.0, 1400.0 - 224.0 - 16.0, 2.0));
    }

    #[test]
    fn a_lost_full_window_is_centred() {
        let area = Rect::from_min_max(pos2(1920.0, 0.0), pos2(3840.0, 1040.0));
        let p = centered(area, vec2(1200.0, 780.0), Vec2::ZERO, 1.0, 1.0);
        assert_eq!((p.x, p.y, p.w, p.h), (1920.0 + 360.0, 130.0, 1200.0, 780.0));
        let p = centered(area, vec2(1000.0, 800.0), Vec2::ZERO, 2.0, 1.0);
        assert_eq!((p.x, p.y), (1920.0, 0.0));
    }

    #[test]
    fn remembered_sizes_stay_in_limits() {
        let p = Place { x: 5.0, y: 6.0, w: 2000.0, h: 300.0, scale: 1.25, zoom: 1.0 };
        assert_eq!(clamp_size(p), Place { w: MAX_W, h: SIZE.y, ..p });
        assert_eq!(clamp_size(Place { w: 10.0, ..p }).w, MIN_W);
    }

    #[test]
    fn full_window_is_saved_in_eframes_format() {
        let p = Place { x: 814.0, y: -183.0, w: 1200.0, h: 812.0, scale: 1.0, zoom: 1.0 };
        assert_eq!(
            p.eframe_window(false),
            "(normal_geometry:(outer_position_pixels:Some((x:814.0,y:-183.0)),scale_factor:1.0,inner_size_points:(x:1200.0,y:812.0)),fullscreen:false,maximized:false)"
        );
        let s = p.eframe_window(true);
        assert!(s.ends_with("maximized:true)"));
    }

    #[test]
    fn places_round_trip_through_settings() {
        let p = Place { x: -1500.0, y: 900.0, w: 420.0, h: SIZE.y, scale: 1.5, zoom: 1.25 };
        let back: Place = serde_json::from_str(&serde_json::to_string(&p).unwrap()).unwrap();
        assert_eq!(back, p);
        let old: Place = serde_json::from_str(r#"{"x":1.0,"y":2.0,"w":400.0,"h":112.0,"scale":1.0}"#).unwrap();
        assert_eq!(old.zoom, 1.0);
        assert_eq!(Place { w: 1250.0, h: 1000.0, zoom: 1.0, ..p }.size_at(1.25), vec2(1000.0, 800.0));
        let m = Mini::load(|k| (k == "mini_window").then(|| serde_json::to_string(&p).unwrap()));
        assert_eq!(m.place, Some(p));
        assert_eq!(m.pinned, PINNED_BY_DEFAULT);
        let m = Mini::load(|k| (k == "mini_pinned").then(|| "0".to_owned()));
        assert!(!m.pinned && m.place.is_none() && !m.active());
    }
}
