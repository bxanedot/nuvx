mod fonts;
pub use fonts::*;

use egui::Color32;

const BG: Color32 = Color32::from_rgb(12, 10, 14);
const TEXT: Color32 = Color32::from_rgb(242, 240, 245);
const TEXT_DIM: Color32 = Color32::from_rgb(178, 172, 186);
const TEXT_FAINT: Color32 = Color32::from_rgb(130, 124, 140);
const BG_LIGHT: Color32 = Color32::from_rgb(246, 244, 248);
const TEXT_LIGHT: Color32 = Color32::from_rgb(22, 20, 26);
const TEXT_DIM_LIGHT: Color32 = Color32::from_rgb(86, 82, 94);
const TEXT_FAINT_LIGHT: Color32 = Color32::from_rgb(124, 118, 132);
pub const RED: Color32 = Color32::from_rgb(0xFA, 0x58, 0x6A);

static LIGHT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn light() -> bool {
    LIGHT.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_mode(ctx: &egui::Context, light: bool) -> bool {
    let was = LIGHT.swap(light, std::sync::atomic::Ordering::Relaxed);
    if was != light {
        ctx.global_style_mut(|s| s.visuals = visuals());
    }
    was
}

pub fn text() -> Color32 {
    if light() { TEXT_LIGHT } else { TEXT }
}

pub fn on_text() -> Color32 {
    if light() { Color32::WHITE } else { Color32::from_gray(15) }
}

pub fn text_faint() -> Color32 {
    if light() { TEXT_FAINT_LIGHT } else { TEXT_FAINT }
}

pub fn bg() -> Color32 {
    if light() { BG_LIGHT } else { BG }
}

pub fn menu_fill() -> Color32 {
    if light() { Color32::WHITE } else { Color32::from_rgb(38, 34, 42) }
}

pub fn surface_fill() -> Color32 {
    if light() { Color32::from_rgb(252, 251, 253) } else { Color32::from_rgb(28, 24, 32) }
}

static GLASS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0x3f80_0000);

pub fn glass(a: f32) -> Color32 {
    let k = f32::from_bits(GLASS.load(std::sync::atomic::Ordering::Relaxed));
    let a = ((a * k).min(1.0) * 255.0) as u8;
    if light() { Color32::from_black_alpha(a) } else { Color32::from_white_alpha(a) }
}

pub fn panel(a: f32) -> Color32 {
    if light() { Color32::from_white_alpha((a.clamp(0.0, 1.0) * 255.0) as u8) } else { shade(a) }
}

static DIM: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(u32::from_le_bytes(TEXT_DIM.to_array()));

pub fn text_dim() -> Color32 {
    if light() {
        return TEXT_DIM_LIGHT;
    }
    let [r, g, b, a] = DIM.load(std::sync::atomic::Ordering::Relaxed).to_le_bytes();
    Color32::from_rgba_premultiplied(r, g, b, a)
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum Appearance {
    #[default]
    System,
    Light,
    Dark,
}

impl Appearance {
    pub const ALL: [Self; 3] = [Self::System, Self::Light, Self::Dark];

    pub fn label(self) -> &'static str {
        match self {
            Self::System => "System",
            Self::Light => "Light",
            Self::Dark => "Dark",
        }
    }

    pub fn key(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }

    pub fn from_key(s: &str) -> Self {
        Self::ALL.into_iter().find(|a| a.key() == s).unwrap_or_default()
    }

    pub fn is_light(self, ctx: &egui::Context) -> bool {
        match self {
            Self::Light => true,
            Self::Dark => false,
            Self::System => system_light().unwrap_or_else(|| ctx.system_theme() == Some(egui::Theme::Light)),
        }
    }
}

fn system_light() -> Option<bool> {
    use std::time::{Duration, Instant};
    static MODE: std::sync::Mutex<Option<(Instant, Option<bool>)>> = std::sync::Mutex::new(None);
    let mut cached = MODE.lock().unwrap();
    match *cached {
        Some((at, m)) if at.elapsed() < Duration::from_secs(2) => m,
        _ => {
            let m = read_system_light();
            *cached = Some((Instant::now(), m));
            m
        }
    }
}

