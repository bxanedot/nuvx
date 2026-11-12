use super::super::{AudioStatus, Ordering, Arc, Duration};

pub struct Spatial<S> {
    inner: S,
    status: Arc<AudioStatus>,
    pending: Option<f32>,
    delay: [Vec<f32>; 2],
    at: usize,
    lowpass: [f32; 2],
}

impl<S: rodio::Source> Spatial<S> {
    const WIDTH: f32 = 2.5;
    const OUTPUT: f32 = 0.82;
    const CROSSFEED: f32 = 0.2;
    const LOWPASS: f32 = 0.3;
    const DELAY_MS: u32 = 15;

    pub(in crate::audio) fn new(inner: S, status: Arc<AudioStatus>) -> Self {
        let n = (inner.sample_rate().get() * Self::DELAY_MS / 1000).max(1) as usize;
        Spatial { inner, status, pending: None, delay: [vec![0.0; n], vec![0.0; n]], at: 0, lowpass: [0.0; 2] }
    }

    fn widen(&mut self, l: f32, r: f32) -> (f32, f32) {
        let (mid, side) = ((l + r) * 0.5, (l - r) * 0.5 * Self::WIDTH);
        let i = self.at;
        self.lowpass[0] += Self::LOWPASS * (self.delay[1][i] - self.lowpass[0]);
        self.lowpass[1] += Self::LOWPASS * (self.delay[0][i] - self.lowpass[1]);
        self.delay[0][i] = l;
        self.delay[1][i] = r;
        self.at = (i + 1) % self.delay[0].len();
        ((mid + side + self.lowpass[0] * Self::CROSSFEED) * Self::OUTPUT, (mid - side + self.lowpass[1] * Self::CROSSFEED) * Self::OUTPUT)
    }
}

impl<S: rodio::Source> Iterator for Spatial<S> {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        if let Some(r) = self.pending.take() {
            return Some(r);
        }
        let l = self.inner.next()?;
        if self.inner.channels().get() != 2 || !self.status.spatial.load(Ordering::Relaxed) {
            return Some(l);
        }
        let Some(r) = self.inner.next() else { return Some(l) };
        let (l, r) = self.widen(l, r);
        self.pending = Some(r);
        Some(l)
    }
}

impl<S: rodio::Source> rodio::Source for Spatial<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len().map(|n| n + self.pending.is_some() as usize)
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
        self.pending = None;
        self.delay.iter_mut().for_each(|d| d.fill(0.0));
        self.lowpass = [0.0; 2];
        Ok(())
    }
}
