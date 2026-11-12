use super::{AudioStatus, TrackDecoder};
use crate::stream::Shared;
use crossbeam_channel::Sender;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
pub enum AudioCmd {
    Start { generation: u64, decoder: TrackDecoder, shared: Arc<Shared>, at: Duration },
    Replace { generation: u64, seek: u64, decoder: TrackDecoder, at: Duration, seekable: bool },
    Pause,
    Resume,
    Stop,
    Handoff,
    Seek(Duration),
    SeekLive { pos: Duration, at: std::time::Instant },
    Volume(f32),
    DeviceLost,
    SetDevice(Option<String>),
}

pub enum AudioEvent {
    Finished { generation: u64 },
    Ending { generation: u64 },
    Interrupted { generation: u64, at: Duration, reason: String },
    Error(String),
}

#[derive(Clone)]
pub struct AudioHandle {
    pub(super) tx: Sender<AudioCmd>,
    pub status: Arc<AudioStatus>,
}

impl AudioHandle {
    pub fn send(&self, cmd: AudioCmd) {
        let _ = self.tx.send(cmd);
    }

    pub fn volume(&self) -> f32 {
        f32::from_bits(self.status.volume.load(Ordering::Relaxed))
    }

    pub fn set_normalize(&self, on: bool) {
        self.status.normalize.store(on, Ordering::Relaxed);
        self.send(AudioCmd::Volume(self.volume()));
    }

    pub fn set_log_volume(&self, on: bool) {
        if self.status.log_volume.swap(on, Ordering::Relaxed) != on {
            let v = self.volume();
            self.status.volume.store(if on { v.cbrt() } else { v.powi(3) }.to_bits(), Ordering::Relaxed);
        }
        self.send(AudioCmd::Volume(self.volume()));
    }

    pub fn set_spatial(&self, on: bool) {
        self.status.spatial.store(on, Ordering::Relaxed);
    }

    pub fn set_eq(&self, tuning: crate::eq::Tuning) {
        if let Ok(mut t) = self.status.eq.lock() {
            *t = tuning;
        }
    }

    pub fn set_crossfade(&self, secs: u32) {
        self.status.crossfade_ms.store(secs * 1000, Ordering::Relaxed);
    }

    pub fn set_automix(&self, on: bool) {
        self.status.automix.store(on, Ordering::Relaxed);
    }

    pub fn set_volume(&self, v: f32) {
        self.status.volume.store(v.to_bits(), Ordering::Relaxed);
        self.send(AudioCmd::Volume(v));
    }
}
