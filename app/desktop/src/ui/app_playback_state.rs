use super::*;

pub(super) struct MixFrom {
    pub(super) track: Track,
    pub(super) total: f32,
    pub(super) mix: f32,
    pub(super) started: bool,
    pub(super) released: bool,
}

pub(super) struct PlayingAs {
    pub(super) generation: u64,
    pub(super) listed: String,
    pub(super) song: Track,
}
