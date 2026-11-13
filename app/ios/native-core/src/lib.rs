






uniffi::setup_scaffolding!();

pub mod align;
pub mod analyzer;
pub mod decode;
pub mod diagnostics;
pub mod eq;
pub mod metadata;
pub mod mixer;
pub mod playback_regions;
pub mod sound;
pub mod spatial;
pub mod time_stretch;
pub mod transition_filter;

mod api;
mod callback;
mod engine;
mod loudness;
mod output;
mod pace;
mod records;
mod render;

#[cfg(test)]
mod tests;

pub use api::{
    align_versions, configure_analyzer, constrain_automix_plan, constrain_automix_plan_for_sources,
    core_version, detect_playback_regions, last_automix_analysis_sources, next_energy_dip,
    rank_automix_candidates, read_track_metadata, read_track_metadata_summary,
    seed_automix_analysis, set_beat_accelerator, write_track_tags,
};
pub use analyzer::{BeatAccelerator, BeatLogits};
pub use callback::EngineCallback;
pub use engine::PlayerEngine;
pub use playback_regions::{PlaybackInterval, PlaybackRegions};
pub use records::{
    AlignmentSegmentRec, AudioSourceFormatRec, AutomixAnalysisOverlayRec, AutomixTier,
    DecodedRegion, EngineError, LoadRequest, MelSpectrogramResult, NerdStatsRec, OutputDeviceRec,
    OutputHealthRec, OutputPcmMode, PlaybackState, QueuedTrackRec, SourceErrorRec, TrackEndReason,
    TrackInfoRec, TrackMetadata, TransitionPlanRec, TransitionStyleRec, VerifiedAudioSourceRec,
    VersionAlignmentRec,
};
pub use sound::{ClarityPreset, ClarityTuning, LoudnessMeasurement, LoudnessMode, SoundMode};

















mod qos {






    #[cfg(target_vendor = "apple")]
    pub fn raise_current_thread() {



        unsafe {
            libc::pthread_set_qos_class_self_np(libc::qos_class_t::QOS_CLASS_USER_INTERACTIVE, 0);
        }
    }



    #[cfg(not(target_vendor = "apple"))]
    pub fn raise_current_thread() {}
}
