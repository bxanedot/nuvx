use super::*;

impl App {
    pub(super) fn toast(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), -1.0));
        self.toast_undo = None;
    }

    pub(super) fn toast_with_undo(&mut self, msg: impl Into<String>, track: Track) {
        self.toast(msg);
        self.toast_undo = Some(track);
    }

    pub(super) fn tick_sleep(&mut self, ctx: &egui::Context, now: f64) {
        use super::sleep_timer::Step;
        match self.sleep.tick(now) {
            Step::Idle => {}
            Step::Fade(cut) => {
                self.sleep_cut = cut;
                self.audio.set_sleep_cut(cut);
                ctx.request_repaint_after(Duration::from_millis(if cut > 0 { 100 } else { 1000 }));
            }
            Step::Done => {
                self.audio.send(crate::audio::AudioCmd::Pause);
                self.sleep_cut = 0;
                self.audio.set_sleep_cut(0);
                self.toast("Sleep timer ended");
            }
        }
    }

    pub(super) fn undo_dislike(&mut self) {
        if let Some(t) = self.toast_undo.take() {
            self.toast = None;
            self.backend.request(Req::Like { video_id: t.id, like: false });
        }
    }
}