#[cfg(windows)]
fn read_system_light() -> Option<bool> {
    use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_DWORD, RegGetValueW};
    use windows::core::w;
    let mut v = 0u32;
    let mut size = 4u32;
    unsafe {
        RegGetValueW(HKEY_CURRENT_USER, w!(r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"), w!("AppsUseLightTheme"), RRF_RT_REG_DWORD, None, Some(&mut v as *mut u32 as *mut _), Some(&mut size))
            .is_ok()
            .then_some(v != 0)
    }
}

#[cfg(not(windows))]
fn read_system_light() -> Option<bool> {
    None
}

pub fn system_accent() -> Option<Color32> {
    use std::time::{Duration, Instant};
    static ACCENT: std::sync::Mutex<Option<(Instant, Option<Color32>)>> = std::sync::Mutex::new(None);
    let mut cached = ACCENT.lock().unwrap();
    match *cached {
        Some((at, c)) if at.elapsed() < Duration::from_secs(2) => c,
        _ => {
            let c = read_system_accent();
            *cached = Some((Instant::now(), c));
            c
        }
    }
}

#[cfg(windows)]
fn read_system_accent() -> Option<Color32> {
    use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_BINARY, RRF_RT_REG_DWORD, RegGetValueW};
    use windows::core::w;
    unsafe {
        let mut palette = [0u8; 32];
        let mut size = palette.len() as u32;
        let key = w!(r"Software\Microsoft\Windows\CurrentVersion\Explorer\Accent");
        if RegGetValueW(HKEY_CURRENT_USER, key, w!("AccentPalette"), RRF_RT_REG_BINARY, None, Some(palette.as_mut_ptr() as *mut _), Some(&mut size)).is_ok() && size >= 8 {
            return Some(Color32::from_rgb(palette[4], palette[5], palette[6]));
        }
        let mut abgr = 0u32;
        let mut size = 4u32;
        RegGetValueW(HKEY_CURRENT_USER, w!(r"Software\Microsoft\Windows\DWM"), w!("AccentColor"), RRF_RT_REG_DWORD, None, Some(&mut abgr as *mut u32 as *mut _), Some(&mut size))
            .is_ok()
            .then(|| Color32::from_rgb(abgr as u8, (abgr >> 8) as u8, (abgr >> 16) as u8))
    }
}

#[cfg(not(windows))]
fn read_system_accent() -> Option<Color32> {
    None
}

pub fn card_radius() -> f32 {
    crate::preset::active().app.card_radius.clamp(0.0, 40.0)
}

pub fn thumb_radius() -> f32 {
    crate::preset::active().app.thumb_radius.clamp(0.0, 20.0)
}

pub fn panel_radius() -> u8 {
    crate::preset::active().app.panel_radius.clamp(0.0, 32.0) as u8
}

pub fn hover_k() -> f32 {
    crate::preset::active().app.hover.clamp(0.0, 2.5)
}

pub fn shade(a: f32) -> Color32 {
    Color32::from_black_alpha((a * 255.0) as u8)
}

pub fn with_alpha(c: Color32, a: f32) -> Color32 {
    c.gamma_multiply(a.clamp(0.0, 1.0))
}

pub fn lerp_color(a: Color32, b: Color32, t: f32) -> Color32 {
    let t = t.clamp(0.0, 1.0);
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
    Color32::from_rgba_premultiplied(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()), l(a.a(), b.a()))
}

pub fn ease_out(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    1.0 - (1.0 - t).powi(3)
}

pub fn ease_in_out(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) / 2.0 }
}

pub const SPEED: f32 = 0.65;

static REDUCE_MOTION: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_reduce_motion(on: bool) {
    REDUCE_MOTION.store(on, std::sync::atomic::Ordering::Relaxed);
}

pub fn speed() -> f32 {
    if REDUCE_MOTION.load(std::sync::atomic::Ordering::Relaxed) { 0.0 } else { SPEED }
}

pub fn anim(ctx: &egui::Context, id: impl std::hash::Hash + std::fmt::Debug, target: f32, secs: f32) -> f32 {
    ctx.animate_value_with_time(egui::Id::unique(id), target, secs * speed())
}

pub fn anim_bool(ctx: &egui::Context, id: impl std::hash::Hash + std::fmt::Debug, on: bool, secs: f32) -> f32 {
    ease_out(ctx.animate_bool_with_time(egui::Id::unique(id), on, secs * speed()))
}

