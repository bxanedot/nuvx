

use crate::mixer::{TransitionPlan, TransitionStyle};

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaybackState {
    Stopped,
    Buffering,
    Playing,
    Paused,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrackEndReason {

    Natural,

    Skipped,
    Error,
}

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransitionStyleRec {
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

impl From<TransitionStyleRec> for TransitionStyle {
    fn from(value: TransitionStyleRec) -> Self {
        match value {
            TransitionStyleRec::EqualPower => TransitionStyle::EqualPower,
            TransitionStyleRec::DjFilter => TransitionStyle::DjFilter,
            TransitionStyleRec::DjBlend => TransitionStyle::DjBlend,
            TransitionStyleRec::Gapless => TransitionStyle::Gapless,
            TransitionStyleRec::EchoOut => TransitionStyle::EchoOut,
            TransitionStyleRec::LoopRoll => TransitionStyle::LoopRoll,
            TransitionStyleRec::ReverbWash => TransitionStyle::ReverbWash,
            TransitionStyleRec::DropCut => TransitionStyle::DropCut,
            TransitionStyleRec::Texture => TransitionStyle::Texture,
            TransitionStyleRec::Brake => TransitionStyle::Brake,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct TransitionPlanRec {
    pub style: TransitionStyleRec,
    pub bass_swap: bool,
    pub bass_swap_fraction: f64,
    pub filter_sweep: f64,
    pub vocal_overlap: f64,

    pub fade_seconds: f64,






    #[uniffi(default = 0.0)]
    pub outgoing_hold_seconds: f64,



    #[uniffi(default = 0.0)]
    pub bridge_start_seconds: f64,
    #[uniffi(default = 0.0)]
    pub bridge_seconds: f64,
    #[uniffi(default = 0)]
    pub bridge_repeats: u32,

    #[uniffi(default = 0.0)]
    pub effect_seconds: f64,

    #[uniffi(default = 0.0)]
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

    #[uniffi(default = 0.0)]
    pub beat_seconds: f64,


    #[uniffi(default = "")]
    pub reason: String,
}

impl From<TransitionPlanRec> for TransitionPlan {
    fn from(rec: TransitionPlanRec) -> Self {
        TransitionPlan {
            style: rec.style.into(),
            bass_swap: rec.bass_swap,
            bass_swap_fraction: rec.bass_swap_fraction,
            filter_sweep: rec.filter_sweep,
            vocal_overlap: rec.vocal_overlap,
            fade_seconds: rec.fade_seconds,
            outgoing_hold_seconds: rec.outgoing_hold_seconds,
            bridge_start_seconds: rec.bridge_start_seconds,
            bridge_seconds: rec.bridge_seconds,
            bridge_repeats: rec.bridge_repeats,
            effect_seconds: rec.effect_seconds,
            effect_start_seconds: rec.effect_start_seconds,
            transition_end_seconds: rec.transition_end_seconds,
            cue_seconds: rec.cue_seconds,
            playback_rate: rec.playback_rate,
            bed_fraction: rec.bed_fraction,
            bed_gain_db: rec.bed_gain_db,
            dip_depth: rec.dip_depth,
            dip_width: rec.dip_width,
            post_glide_seconds: rec.post_glide_seconds,
            outgoing_duration_seconds: rec.outgoing_duration_seconds,
            beat_seconds: rec.beat_seconds,
            reason: rec.reason,
        }
    }
}

impl From<TransitionPlan> for TransitionPlanRec {
    fn from(plan: TransitionPlan) -> Self {
        Self {
            style: match plan.style {
                TransitionStyle::Gapless => TransitionStyleRec::Gapless,
                TransitionStyle::EqualPower => TransitionStyleRec::EqualPower,
                TransitionStyle::DjFilter => TransitionStyleRec::DjFilter,
                TransitionStyle::DjBlend => TransitionStyleRec::DjBlend,
                TransitionStyle::EchoOut => TransitionStyleRec::EchoOut,
                TransitionStyle::LoopRoll => TransitionStyleRec::LoopRoll,
                TransitionStyle::ReverbWash => TransitionStyleRec::ReverbWash,
                TransitionStyle::DropCut => TransitionStyleRec::DropCut,
                TransitionStyle::Texture => TransitionStyleRec::Texture,
                TransitionStyle::Brake => TransitionStyleRec::Brake,
            },
            bass_swap: plan.bass_swap,
            bass_swap_fraction: plan.bass_swap_fraction,
            filter_sweep: plan.filter_sweep,
            vocal_overlap: plan.vocal_overlap,
            fade_seconds: plan.fade_seconds,
            outgoing_hold_seconds: plan.outgoing_hold_seconds,
            bridge_start_seconds: plan.bridge_start_seconds,
            bridge_seconds: plan.bridge_seconds,
            bridge_repeats: plan.bridge_repeats,
            effect_seconds: plan.effect_seconds,
            effect_start_seconds: plan.effect_start_seconds,
            transition_end_seconds: plan.transition_end_seconds,
            cue_seconds: plan.cue_seconds,
            playback_rate: plan.playback_rate,
            bed_fraction: plan.bed_fraction,
            bed_gain_db: plan.bed_gain_db,
            dip_depth: plan.dip_depth,
            dip_width: plan.dip_width,
            post_glide_seconds: plan.post_glide_seconds,
            outgoing_duration_seconds: plan.outgoing_duration_seconds,
            beat_seconds: plan.beat_seconds,
            reason: plan.reason,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct LoadRequest {


    pub source: String,
    pub title: String,
    pub artist: String,

    pub start_seconds: f64,
    pub plan: Option<TransitionPlanRec>,



    pub headers: Option<std::collections::HashMap<String, String>>,

    pub claimed_kbps: Option<u32>,



    pub loudness_db: Option<f64>,









    pub duration_seconds: Option<f64>,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct TrackInfoRec {
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




#[derive(uniffi::Record, Debug, Clone)]
pub struct QueuedTrackRec {
    pub title: String,
    pub artist: String,
    pub source: String,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct NerdStatsRec {
    pub codec: String,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub kbps: u32,


    pub loudness_gain_db: Option<f32>,



    pub transition_match_db: Option<f32>,




    pub swap_correlation: Option<f64>,
    pub active_stages: Vec<String>,
    pub converter_delay_frames: f64,
    pub protection_reduction_db: f32,
    pub protection_interventions: u64,
    pub build_revision: String,
    pub decoder_implementation: String,
    pub decoded_layout: String,
    pub queued_target_ms: u64,
    pub loudness_origin: String,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct DecodedRegion {

    pub samples: Vec<f32>,
    pub sample_rate: u32,
    pub start_seconds: f64,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct DecodedPcm {

    pub pcm: Vec<u8>,
    pub sample_rate: u32,
    pub start_seconds: f64,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct TrackMetadata {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_seconds: f64,

    pub artwork: Vec<u8>,



    pub track_number: u32,
}



#[derive(uniffi::Record, Debug, Clone)]
pub struct AudioSourceFormatRec {
    pub codec: String,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub lossless_pcm: bool,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct SourceErrorRec {
    pub source: String,
    pub message: String,
}


#[derive(uniffi::Record, Debug, Clone)]
pub struct VerifiedAudioSourceRec {
    pub format: AudioSourceFormatRec,
    pub estimated_kbps: Option<u32>,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct MelSpectrogramResult {
    pub frames: u64,
    pub mels: u64,

    pub values: Vec<f32>,
}

#[derive(uniffi::Error, Debug, Clone, PartialEq, Eq)]
pub enum EngineError {
    NoOutputDevice,
    StreamInit(String),
    LoadFailed(String),
    NotStarted,
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::NoOutputDevice => write!(f, "no audio output device"),
            EngineError::StreamInit(e) => write!(f, "stream init failed: {e}"),
            EngineError::LoadFailed(e) => write!(f, "load failed: {e}"),
            EngineError::NotStarted => write!(f, "engine not started"),
        }
    }
}

impl std::error::Error for EngineError {}

















#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum OutputPcmMode {
    Pcm16,
    Float32,
}

impl OutputPcmMode {



    pub(super) fn parse(mode: &str) -> Self {
        match mode {
            "FLOAT_32" | "PCM_24" => OutputPcmMode::Float32,
            _ => OutputPcmMode::Pcm16,
        }
    }

    pub(super) fn label(self) -> &'static str {
        match self {
            OutputPcmMode::Pcm16 => "PCM_16",
            OutputPcmMode::Float32 => "FLOAT_32",
        }
    }
}






#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AutomixTier {
    Efficient,
    Balanced,
    Performance,
}

impl AutomixTier {
    pub(crate) fn inference_threads(self) -> usize {





        match self { Self::Efficient => 1, Self::Balanced => 1, Self::Performance => 4 }
    }




    pub(crate) fn analysis_duty(self) -> Option<f64> {
        match self { Self::Efficient => Some(0.5), Self::Balanced => Some(0.6), Self::Performance => None }
    }
    pub(super) fn parse(mode: &str) -> Self {
        match mode {
            "EFFICIENT" => AutomixTier::Efficient,
            "PERFORMANCE" => AutomixTier::Performance,
            _ => AutomixTier::Balanced,
        }
    }


    pub(super) fn runs_vocal_model(self) -> bool {
        self != AutomixTier::Efficient
    }



    pub(super) fn name(self) -> &'static str {
        match self {
            AutomixTier::Efficient => "EFFICIENT",
            AutomixTier::Balanced => "BALANCED",
            AutomixTier::Performance => "PERFORMANCE",
        }
    }
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct OutputDeviceRec {
    pub name: String,
    pub sample_rate: u32,
    pub channels: u32,
    pub started: bool,


    pub sample_format: String,


    pub bit_perfect_active: bool,

    pub bit_perfect_reason: String,
}




#[derive(uniffi::Record, Debug, Clone)]
pub struct OutputHealthRec {
    pub buffered_frames: u64,
    pub callback_underruns: u64,
    pub output_rebuilds: u64,
    pub output_xruns: u64,

    pub output_peak: f32,
}


#[derive(uniffi::Record, Debug, Clone, Default)]
pub struct AutomixAnalysisOverlayRec {
    pub bpm: Option<f64>,
    pub beat_confidence: Option<f64>,
    pub beat_interval: Option<f64>,
    pub beats: Option<Vec<f64>>,
    pub downbeats: Option<Vec<f64>>,
    pub key: Option<String>,
    pub key_confidence: Option<f64>,
    pub phrase_boundaries: Option<Vec<f64>>,
    pub vocal_probability: Option<f64>,
    pub content_end: Option<f64>,
    pub audible_start: Option<f64>,
    pub outro_start: Option<f64>,
    pub mix_in_time: Option<f64>,

    pub source: String,
}



#[derive(uniffi::Record, Debug, Clone)]
pub struct AlignmentSegmentRec {
    pub song_start: f64,
    pub song_end: f64,
    pub offset: f64,
}



#[derive(uniffi::Record, Debug, Clone)]
pub struct VersionAlignmentRec {
    pub segments: Vec<AlignmentSegmentRec>,
    pub similarity: f64,
    pub baseline: f64,
    pub minimum_window_similarity: f64,
    pub coverage: f64,
    pub confident: bool,
}
