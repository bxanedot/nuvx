use super::{AudioCmd, AudioStatus};
use crossbeam_channel::Sender;
use rodio::cpal::traits::{DeviceTrait, HostTrait};
use rodio::mixer::{Mixer, MixerSource};
use rodio::{DeviceSinkBuilder, MixerDeviceSink};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
pub(super) struct Output {
    pub(super) mixer: Mixer,
    pub(super) slot: Arc<std::sync::Mutex<Option<MixerSource>>>,
    pub(super) sink: Option<MixerDeviceSink>,
    pub(super) device: Option<String>,
    pub(super) preferred: Option<String>,
    /// Each sink has a generation; retired callbacks cannot disrupt its replacement.
    pub(super) generation: Arc<AtomicU64>,
    /// Highest generation for which the error callback has notified the engine.
    pub(super) lost: Arc<AtomicU64>,
    pub(super) checked: Instant,
}

impl Output {
    pub(super) fn new(tx: Sender<AudioCmd>, status: &AudioStatus, preferred: Option<String>) -> Self {
        let config = target(preferred.as_deref()).and_then(|d| d.default_output_config().ok());
        let channels = config.as_ref().and_then(|c| std::num::NonZero::new(c.channels())).unwrap_or(std::num::NonZero::new(2).unwrap());
        let rate = config.as_ref().and_then(|c| std::num::NonZero::new(c.sample_rate())).unwrap_or(std::num::NonZero::new(48_000).unwrap());
        let (mixer, source) = rodio::mixer::mixer(channels, rate);
        let generation = Arc::new(AtomicU64::new(0));
        let lost = Arc::new(AtomicU64::new(0));
        let mut out = Self { mixer, slot: Arc::new(std::sync::Mutex::new(Some(source))), sink: None, device: None, preferred, generation, lost, checked: Instant::now() };
        out.open(tx, status);
        out
    }

    pub(super) fn open(&mut self, tx: Sender<AudioCmd>, status: &AudioStatus) -> bool {
        // Retire the old callback before the old sink is dropped.
        let gen = self.generation.fetch_add(1, Ordering::AcqRel) + 1;
        self.sink = None;
        self.device = None;
        self.checked = Instant::now();
        let Some(device) = target(self.preferred.as_deref()) else {
            return false;
        };
        let id = device.id().map(|i| i.to_string()).ok();
        let lost = self.lost.clone();
        let active = self.generation.clone();
        let on_error = move |e: rodio::CpalError| {
            if notify_device_error(gen, &active, &lost, &tx) {
                log::warn!("audio: output device error: {e}");
            }
        };
        let sink = match DeviceSinkBuilder::from_device(device).and_then(|b| b.with_error_callback(on_error).open_sink_or_fallback()) {
            Ok(s) => s,
            Err(e) => {
                log::warn!("audio: couldn't open the output device: {e}");
                return false;
            }
        };
        let waited = Instant::now();
        let source = loop {
            if let Some(source) = self.slot.lock().unwrap().take() {
                break source;
            }
            if waited.elapsed() > Duration::from_secs(1) {
                log::warn!("audio: mixer still held by the old device");
                return false;
            }
            std::thread::sleep(Duration::from_millis(5));
        };
        let mut sink = sink;
        sink.log_on_drop(false);
        let channels = rodio::Source::channels(&source).get();
        sink.mixer().add(Bridge { inner: Some(source), slot: self.slot.clone(), channels, n: 0 });
        let rate = sink.config().sample_rate().get().max(1);
        let buffer_ms = match sink.config().buffer_size() {
            rodio::cpal::BufferSize::Fixed(n) => *n as u64 * 1000 / rate as u64,
            rodio::cpal::BufferSize::Default => 0,
        };
        status.latency_ms.store((buffer_ms + 10) as u32, Ordering::Relaxed);
        log::info!("audio: output device {:?} at {} Hz", id, rate);
        self.sink = Some(sink);
        self.device = id;
        true
    }

    pub(super) fn default_changed(&mut self) -> bool {
        self.checked = Instant::now();
        let want = target(self.preferred.as_deref()).and_then(|d| d.id().ok()).map(|i| i.to_string());
        want.is_some() && want != self.device
    }
}

