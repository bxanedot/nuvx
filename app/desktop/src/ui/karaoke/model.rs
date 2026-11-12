use super::Align;
use egui::{FontId, Pos2, Rect};

#[derive(Clone)]
pub(super) struct Word {
    pub(super) text: String,
    pub(super) font: FontId,
    pub(super) start: f64,
    pub(super) dur: f64,
    pub(super) line_synced: bool,
    pub(super) bg: bool,
    pub(super) long: bool,
    pub(super) pos: Pos2,
    pub(super) w: f32,
    pub(super) h: f32,
    pub(super) group: usize,
    pub(super) letters: bool,
    pub(super) rtl: bool,
}

#[derive(Clone)]
pub(super) struct Group {
    pub(super) first: usize,
    pub(super) words: std::ops::Range<usize>,
    pub(super) wobbles: bool,
}

#[derive(Clone)]
pub(super) enum DecoBody {
    Timed { words: Vec<Word>, groups: Vec<Group> },
    Text(String, FontId),
}

#[derive(Clone)]
pub(super) struct Deco {
    pub(super) body: DecoBody,
    pub(super) rect: Rect,
    pub(super) pill: bool,
}

#[derive(Clone)]
pub(super) struct Block {
    pub(super) line: Option<usize>,
    pub(super) start: f64,
    pub(super) dur: f64,
    pub(super) instrumental: bool,
    pub(super) align: Align,
    pub(super) top: f32,
    pub(super) height: f32,
    pub(super) words: Vec<Word>,
    pub(super) groups: Vec<Group>,
    pub(super) decos: Vec<Deco>,
    pub(super) credits: Option<(String, FontId)>,
}

pub(super) struct Layout {
    pub(super) key: u64,
    pub(super) width: f32,
    pub(super) blocks: Vec<Block>,
    pub(super) pad_top: f32,
    pub(super) content_h: f32,
    pub(super) richsync: bool,
}