pub mod icon {
    pub const LIBRARY: char = '\u{e100}';
    pub const SEARCH: char = '\u{e151}';
    pub const CHEVRON_LEFT: char = '\u{e06e}';
    pub const CHEVRON_RIGHT: char = '\u{e06f}';
    pub const CHEVRON_UP: char = '\u{e070}';
    pub const CHEVRON_DOWN: char = '\u{e06d}';
    pub const PLAY: char = '\u{e13c}';
    pub const PAUSE: char = '\u{e12e}';
    pub const SHUFFLE: char = '\u{e15e}';
    pub const HEART: char = '\u{e0f2}';
    pub const THUMBS_DOWN: char = '\u{e189}';
    pub const LIST_MUSIC: char = '\u{e2e0}';
    pub const X: char = '\u{e1b2}';
    pub const HISTORY: char = '\u{e1f5}';
    pub const TRASH_2: char = '\u{e18e}';
    pub const ELLIPSIS_VERTICAL: char = '\u{e0b7}';
    pub const LIST_PLUS: char = '\u{e23f}';
    pub const LIST_MINUS: char = '\u{e23e}';
    pub const LIST_CHECKS: char = '\u{e1d0}';
    pub const RADIO: char = '\u{e142}';
    pub const LOG_OUT: char = '\u{e10e}';
    pub const MUSIC: char = '\u{e122}';
    pub const SPARKLES: char = '\u{e412}';
    pub const CHECK: char = '\u{e06c}';
    pub const CIRCLE_USER: char = '\u{e461}';
    pub const UPLOAD: char = '\u{e19e}';
    pub const USERS: char = '\u{e1a4}';
    pub const COPY: char = '\u{e09e}';
    pub const LINK: char = '\u{e102}';
    pub const CROWN: char = '\u{e1d6}';
    pub const HEADPHONES: char = '\u{e0f1}';
    pub const DOWNLOAD: char = '\u{e0b2}';
    pub const FOLDER_OPEN: char = '\u{e247}';
    pub const PIN: char = '\u{e259}';
    pub const PIN_OFF: char = '\u{e2b6}';
    pub const MAXIMIZE_2: char = '\u{e113}';
}

pub fn apply_chrome(ctx: &egui::Context, p: &crate::preset::Preset) {
    GLASS.store(p.chrome.glass.clamp(0.0, 2.5).to_bits(), std::sync::atomic::Ordering::Relaxed);
    let dim = crate::preset::hex(&p.app.text_dim).unwrap_or(TEXT_DIM);
    DIM.store(u32::from_le_bytes(dim.to_array()), std::sync::atomic::Ordering::Relaxed);
    install_style(ctx);
    install_fonts(ctx, &p.app.font);
}

pub fn install_style(ctx: &egui::Context) {
    ctx.set_theme(egui::Theme::Dark);
    ctx.global_style_mut(|s| {
        s.spacing.item_spacing = egui::vec2(8.0, 6.0);
        s.spacing.button_padding = egui::vec2(14.0, 7.0);
        s.spacing.scroll = egui::style::ScrollStyle {
            bar_width: 0.0,
            floating_width: 0.0,
            floating_allocated_width: 0.0,
            handle_min_length: 0.0,
            bar_inner_margin: 0.0,
            bar_outer_margin: 0.0,
            dormant_background_opacity: 0.0,
            active_background_opacity: 0.0,
            interact_background_opacity: 0.0,
            dormant_handle_opacity: 0.0,
            active_handle_opacity: 0.0,
            interact_handle_opacity: 0.0,
            ..egui::style::ScrollStyle::floating()
        };
        s.text_styles.insert(egui::TextStyle::Body, regular(14.0));
        s.text_styles.insert(egui::TextStyle::Button, semibold(14.0));
        s.text_styles.insert(egui::TextStyle::Heading, bold(22.0));
        s.text_styles.insert(egui::TextStyle::Small, regular(12.0));
        s.visuals = visuals();
        s.interaction.selectable_labels = false;
        s.animation_time = 0.18 * speed();
    });
}

fn visuals() -> egui::Visuals {
    let p = crate::preset::active();
    let mut v = if light() { egui::Visuals::light() } else { egui::Visuals::dark() };
    v.panel_fill = bg();
    v.window_fill = surface_fill();
    v.extreme_bg_color = glass(0.08);
    v.override_text_color = Some(text());
    v.selection.bg_fill = with_alpha(RED, if light() { 0.3 } else { 0.5 });
    v.hyperlink_color = text();
    v.interact_cursor = Some(egui::CursorIcon::PointingHand);
    let pr = egui::CornerRadius::same(p.app.panel_radius.clamp(0.0, 32.0) as u8);
    v.window_corner_radius = pr;
    v.menu_corner_radius = pr;
    v.window_stroke = egui::Stroke::new(1.0, glass(0.08));
    v.popup_shadow = egui::epaint::Shadow { offset: [0, 8], blur: 28, spread: 0, color: shade(if light() { 0.16 } else { 0.5 }) };
    let r = egui::CornerRadius::same(p.chrome.radius.clamp(2.0, 28.0) as u8);
    for (w, fill) in [
        (&mut v.widgets.inactive, glass(0.08)),
        (&mut v.widgets.hovered, glass(0.14)),
        (&mut v.widgets.active, glass(0.2)),
        (&mut v.widgets.open, glass(0.14)),
    ] {
        w.bg_fill = fill;
        w.weak_bg_fill = fill;
        w.bg_stroke = egui::Stroke::NONE;
        w.corner_radius = r;
        w.fg_stroke.color = text();
    }
    v.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, glass(0.08));
    v
}

