


use super::fx::{self};
use crate::sound::{ClarityTuning, LoudnessMode, SoundMode};
use crossbeam_channel::Sender;
use rtrb::Producer;
use std::sync::atomic::AtomicU64;
use std::sync::Arc;


pub trait EngineEvents: Send + Sync {
    fn state_changed(&self, state: crate::PlaybackState);
    fn track_ended(&self, reason: crate::TrackEndReason, source: String);
    fn error(&self, message: String);
    fn source_error(&self, _source: String, message: String) {
        self.error(message);
    }


    fn handoff(&self, info: TrackInfo);
    fn duration_changed(&self, seconds: f64);
}

#[derive(Debug, Clone)]
pub struct TrackInfo {
    pub title: String,
    pub artist: String,
    pub source: String,
    pub duration_seconds: f64,
    pub codec: String,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub kbps: u32,
}


#[derive(Debug, Clone)]
pub struct TrackSource {
    pub source: String,
    pub title: String,
    pub artist: String,

    pub start_seconds: f64,
    pub plan: TransitionPlan,

    pub headers: std::collections::HashMap<String, String>,

    pub claimed_kbps: u32,





    pub loudness_db: Option<f64>,











    pub duration_seconds: f64,
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransitionStyle {
    EqualPower,
    DjFilter,
    DjBlend,
    Gapless,


    EchoOut,


    LoopRoll,


    ReverbWash,

    DropCut,

    Texture,

    Brake,
}

impl TransitionStyle {



    pub const fn telemetry_name(self) -> &'static str {
        match self {
            Self::EqualPower => "equal_power",
            Self::DjFilter => "dj_filter",
            Self::DjBlend => "dj_blend",
            Self::Gapless => "gapless",
            Self::EchoOut => "echo_out",
            Self::LoopRoll => "loop_roll",
            Self::ReverbWash => "reverb_wash",
            Self::DropCut => "drop_cut",
            Self::Texture => "texture",
            Self::Brake => "brake",
        }
    }



    pub fn is_effect(self) -> bool {
        fx::Technique::of(self).is_some()
    }
}




#[derive(Debug, Clone)]
pub struct TransitionPlan {
    pub style: TransitionStyle,
    pub bass_swap: bool,
    pub bass_swap_fraction: f64,
    pub filter_sweep: f64,
    pub vocal_overlap: f64,

    pub fade_seconds: f64,






    pub outgoing_hold_seconds: f64,



    pub bridge_start_seconds: f64,
    pub bridge_seconds: f64,
    pub bridge_repeats: u32,



    pub effect_seconds: f64,


    pub effect_start_seconds: f64,









    pub transition_end_seconds: f64,
    pub cue_seconds: f64,


    pub playback_rate: f64,


    pub bed_fraction: f64,

    pub bed_gain_db: f64,


    pub dip_depth: f64,

    pub dip_width: f64,


    pub post_glide_seconds: f64,









    pub outgoing_duration_seconds: f64,


    pub beat_seconds: f64,

    pub reason: String,
}

impl Default for TransitionPlan {
    fn default() -> Self {
        Self {
            style: TransitionStyle::EqualPower,
            bass_swap: false,
            bass_swap_fraction: 0.7,
            filter_sweep: 0.0,
            vocal_overlap: 0.0,
            fade_seconds: 0.0,
            outgoing_hold_seconds: 0.0,
            bridge_start_seconds: 0.0,
            bridge_seconds: 0.0,
            bridge_repeats: 0,
            effect_seconds: 0.0,
            effect_start_seconds: 0.0,
            transition_end_seconds: 0.0,
            cue_seconds: 0.0,
            playback_rate: 1.0,
            bed_fraction: 0.0,
            bed_gain_db: 0.0,
            dip_depth: 0.0,
            dip_width: 0.0,
            post_glide_seconds: 0.0,
            outgoing_duration_seconds: 0.0,
            beat_seconds: 0.0,
            reason: String::new(),
        }
    }
}



#[derive(Clone, Debug)]
pub struct QueuedTrack {
    pub title: String,
    pub artist: String,
    pub source: String,
}

pub enum Command {

    ForSource {
        source: String,
        command: Box<Command>,
    },
    Load {
        request: TrackSource,
        reply: Sender<Result<TrackInfo, String>>,
    },








    SwapSource {
        request: TrackSource,
        crossfade_seconds: f64,
        reply: Sender<Result<TrackInfo, String>>,
    },
    QueueNext {
        request: TrackSource,
    },




    PendingTrack {
        reply: Sender<Option<QueuedTrack>>,
    },






    SkipToPending {
        skip_mix_enabled: bool,
        reply: Sender<Result<TrackInfo, String>>,
    },
    Play,
    Pause,
    Stop,






    Seek {
        seconds: f64,
    },
    SetVolume(f32),
    SetSleepGain(f32),
    HoldTrackEnd(bool),
    SetPlaybackRegions {
        source: String,
        regions: crate::PlaybackRegions,
    },


    SetBitPerfect(bool),
    SetCrossfadeWindow(f64),
    CancelAutomixProcessing,
    SetSpatial(bool),
    SetHeadYaw(f32),



    SetVoiceFilter {
        incoming: bool,
        low_hz: f32,
        high_hz: f32,
    },
    OpenFilters,
    SetPlaybackSpeed(f32),
    SetSkipSilence(bool),


    SetEqTuning {
        enabled: bool,
        gains_db: [f32; crate::eq::EqLayout::SLOTS],
        qs: [f32; crate::eq::EqLayout::SLOTS],
        preamp_db: f32,
        balance: f32,
    },



    SetLoudnessEnabled(bool),
    SetSoundMode(SoundMode),
    ObserveUnderruns(Arc<AtomicU64>),
    SetDecodeChunk(usize),
    SetPreferAppleAac(bool),
    StartDiagnosticCapture {
        directory: String,
        seconds: f64,
    },
    FinishDiagnosticCapture {
        reply: Option<Sender<Result<(), String>>>,
    },
    SetClarityTuning(ClarityTuning),
    SetLoudnessMode(LoudnessMode),
    SetLoudnessMeasurement(crate::LoudnessMeasurement),

    SetOutputFormat {
        rate: u32,
        producer: Producer<f32>,
        generation: u64,
        acknowledged: Option<Sender<bool>>,
    },
    Shutdown,
}
