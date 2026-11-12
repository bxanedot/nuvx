use super::*;

impl App {
    pub(super) fn toast(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), -1.0));
    }
}
