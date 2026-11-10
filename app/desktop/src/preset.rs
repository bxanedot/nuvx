use std::sync::OnceLock;

use egui::Color32;
use serde::Deserialize;

pub const DEFAULT_ID: &str = "nuvx";

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BgMode {
    #[default]
    Kawarp,
    CoverStatic,
    AccentGradient,
    Solid,
    CoverFullbleed,
}

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct Background {
    pub mode: BgMode,
    pub solid: String,
    pub intensity: f32,
    pub saturation: f32,
    pub speed: f32,
    pub scale: f32,
    pub np_dim: f32,
    pub page_dim: f32,
    pub reactive: f32,
    pub scrim: f32,
}

impl Default for Background {
    fn default() -> Self {
        Self {
            mode: BgMode::Kawarp,
            solid: "#0c0a0e".into(),
            intensity: 1.0,
            saturation: 1.5,
            speed: 1.0,
            scale: 1.2,
            np_dim: 0.85,
            page_dim: 0.5,
            reactive: 1.0,
            scrim: 0.3,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Align {
    #[default]
    Left,
    Center,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Weight {
    Regular,
    Semibold,
    #[default]
    Bold,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Fill {
    #[default]
    Sweep,
    Word,
    Line,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlurScope {
    #[default]
    All,
    Past,
    Upcoming,
}

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct LyricStyle {
    pub align: Align,
    pub weight: Weight,
    pub font: String,
    pub size: f32,
    pub line_gap: f32,
    pub anchor: f32,
    pub fill: Fill,
    pub active_alpha: f32,
    pub inactive_alpha: f32,
    pub falloff: f32,
    pub min_alpha: f32,
    pub past_alpha: Option<f32>,
    pub sung_color: String,
    pub blur: f32,
    pub blur_scope: BlurScope,
    pub glow: f32,
    pub glow_color: String,
    pub active_scale: f32,
    pub lift: f32,
    pub css: Option<ThemeCss>,
}

#[derive(Clone, Default, Deserialize)]
#[serde(default)]
pub struct ThemeCss {
    pub font_rem: Vec<[f32; 2]>,
    pub font_weight: Option<u16>,
    pub past: Option<[f32; 2]>,
    pub upcoming: Option<[f32; 2]>,
    pub opacity_transition: Option<[f32; 2]>,
    pub filter_transition: Option<[f32; 2]>,
    pub glow_alpha: Option<f32>,
    pub long_word_glow_alpha: Option<f32>,
    pub no_wobble: bool,
    pub letter_wave: Option<[f32; 4]>,
    pub background_em: Option<f32>,
    pub hover_scale: Option<f32>,
    pub padding_inline: Option<[f32; 2]>,
}

impl Default for LyricStyle {
    fn default() -> Self {
        Self {
            align: Align::Left,
            weight: Weight::Bold,
            font: "SF Pro Display".into(),
            size: 1.42,
            line_gap: 0.6,
            anchor: 0.43,
            fill: Fill::Sweep,
            active_alpha: 0.3,
            inactive_alpha: 0.3,
            falloff: 0.01,
            min_alpha: 0.25,
            past_alpha: Some(0.0),
            sung_color: "white".into(),
            blur: 2.0,
            blur_scope: BlurScope::Upcoming,
            glow: 0.0,
            glow_color: "white".into(),
            active_scale: 1.05,
            lift: 3.0,
            css: None,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Layout {
    #[default]
    Split,
    LyricsOnly,
    CoverHero,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Side {
    #[default]
    Left,
    Right,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Shape {
    #[default]
    Rounded,
    Circle,
    Record,
}

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct CoverStyle {
    pub layout: Layout,
    pub side: Side,
    pub shape: Shape,
    pub radius: f32,
    pub spin_secs: f32,
    pub brightness: f32,
    pub size: f32,
    pub shadow: f32,
    pub glow: f32,
}

impl Default for CoverStyle {
    fn default() -> Self {
        Self { layout: Layout::Split, side: Side::Left, shape: Shape::Rounded, radius: 12.0, spin_secs: 60.0, brightness: 1.0, size: 1.0, shadow: 0.55, glow: 0.0 }
    }
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlayerBar {
    #[default]
    Docked,
    Floating,
}

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct Chrome {
    pub glass: f32,
    pub radius: f32,
    pub player_bar: PlayerBar,
}

impl Default for Chrome {
    fn default() -> Self {
        Self { glass: 1.0, radius: 18.0, player_bar: PlayerBar::Docked }
    }
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PageBg {
    #[default]
    Same,
    Static,
    Accent,
    Solid,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SidebarStyle {
    #[default]
    Glass,
    Transparent,
    Solid,
    Floating,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NavStyle {
    #[default]
    Pill,
    Accent,
    Bar,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChipStyle {
    #[default]
    Pill,
    Outline,
    Accent,
}

#[derive(Clone, Deserialize)]
#[serde(default)]
pub struct AppStyle {
    pub font: String,
    pub page_bg: PageBg,
    pub page_dim: Option<f32>,
    pub sidebar: SidebarStyle,
    pub sidebar_alpha: f32,
    pub bar_alpha: f32,
    pub bar_border: bool,
    pub nav: NavStyle,
    pub chip: ChipStyle,
    pub card_radius: f32,
    pub thumb_radius: f32,
    pub panel_radius: f32,
    pub hover: f32,
    pub text_dim: String,
}

impl Default for AppStyle {
    fn default() -> Self {
        Self {
            font: "inter".into(),
            page_bg: PageBg::Same,
            page_dim: None,
            sidebar: SidebarStyle::Glass,
            sidebar_alpha: 0.28,
            bar_alpha: 0.55,
            bar_border: false,
            nav: NavStyle::Pill,
            chip: ChipStyle::Pill,
            card_radius: 8.0,
            thumb_radius: 5.0,
            panel_radius: 14.0,
            hover: 1.0,
            text_dim: "#b2acba".into(),
        }
    }
}

#[derive(Clone, Default, Deserialize)]
#[serde(default)]
pub struct Preset {
    pub id: String,
    pub name: String,
    pub creators: Vec<String>,
    pub blurb: String,
    pub background: Background,
    pub accent: String,
    pub lyrics: LyricStyle,
    pub cover: CoverStyle,
    pub chrome: Chrome,
    pub app: AppStyle,
}

impl Preset {
    fn builtin() -> Self {
        Self {
            id: DEFAULT_ID.into(),
            name: "nuvx".into(),
            blurb: "The app's own look: animated album-art background, left-aligned karaoke lyrics.".into(),
            accent: "cover".into(),
            ..Default::default()
        }
    }

    pub fn accent(&self, from_cover: Color32) -> Color32 {
        if self.accent == "system" {
            return crate::theme::system_accent().unwrap_or(from_cover);
        }
        hex(&self.accent).unwrap_or(from_cover)
    }

    pub fn solid(&self) -> Color32 {
        hex(&self.background.solid).unwrap_or(crate::theme::bg())
    }
}

pub fn paint(name: &str, accent: Color32) -> Color32 {
    match name {
        "accent" | "cover" => accent,
        "white" | "" => Color32::WHITE,
        s => hex(s).unwrap_or(Color32::WHITE),
    }
}

pub fn hex(s: &str) -> Option<Color32> {
    let s = s.strip_prefix('#')?;
    if s.len() != 6 {
        return None;
    }
    let v = u32::from_str_radix(s, 16).ok()?;
    Some(Color32::from_rgb((v >> 16) as u8, (v >> 8) as u8, v as u8))
}

pub fn all() -> &'static [Preset] {
    static ALL: OnceLock<Vec<Preset>> = OnceLock::new();
    ALL.get_or_init(|| {
        let mut v = vec![Preset::builtin()];
        match serde_json::from_str::<Vec<Preset>>(include_str!("../assets/themes/presets.json")) {
            Ok(list) => v.extend(list),
            Err(e) => log::error!("theme presets: {e}"),
        }
        v
    })
}

static ACTIVE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

pub fn set_active(id: &str) {
    let i = all().iter().position(|p| p.id == id).unwrap_or(0);
    ACTIVE.store(i, std::sync::atomic::Ordering::Relaxed);
}

pub fn active() -> &'static Preset {
    &all()[ACTIVE.load(std::sync::atomic::Ordering::Relaxed).min(all().len() - 1)]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn presets_are_complete() {
        let list: Vec<Preset> = serde_json::from_str(include_str!("../assets/themes/presets.json")).expect("presets.json parses");
        assert_eq!(list.len(), 27);
        let mut ids = std::collections::HashSet::new();
        for p in &list {
            assert!(ids.insert(p.id.clone()), "duplicate id {}", p.id);
            assert!(!p.name.is_empty() && !p.creators.is_empty() && !p.blurb.is_empty(), "{} lacks name/creators/blurb", p.id);
            assert!(matches!(p.accent.as_str(), "cover" | "system" | "") || hex(&p.accent).is_some(), "{} accent {}", p.id, p.accent);
        }
        assert_eq!(all().len(), list.len() + 1);
    }
}