fn target(preferred: Option<&str>) -> Option<rodio::cpal::Device> {
    let host = rodio::cpal::default_host();
    if let Some(want) = preferred
        && let Some(d) = host.output_devices().ok().and_then(|mut all| all.find(|d| d.id().is_ok_and(|i| i.to_string() == want)))
    {
        return Some(d);
    }
    host.default_output_device()
}

/// A retired sink must never change the state of a newer output connection.
fn notify_device_error(
    generation: u64,
    active: &AtomicU64,
    reported: &AtomicU64,
    tx: &Sender<AudioCmd>,
) -> bool {
    if active.load(Ordering::Acquire) != generation {
        return false;
    }
    if reported.fetch_max(generation, Ordering::AcqRel) >= generation {
        return false;
    }
    tx.send(AudioCmd::DeviceLost(generation)).is_ok()
}

#[derive(Clone, PartialEq)]
pub struct OutputDevice {
    pub id: String,
    pub name: String,
}

pub fn output_devices() -> Vec<OutputDevice> {
    let Ok(all) = rodio::cpal::default_host().output_devices() else { return Vec::new() };
    let mut out: Vec<OutputDevice> = all
        .filter_map(|d| {
            let id = d.id().ok()?.to_string();
            let desc = d.description().ok()?;
            let name = desc.extended().next().map(str::to_owned).unwrap_or_else(|| desc.name().to_owned());
            Some(OutputDevice { id, name })
        })
        .collect();
    out.sort_by_key(|a| a.name.to_lowercase());
    out
}

struct Bridge {
    inner: Option<MixerSource>,
    slot: Arc<std::sync::Mutex<Option<MixerSource>>>,
    channels: u16,
    n: u64,
}

impl Iterator for Bridge {
    type Item = rodio::Sample;
    fn next(&mut self) -> Option<Self::Item> {
        self.n += 1;
        Some(self.inner.as_mut().and_then(Iterator::next).unwrap_or(0.0))
    }
}

impl rodio::Source for Bridge {
    fn current_span_len(&self) -> Option<usize> {
        None
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.as_ref().map_or(std::num::NonZero::new(self.channels.max(1)).unwrap(), |s| s.channels())
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.as_ref().map_or(std::num::NonZero::new(48_000).unwrap(), |s| s.sample_rate())
    }
    fn total_duration(&self) -> Option<Duration> {
        None
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        if let Some(mut source) = self.inner.take() {
            while !self.n.is_multiple_of(self.channels.max(1) as u64) {
                source.next();
                self.n += 1;
            }
            *self.slot.lock().unwrap() = Some(source);
        }
    }
}

#[cfg(test)]
mod generation_tests {
    use super::*;
    use crossbeam_channel::unbounded;

    #[test]
    fn retired_device_callback_is_ignored_after_switch() {
        let active = AtomicU64::new(1);
        let reported = AtomicU64::new(0);
        let (tx, rx) = unbounded();
        assert!(notify_device_error(1, &active, &reported, &tx));
        assert!(!notify_device_error(1, &active, &reported, &tx));
        active.store(2, Ordering::Release);
        assert!(!notify_device_error(1, &active, &reported, &tx));
        assert!(notify_device_error(2, &active, &reported, &tx));
        assert!(matches!(rx.try_recv(), Ok(AudioCmd::DeviceLost(1))));
        assert!(matches!(rx.try_recv(), Ok(AudioCmd::DeviceLost(2))));
        assert!(rx.try_recv().is_err());
    }

    #[test]
    fn rapid_output_switches_keep_only_latest_generation_eligible() {
        let active = AtomicU64::new(1);
        let reported = AtomicU64::new(0);
        let (tx, rx) = unbounded();
        active.store(2, Ordering::Release);
        active.store(3, Ordering::Release);
        assert!(!notify_device_error(1, &active, &reported, &tx));
        assert!(!notify_device_error(2, &active, &reported, &tx));
        assert!(notify_device_error(3, &active, &reported, &tx));
        assert!(!notify_device_error(3, &active, &reported, &tx));
        assert_eq!(reported.load(Ordering::Acquire), 3);
        assert!(matches!(rx.try_recv(), Ok(AudioCmd::DeviceLost(3))));
        assert!(rx.try_recv().is_err());
    }
}

pub(super) const DEVICE_CHECK: Duration = Duration::from_secs(1);
