









pub(super) struct VoiceBridge {

    pub(super) delay_frames: u64,

    pub(super) unit_frames: u64,

    pub(super) repeats: u32,

    pub(super) served: u64,

    pub(super) captured: Vec<f32>,

    pub(super) replay_at: usize,
    pub(super) done_replays: u32,
    pub(super) stage: BridgeStage,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum BridgeStage {
    Waiting,
    Capturing,
    Replaying,
    Done,
}

impl VoiceBridge {
    pub(super) fn new(delay_frames: u64, unit_frames: u64, repeats: u32) -> Self {
        Self {
            delay_frames,
            unit_frames,
            repeats: repeats.max(1),
            served: 0,
            captured: Vec::with_capacity(unit_frames as usize * 2),
            replay_at: 0,
            done_replays: 0,
            stage: BridgeStage::Waiting,
        }
    }





    pub(super) fn replay_into(&mut self, frames: usize, rate: u32, out: &mut Vec<f32>) {
        let seam =
            ((rate as u64 / 100).max(1) as usize).min((self.unit_frames / 4).max(1) as usize);
        while out.len() < frames * 2 && self.stage == BridgeStage::Replaying {
            let at = self.replay_at;
            if at >= self.unit_frames as usize {
                self.replay_at = 0;
                self.done_replays += 1;
                if self.done_replays >= self.repeats {
                    self.stage = BridgeStage::Done;
                }
                continue;
            }
            if at < seam {
                let gain = (at + 1) as f32 / seam as f32;
                let tail = (self.unit_frames as usize - seam + at) * 2;
                out.push(self.captured[tail] * (1.0 - gain) + self.captured[at * 2] * gain);
                out.push(self.captured[tail + 1] * (1.0 - gain) + self.captured[at * 2 + 1] * gain);
            } else {
                out.push(self.captured[at * 2]);
                out.push(self.captured[at * 2 + 1]);
            }
            self.replay_at += 1;
        }
    }





    pub(super) fn after_pull(
        &mut self,
        out: &mut Vec<f32>,
        pending: &mut Vec<f32>,
        pending_cursor: &mut usize,
    ) -> usize {
        if self.stage != BridgeStage::Waiting && self.stage != BridgeStage::Capturing {
            return 0;
        }
        let mut frames = out.len() / 2;
        let mut skip = 0usize;
        if self.stage == BridgeStage::Waiting {
            if self.served + frames as u64 <= self.delay_frames {
                self.served += frames as u64;
                return 0;
            }
            let into = (self.delay_frames - self.served) as usize;
            skip = into.min(frames);
            self.served += skip as u64;
            frames -= skip;
            self.stage = BridgeStage::Capturing;
        }
        let captured_frames = self.captured.len() / 2;
        let remaining = (self.unit_frames as usize).saturating_sub(captured_frames);
        let take = frames.min(remaining);
        if take > 0 {
            let start = skip * 2;
            self.captured
                .extend_from_slice(&out[start..start + take * 2]);
        }
        if self.captured.len() / 2 == self.unit_frames as usize {
            let cut = (skip + take) * 2;
            let held = out.split_off(cut);
            let held_frames = held.len() / 2;
            let rest = pending[*pending_cursor..].to_vec();
            *pending = held;
            pending.extend(rest);
            *pending_cursor = 0;
            self.replay_at = 0;
            self.done_replays = 0;
            self.stage = BridgeStage::Replaying;
            return held_frames;
        }
        0
    }
}
