use super::super::{AudioStatus, Arc, Duration};

pub struct Eq<S> {
    inner: S,
    status: Arc<AudioStatus>,
    eq: crate::eq::Processor,
    tuning: crate::eq::Tuning,
    frame: Vec<f32>,
    out: usize,
}

impl<S: rodio::Source> Eq<S> {
    pub(in crate::audio) fn new(inner: S, status: Arc<AudioStatus>) -> Self {
        let tuning = status.eq.lock().map(|t| *t).unwrap_or(crate::eq::Tuning::OFF);
        let channels = inner.channels().get() as usize;
        let eq = crate::eq::Processor::new(inner.sample_rate().get(), channels, tuning);
        Eq { inner, status, eq, tuning, frame: Vec::with_capacity(channels), out: 0 }
    }
}

impl<S: rodio::Source> Iterator for Eq<S> {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        if self.out == self.frame.len() {
            if let Ok(t) = self.status.eq.try_lock() {
                self.tuning = *t;
            }
            self.frame.clear();
            self.out = 0;
            let channels = self.inner.channels().get() as usize;
            self.frame.extend(self.inner.by_ref().take(channels));
            if self.frame.is_empty() {
                return None;
            }
            if self.frame.len() == channels && !self.eq.idle(&self.tuning) {
                self.eq.frame(&mut self.frame, &self.tuning);
            }
        }
        self.out += 1;
        Some(self.frame[self.out - 1])
    }
}

impl<S: rodio::Source> rodio::Source for Eq<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len().map(|n| n + self.frame.len() - self.out)
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.channels()
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.sample_rate()
    }
    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.inner.try_seek(pos)?;
        self.frame.clear();
        self.out = 0;
        self.eq.reset();
        Ok(())
    }
}
