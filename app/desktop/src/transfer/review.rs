use super::*;

impl Transfer {
    pub fn decide(&self, doubt: usize, decision: Decision) {
        let mut guard = self.job.lock().unwrap();
        let Some(job) = guard.as_mut() else { return };
        if let Some(d) = job.doubts.get_mut(doubt) {
            d.decision = Some(decision);
            if job.current_doubt().is_none() {
                self.review_done.notify_one();
            }
        }
    }

    pub fn decide_with(&self, doubt: usize, track: YtTrack) {
        {
            let mut guard = self.job.lock().unwrap();
            let Some(job) = guard.as_mut().filter(|j| j.phase == Phase::Review) else { return };
            let Some(d) = job.doubts.get_mut(doubt) else { return };
            let k = match d.candidates.iter().position(|c| c.id == track.id) {
                Some(k) => k,
                None => {
                    d.videos.push(track.album.is_none());
                    d.candidates.push(track);
                    d.candidates.len() - 1
                }
            };
            d.loading = false;
            d.decision = Some(Decision::Pick(k));
            if job.current_doubt().is_none() {
                self.review_done.notify_one();
            }
        }
        self.wake();
    }

    pub fn edit_match(self: &Arc<Self>, rt: &crate::backend::Backend, index: usize) -> Option<usize> {
        let track = {
            let mut guard = self.job.lock().unwrap();
            let job = guard.as_mut()?;
            if let Some(k) = job.doubts.iter().position(|d| d.index == index) {
                return Some(k);
            }
            job.doubts.push(Doubt { index, candidates: Vec::new(), videos: Vec::new(), decision: None, loading: true });
            job.tracks.get(index)?.clone()
        };
        let (this, api) = (self.clone(), rt.api());
        rt.spawn(async move {
            let found = candidates(&api, &track).await.unwrap_or_default();
            if let Some(job) = this.job.lock().unwrap().as_mut()
                && let Some(d) = job.doubts.iter_mut().find(|d| d.index == index)
            {
                (d.candidates, d.videos) = found.into_iter().unzip();
                d.loading = false;
            }
            this.wake();
        });
        Some(self.job.lock().unwrap().as_ref()?.doubts.len() - 1)
    }

    pub fn finish_review(&self) {
        let mut guard = self.job.lock().unwrap();
        let Some(job) = guard.as_mut() else { return };
        for d in &mut job.doubts {
            d.decision.get_or_insert(Decision::Keep);
        }
        self.review_done.notify_one();
    }
}
