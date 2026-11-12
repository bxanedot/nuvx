use egui::{Color32, FontId};
use crate::preset::{BlurScope, Fill, LyricStyle, ThemeCss, Weight};
use crate::theme;

#[derive(Clone)]
pub(super) struct Cfg {
    pub(super) font: String,
    pub(super) weight: Weight,
    pub(super) size: f32,
    pub(super) active: Color32,
    pub(super) inactive_alpha: f32,
    pub(super) glow: Color32,
    pub(super) glow_rem: f32,
    pub(super) scale: f32,
    pub(super) active_scale: f32,
    pub(super) padding: f32,
    pub(super) target_ratio: f32,
    pub(super) centered: bool,
    pub(super) fill: Fill,
    pub(super) wave_crest: f32,
    pub(super) wave_settle: f32,
    pub(super) emphasis: f32,
    pub(super) upcoming_alpha: f32,
    pub(super) falloff: f32,
    pub(super) min_alpha: f32,
    pub(super) past_alpha: Option<f32>,
    pub(super) blur: f32,
    pub(super) blur_scope: BlurScope,
    pub(super) rem: f32,
    pub(super) bg_em: f32,
    pub(super) pad: (f32, f32),
    pub(super) wobble: bool,
    pub(super) wave_duration: f64,
    pub(super) glow_alpha: f32,
    pub(super) long_glow_alpha: f32,
    pub(super) css: Option<ThemeCss>,
}

pub(super) const GLOW_INVISIBLE: f32 = 0.5 / 255.0;

impl Cfg {
    pub(super) fn max_scale(&self) -> f32 {
        self.active_scale.max(self.css.as_ref().and_then(|c| c.hover_scale).unwrap_or(1.0)).max(1.0)
    }

    pub(super) fn new(s: &LyricStyle, accent: Color32, width: f32, viewport: f32, centered: bool, scale: f32) -> Self {
        let css = s.css.clone();
        let (mut size, mut rem) = match css.as_ref().filter(|c| !c.font_rem.is_empty()) {
            Some(c) => {
                let r = c.font_rem.iter().filter(|st| viewport >= st[0]).map(|st| st[1]).next_back().unwrap_or(c.font_rem[0][1]);
                (r * 10.0, 10.0)
            }
            None => {
                let size = 48.0 * s.size.clamp(0.6, 1.6);
                (size, size / 3.0)
            }
        };
        let cap = (width / 10.5).max(22.0);
        if size > cap {
            rem *= cap / size;
            size = cap;
        }
        let rounded = (size * scale).round().max(1.0);
        rem *= rounded / size;
        size = rounded;
        let glow = s.glow.clamp(0.0, 1.0);
        let weight = match css.as_ref().and_then(|c| c.font_weight) {
            Some(w) if w >= 700 => Weight::Bold,
            Some(w) if w >= 550 => Weight::Semibold,
            Some(_) => Weight::Regular,
            None => s.weight,
        };
        let wave = css.as_ref().and_then(|c| c.letter_wave).unwrap_or([0.05, 0.02, 0.9, 1.11]);
        let (crest, settle) = if css.is_some() { (wave[0], wave[1]) } else { (s.lift.clamp(0.0, 8.0) / size, s.lift.clamp(0.0, 8.0) / size) };
        let (glow_alpha, long_glow_alpha) = match css.as_ref().and_then(|c| c.glow_alpha) {
            Some(a) => (a, css.as_ref().and_then(|c| c.long_word_glow_alpha).unwrap_or(a)),
            None => (glow, glow),
        };
        let pad = css.as_ref().and_then(|c| c.padding_inline).map_or((0.0, 0.0), |p| (p[0] * rem / 10.0, p[1] * rem / 10.0));
        Self {
            font: theme::effective_font(&s.font),
            weight,
            size,
            active: crate::preset::paint(&s.sung_color, accent),
            inactive_alpha: s.active_alpha,
            glow: crate::preset::paint(&s.glow_color, accent),
            glow_rem: if css.is_some() { 0.8 } else { 0.8 * (1.0 + glow) },
            scale: 0.95,
            active_scale: s.active_scale.max(1.0),
            padding: rem * 2.0 * (s.line_gap / 0.6).clamp(0.2, 3.0),
            target_ratio: s.anchor.clamp(0.1, 0.7),
            centered,
            fill: s.fill,
            wave_crest: crest,
            wave_settle: settle,
            emphasis: wave[3],
            upcoming_alpha: s.inactive_alpha,
            falloff: s.falloff,
            min_alpha: s.min_alpha,
            past_alpha: s.past_alpha,
            blur: s.blur,
            blur_scope: s.blur_scope,
            rem,
            bg_em: css.as_ref().and_then(|c| c.background_em).unwrap_or(0.75),
            pad,
            wobble: css.as_ref().is_some_and(|c| !c.no_wobble),
            wave_duration: wave[2] as f64,
            glow_alpha,
            long_glow_alpha,
            css,
        }
    }
    pub(super) fn rem(&self) -> f32 {
        self.rem
    }
    pub(super) fn main_font(&self, text: &str) -> FontId {
        theme::lyric_font_for(&self.font, self.weight, self.size, text)
    }
    pub(super) fn bg_font(&self, text: &str) -> FontId {
        theme::lyric_font_for(&self.font, Weight::Semibold, self.size * self.bg_em, text)
    }
    pub(super) fn roman_font(&self, text: &str) -> FontId {
        theme::lyric_font_for(&self.font, Weight::Semibold, self.rem() * 2.0 / 1.25, text)
    }
    pub(super) fn trans_font(&self, text: &str) -> FontId {
        theme::lyric_font_for(&self.font, Weight::Semibold, self.rem() * 2.0, text)
    }
}
