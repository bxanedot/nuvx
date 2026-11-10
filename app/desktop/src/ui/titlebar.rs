use egui::{Color32, CursorIcon, Id, Pos2, Rect, ResizeDirection, Sense, Stroke, Ui, Vec2, ViewportCommand, pos2, vec2};

use crate::theme;

pub const HEIGHT: f32 = 56.0;
const BUTTON_W: f32 = 46.0;
const EDGE: f32 = 5.0;
const CORNER: f32 = 14.0;

pub fn custom() -> bool {
    cfg!(any(windows, target_os = "linux")) && std::env::var_os("NUVX_NATIVE_TITLEBAR").is_none()
}

static MAC: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub fn set_mac(on: bool) {
    MAC.store(on, std::sync::atomic::Ordering::Relaxed);
}

pub fn mac() -> bool {
    MAC.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn lights_shown(ctx: &egui::Context) -> bool {
    mac() && shown(ctx)
}

pub fn set_fullscreen(ctx: &egui::Context, on: bool) {
    ctx.data_mut(|d| d.insert_temp(Id::unique("titlebar-fullscreen"), on));
}

fn shown(ctx: &egui::Context) -> bool {
    custom() && !ctx.data(|d| d.get_temp::<bool>(Id::unique("titlebar-fullscreen")).unwrap_or(false)) && !ctx.input(|i| i.viewport().fullscreen.unwrap_or(false))
}

fn maximized(ctx: &egui::Context) -> bool {
    ctx.input(|i| i.viewport().maximized.unwrap_or(false))
}

pub fn buttons_rect(ctx: &egui::Context) -> Option<Rect> {
    (shown(ctx) && !mac()).then(|| {
        let screen = ctx.content_rect();
        Rect::from_min_size(pos2(screen.right() - 3.0 * BUTTON_W, screen.top()), vec2(3.0 * BUTTON_W, HEIGHT))
    })
}

pub fn top_margin(normal: i8, row: f32) -> i8 {
    if custom() { ((HEIGHT - row) / 2.0).round() as i8 } else { normal }
}

fn overlap(ctx: &egui::Context, row: Rect) -> f32 {
    buttons_rect(ctx).map_or(0.0, |b| (row.right() - b.left()).max(0.0))
}

pub fn right_gap(ctx: &egui::Context, row: Rect, normal: f32) -> f32 {
    let o = overlap(ctx, row);
    if o > 0.0 { o + 10.0 } else { normal }
}

pub fn drag_area(ui: &Ui, rect: Rect, id: &str) {
    if !shown(ui.ctx()) {
        return;
    }
    if drag_strip(ui, rect, id).double_clicked() {
        let max = maximized(ui.ctx());
        ui.ctx().send_viewport_cmd(ViewportCommand::Maximized(!max));
    }
}

pub fn drag_strip(ui: &Ui, rect: Rect, id: &str) -> egui::Response {
    let resp = ui.interact(rect, Id::unique(("titlebar-drag", id)), Sense::CLICK);
    let pressed_here = resp.id.with("pressed");
    let (pressed, down, dragging) = ui.input(|i| (i.pointer.primary_pressed(), i.pointer.primary_down(), i.pointer.is_decidedly_dragging()));
    if pressed {
        let here = resp.is_pointer_button_down_on();
        ui.ctx().data_mut(|d| d.insert_temp(pressed_here, here));
    } else if !down {
        ui.ctx().data_mut(|d| d.remove::<bool>(pressed_here));
    }
    if !resp.double_clicked() && down && dragging && ui.ctx().data_mut(|d| d.remove_temp::<bool>(pressed_here)).unwrap_or(false) {
        ui.ctx().send_viewport_cmd(ViewportCommand::StartDrag);
    }
    resp
}

pub fn edge_inset() -> f32 {
    if custom() { EDGE } else { 0.0 }
}

pub fn window_buttons(ctx: &egui::Context) {
    let Some(rect) = buttons_rect(ctx) else { return };
    let max = maximized(ctx);
    egui::Area::new(Id::unique("window-buttons")).order(egui::Order::Foreground).fixed_pos(rect.min).show(ctx, |ui| {
        let kinds = [(Kind::Minimize, "Minimize"), (if max { Kind::Restore } else { Kind::Maximize }, if max { "Restore" } else { "Maximize" }), (Kind::Close, "Close")];
        for (i, (kind, name)) in kinds.into_iter().enumerate() {
            let r = Rect::from_min_size(rect.min + vec2(i as f32 * BUTTON_W, 0.0), vec2(BUTTON_W, HEIGHT));
            let resp = ui.interact(r, Id::unique(("window-button", i)), Sense::click());
            let resp = super::widgets::named(resp, name);
            paint_button(ui, r, kind, &resp);
            if resp.has_focus() {
                ui.painter().rect_stroke(r.shrink(3.0), 3.0, Stroke::new(1.5, theme::text()), egui::StrokeKind::Inside);
            }
            if resp.clicked() {
                ctx.send_viewport_cmd(match kind {
                    Kind::Minimize => ViewportCommand::Minimized(true),
                    Kind::Maximize => ViewportCommand::Maximized(true),
                    Kind::Restore => ViewportCommand::Maximized(false),
                    Kind::Close => ViewportCommand::Close,
                });
            }
        }
    });
}

pub fn traffic_lights(ui: &Ui, rect: Rect) {
    let ctx = ui.ctx().clone();
    let max = maximized(&ctx);
    let focused = ctx.input(|i| i.viewport().focused.unwrap_or(true));
    let over = ui.rect_contains_pointer(rect);
    let green = if max { (Kind::Restore, "Restore") } else { (Kind::Maximize, "Maximize") };
    let lights = [
        (Kind::Close, "Close", Color32::from_rgb(0xFF, 0x5F, 0x57)),
        (Kind::Minimize, "Minimize", Color32::from_rgb(0xFE, 0xBC, 0x2E)),
        (green.0, green.1, Color32::from_rgb(0x28, 0xC8, 0x40)),
    ];
    let p = ui.painter();
    for (i, (kind, name, color)) in lights.into_iter().enumerate() {
        let c = pos2(rect.left() + 6.0 + i as f32 * 20.0, rect.center().y);
        let resp = ui.interact(Rect::from_center_size(c, vec2(16.0, 16.0)), Id::unique(("traffic-light", i)), Sense::click());
        let resp = super::widgets::named(resp, name);
        let fill = if !focused && !over {
            theme::glass(0.22)
        } else if resp.is_pointer_button_down_on() {
            theme::lerp_color(color, Color32::BLACK, 0.2)
        } else {
            color
        };
        p.circle_filled(c, 6.0, fill);
        p.circle_stroke(c, 6.0, Stroke::new(0.5, Color32::from_black_alpha(50)));
        if over {
            let ink = match kind {
                Kind::Close => Color32::from_rgb(0x4D, 0x00, 0x00),
                Kind::Minimize => Color32::from_rgb(0x99, 0x57, 0x00),
                Kind::Maximize | Kind::Restore => Color32::from_rgb(0x00, 0x65, 0x00),
            };
            let s = Stroke::new(1.1, ink);
            let tri = |pts: [Vec2; 3]| egui::Shape::convex_polygon(pts.iter().map(|v| c + *v).collect(), ink, Stroke::NONE);
            match kind {
                Kind::Close => {
                    p.line_segment([c + vec2(-2.3, -2.3), c + vec2(2.3, 2.3)], s);
                    p.line_segment([c + vec2(-2.3, 2.3), c + vec2(2.3, -2.3)], s);
                }
                Kind::Minimize => {
                    p.line_segment([c + vec2(-3.2, 0.0), c + vec2(3.2, 0.0)], Stroke::new(1.3, ink));
                }
                Kind::Maximize => {
                    p.add(tri([vec2(-3.0, -3.0), vec2(1.3, -3.0), vec2(-3.0, 1.3)]));
                    p.add(tri([vec2(3.0, 3.0), vec2(-1.3, 3.0), vec2(3.0, -1.3)]));
                }
                Kind::Restore => {
                    p.add(tri([vec2(-0.5, -0.5), vec2(-0.5, -4.2), vec2(-4.2, -0.5)]));
                    p.add(tri([vec2(0.5, 0.5), vec2(4.2, 0.5), vec2(0.5, 4.2)]));
                }
            }
        }
        if resp.has_focus() {
            p.circle_stroke(c, 8.0, Stroke::new(1.5, theme::text()));
        }
        if resp.clicked() {
            match kind {
                Kind::Minimize => ctx.send_viewport_cmd(ViewportCommand::Minimized(true)),
                Kind::Maximize => ctx.send_viewport_cmd(ViewportCommand::Maximized(true)),
                Kind::Restore => ctx.send_viewport_cmd(ViewportCommand::Maximized(false)),
                Kind::Close => ctx.send_viewport_cmd(ViewportCommand::Close),
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Minimize,
    Maximize,
    Restore,
    Close,
}

fn paint_button(ui: &Ui, r: Rect, kind: Kind, resp: &egui::Response) {
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.1);
    let down = resp.is_pointer_button_down_on();
    let p = ui.painter();
    let fg = if kind == Kind::Close {
        let red = if down { Color32::from_rgb(0x94, 0x1F, 0x15) } else { Color32::from_rgb(0xC4, 0x2B, 0x1C) };
        if h > 0.0 {
            p.rect_filled(r, 0.0, red.gamma_multiply(h));
        }
        theme::lerp_color(theme::text_dim(), Color32::WHITE, h)
    } else {
        if h > 0.0 {
            p.rect_filled(r, 0.0, theme::glass(if down { 0.16 } else { 0.10 } * h));
        }
        theme::lerp_color(theme::text_dim(), theme::text(), h)
    };
    let px = 1.0 / ui.ctx().pixels_per_point();
    let snap = |p: Pos2| pos2((p.x / px).round() * px + px / 2.0, (p.y / px).round() * px + px / 2.0);
    let c = snap(r.center());
    let s = Stroke::new(1.0, fg);
    match kind {
        Kind::Minimize => {
            p.line_segment([c + vec2(-5.0, 0.0), c + vec2(5.0, 0.0)], s);
        }
        Kind::Maximize => {
            p.rect_stroke(Rect::from_center_size(c, vec2(10.0, 10.0)), 1.0, s, egui::StrokeKind::Middle);
        }
        Kind::Restore => {
            p.rect_stroke(Rect::from_center_size(c + vec2(-1.0, 1.0), vec2(8.0, 8.0)), 1.0, s, egui::StrokeKind::Middle);
            let b = Rect::from_center_size(c + vec2(1.0, -1.0), vec2(8.0, 8.0));
            p.line_segment([pos2(b.left() + 2.0, b.top()), b.right_top()], s);
            p.line_segment([b.right_top(), pos2(b.right(), b.bottom() - 2.0)], s);
        }
        Kind::Close => {
            p.line_segment([c + vec2(-5.0, -5.0), c + vec2(5.0, 5.0)], s);
            p.line_segment([c + vec2(-5.0, 5.0), c + vec2(5.0, -5.0)], s);
        }
    }
}

pub fn resize_edges(ctx: &egui::Context) {
    edges(ctx, false);
}

pub fn resize_sides(ctx: &egui::Context) {
    edges(ctx, true);
}

fn edges(ctx: &egui::Context, sides_only: bool) {
    if !shown(ctx) || maximized(ctx) {
        return;
    }
    let s = ctx.content_rect();
    let (l, r, t, b) = (s.left(), s.right(), s.top(), s.bottom());
    let edges = [
        (Rect::from_min_max(pos2(l + CORNER, t), pos2(r - CORNER, t + EDGE)), ResizeDirection::North, CursorIcon::ResizeNorth),
        (Rect::from_min_max(pos2(l + CORNER, b - EDGE), pos2(r - CORNER, b)), ResizeDirection::South, CursorIcon::ResizeSouth),
        (Rect::from_min_max(pos2(l, t + CORNER), pos2(l + EDGE, b - CORNER)), ResizeDirection::West, CursorIcon::ResizeWest),
        (Rect::from_min_max(pos2(r - EDGE, t + CORNER), pos2(r, b - CORNER)), ResizeDirection::East, CursorIcon::ResizeEast),
        (Rect::from_min_max(pos2(l, t), pos2(l + CORNER, t + EDGE)), ResizeDirection::NorthWest, CursorIcon::ResizeNorthWest),
        (Rect::from_min_max(pos2(l, t), pos2(l + EDGE, t + CORNER)), ResizeDirection::NorthWest, CursorIcon::ResizeNorthWest),
        (Rect::from_min_max(pos2(r - CORNER, t), pos2(r, t + EDGE)), ResizeDirection::NorthEast, CursorIcon::ResizeNorthEast),
        (Rect::from_min_max(pos2(r - EDGE, t), pos2(r, t + CORNER)), ResizeDirection::NorthEast, CursorIcon::ResizeNorthEast),
        (Rect::from_min_max(pos2(l, b - EDGE), pos2(l + CORNER, b)), ResizeDirection::SouthWest, CursorIcon::ResizeSouthWest),
        (Rect::from_min_max(pos2(l, b - CORNER), pos2(l + EDGE, b)), ResizeDirection::SouthWest, CursorIcon::ResizeSouthWest),
        (Rect::from_min_max(pos2(r - CORNER, b - EDGE), pos2(r, b)), ResizeDirection::SouthEast, CursorIcon::ResizeSouthEast),
        (Rect::from_min_max(pos2(r - EDGE, b - CORNER), pos2(r, b)), ResizeDirection::SouthEast, CursorIcon::ResizeSouthEast),
    ];
    egui::Area::new(Id::unique("resize-edges")).order(egui::Order::Foreground).fixed_pos(s.min).show(ctx, |ui| {
        ui.set_clip_rect(s);
        for (i, (rect, dir, cursor)) in edges.into_iter().enumerate() {
            let side = matches!(dir, ResizeDirection::East | ResizeDirection::West);
            if sides_only && !side {
                continue;
            }
            let rect = if sides_only { Rect::from_x_y_ranges(rect.x_range(), s.y_range()) } else { rect };
            let resp = ui.interact(rect, Id::unique(("resize-edge", i)), Sense::DRAG).on_hover_cursor(cursor);
            if resp.is_pointer_button_down_on() && ui.input(|i| i.pointer.primary_pressed()) {
                ctx.send_viewport_cmd(ViewportCommand::BeginResize(dir));
            }
        }
    });
}

#[cfg_attr(not(windows), allow(unused_variables))]
pub fn set_hwnd(hwnd: *mut std::ffi::c_void) {
    #[cfg(windows)]
    if custom() {
        frame::init(hwnd);
    }
}

#[cfg(windows)]
mod frame {
    use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
    use windows::Win32::Graphics::Dwm::DwmExtendFrameIntoClientArea;
    use windows::Win32::UI::Controls::MARGINS;
    use windows::Win32::UI::Shell::{DefSubclassProc, SetWindowSubclass};
    use windows::Win32::UI::WindowsAndMessaging::{NCCALCSIZE_PARAMS, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, SWP_NOZORDER, SetWindowPos, WM_CHAR, WM_NCCALCSIZE, WM_SYSCHAR};

    pub fn init(hwnd: *mut std::ffi::c_void) {
        let hwnd = HWND(hwnd);
        unsafe {
            if !SetWindowSubclass(hwnd, Some(proc), 1, 0).as_bool() {
                log::warn!("title bar: couldn't subclass the window");
                return;
            }
            let _ = DwmExtendFrameIntoClientArea(hwnd, &MARGINS { cxLeftWidth: 0, cxRightWidth: 0, cyTopHeight: 0, cyBottomHeight: 1 });
            let _ = SetWindowPos(hwnd, None, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        }
    }

    unsafe extern "system" fn proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM, _: usize, _: usize) -> LRESULT {
        unsafe {
            if msg == WM_CHAR || msg == WM_SYSCHAR {
                let _ = DefSubclassProc(hwnd, msg, wparam, lparam);
                return LRESULT(0);
            }
            if msg != WM_NCCALCSIZE || wparam.0 == 0 {
                return DefSubclassProc(hwnd, msg, wparam, lparam);
            }
            let params = &mut *(lparam.0 as *mut NCCALCSIZE_PARAMS);
            let proposed = params.rgrc[0];
            let result = DefSubclassProc(hwnd, msg, wparam, lparam);
            let r = params.rgrc[0];
            if (r.left, r.right, r.top, r.bottom) == (proposed.left, proposed.right, proposed.top + 1, proposed.bottom + 1) {
                params.rgrc[0] = proposed;
            }
            result
        }
    }
}
