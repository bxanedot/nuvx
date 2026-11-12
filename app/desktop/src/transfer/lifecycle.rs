use super::*;

impl Transfer {
    pub fn set_paused(&self, paused: bool) {
        if let Some(j) = self.job.lock().unwrap().as_mut() {
            j.paused = paused;
        }
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
        self.review_done.notify_one();
    }

    pub fn dismiss(&self) {
        let mut job = self.job.lock().unwrap();
        if job.as_ref().is_some_and(|j| !j.phase.running()) {
            *job = None;
        }
    }

    pub fn start(self: &Arc<Self>, rt: &crate::backend::Backend, source: Source, dest: Dest, resume: bool) {
        if self.running() {
            return;
        }
        if !resume {
            self.forget(&source);
        }
        self.cancel.store(false, Ordering::Relaxed);
        *self.resumable.lock().unwrap() = None;
        *self.job.lock().unwrap() = Some(Job {
            name: String::new(),
            source: source.clone(),
            dest,
            phase: Phase::Reading,
            read: (0, 0),
            read_secs: None,
            tracks: Vec::new(),
            results: Vec::new(),
            not_songs: 0,
            search_started: None,
            doubts: Vec::new(),
            saving: (0, 0),
            redo: None,
            waiting: false,
            summary: None,
            paused: false,
            started: Instant::now(),
            created: Vec::new(),
            added: 0,
            liked: 0,
            reviewed: false,
        });
        let (this, api) = (self.clone(), rt.api());
        *self.pinned.lock().unwrap() = Some((api.clone(), api.epoch()));
        rt.spawn(async move {
            let res = this.run(&api).await;
            let mut guard = this.job.lock().unwrap();
            if let Some(job) = guard.as_mut() {
                match res {
                    Ok(()) => {}
                    Err(_) if this.cancel.load(Ordering::Relaxed) => {
                        this.forget(&job.source);
                        *guard = None;
                    }
                    Err(e) => {
                        log::warn!("spotify transfer: {e:#}");
                        let saved = !job.tracks.is_empty();
                        job.phase = Phase::Failed(if saved { format!("{e}. Your progress is saved; try again to pick up where it stopped.") } else { e.to_string() });
                        drop(guard);
                        *this.resumable.lock().unwrap() = this.find_resumable();
                    }
                }
            }
            this.wake();
        });
    }

    pub fn discard_saved(&self, source: &Source) {
        self.forget(source);
        *self.resumable.lock().unwrap() = self.find_resumable();
    }
}
