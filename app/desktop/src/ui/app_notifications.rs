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

    pub(super) fn undo_dislike(&mut self) {
        if let Some(t) = self.toast_undo.take() {
            self.toast = None;
            self.backend.request(Req::Like { video_id: t.id, like: false });
        }
    }
}
