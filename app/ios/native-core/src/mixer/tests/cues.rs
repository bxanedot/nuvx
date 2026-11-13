

use super::*;

#[test]
fn cue_in_the_outro_starts_at_zero() {
    assert_eq!(clamp_start_seconds(0.0, 180.0), 0.0);
    assert_eq!(clamp_start_seconds(12.0, 180.0), 12.0);
    assert_eq!(clamp_start_seconds(176.0, 180.0), 0.0);
    assert_eq!(clamp_start_seconds(175.0, 180.0), 0.0);
    assert_eq!(clamp_start_seconds(174.9, 180.0), 174.9);
    assert_eq!(clamp_start_seconds(8.0, 0.0), 8.0);
}







#[test]
fn a_planned_mix_in_cannot_reach_past_the_depth_bound() {
    let planned = TransitionPlan {
        cue_seconds: 45.0,
        ..TransitionPlan::default()
    };

    assert_eq!(bound_mix_in_depth(45.0, &planned, 210.0), 0.0);
    assert_eq!(bound_mix_in_depth(48.0, &planned, 210.0), 0.0);

    let legal = TransitionPlan {
        cue_seconds: 6.0,
        ..TransitionPlan::default()
    };
    assert_eq!(bound_mix_in_depth(6.0, &legal, 210.0), 6.0);
    assert_eq!(bound_mix_in_depth(7.5, &legal, 210.0), 7.5);



    let eight = TransitionPlan {
        cue_seconds: 8.0,
        ..TransitionPlan::default()
    };
    assert_eq!(bound_mix_in_depth(8.0, &eight, 240.0), 8.0);
    assert_eq!(bound_mix_in_depth(8.0, &eight, 60.0), 0.0);
    assert_eq!(bound_mix_in_depth(3.5, &eight, 60.0), 3.5);
}







#[test]
fn a_resume_seek_is_not_treated_as_a_mix_in() {
    let no_plan = TransitionPlan::default();
    assert_eq!(no_plan.cue_seconds, 0.0);
    assert_eq!(bound_mix_in_depth(180.0, &no_plan, 240.0), 180.0);
    assert_eq!(bound_mix_in_depth(45.0, &no_plan, 210.0), 45.0);


    assert_eq!(bound_mix_in_depth(45.0, &planned_at(45.0), 0.0), 45.0);
}










#[test]
fn a_planned_transition_overlaps_rather_than_waits_for_the_end() {
    let dir = std::env::temp_dir().join("nuvx-mixer-overlap");
    std::fs::create_dir_all(&dir).unwrap();
    let a = dir.join("a.wav");
    let b = dir.join("b.wav");

    test_wav(&a, 8.0, 440.0);
    test_wav(&b, 8.0, 660.0);

    let events = std::sync::Arc::new(RecordedEvents::default());
    let (tx, rx) = crossbeam_channel::unbounded::<Command>();
    let (consumer_side, mut consumer) = rtrb::RingBuffer::<f32>::new(44_100 * 2 * 2);
    let buffered = std::sync::Arc::new(AtomicU64::new(0));
    let position = std::sync::Arc::new(AtomicU64::new(0));
    let duration = std::sync::Arc::new(AtomicU64::new(0));
    let shutdown = std::sync::Arc::new(AtomicBool::new(false));

    let handle = std::thread::spawn({
        let events = events.clone();
        let buffered = buffered.clone();
        let position = position.clone();
        let duration = duration.clone();
        let shutdown = shutdown.clone();
        move || {
            run_mixer(
                rx,
                consumer_side,
                buffered,
                position,
                duration,
                44_100,
                events,
                shutdown,
                std::sync::Arc::new(AtomicBool::new(false)),
                std::sync::Arc::new(AtomicBool::new(false)),
                std::sync::Arc::new(Mutex::new(NerdSnapshot::default())),
            )
        }
    });



    let fade = 2.0f64;
    tx.send(Command::Load {
        request: TrackSource {
            source: a.display().to_string(),
            title: "A".into(),
            artist: String::new(),
            start_seconds: 0.0,
            plan: TransitionPlan::default(),
            headers: std::collections::HashMap::new(),
            claimed_kbps: 0,
            loudness_db: None,
            duration_seconds: 0.0,
        },
        reply: crossbeam_channel::bounded(1).0,
    })
    .unwrap();
    tx.send(Command::QueueNext {
        request: TrackSource {
            source: b.display().to_string(),
            title: "B".into(),
            artist: String::new(),
            start_seconds: 0.0,
            plan: TransitionPlan {
                style: TransitionStyle::DjBlend,
                bass_swap: false,
                fade_seconds: fade,
                effect_seconds: 0.0,
                effect_start_seconds: 0.0,
                transition_end_seconds: 6.0,
                ..TransitionPlan::default()
            },
            headers: std::collections::HashMap::new(),
            claimed_kbps: 0,
            loudness_db: None,
            duration_seconds: 0.0,
        },
    })
    .unwrap();

    let at = drain_until_handoff(&mut consumer, &buffered, &position, &events, 20)
        .expect("no handoff fired");
    assert!(
        at <= 6.0 + 0.6,
        "the handoff fired at {at:.2}s, past the 6 s blend anchor"
    );
    assert!(
        at <= 8.0 - 0.5,
        "the handoff fired at {at:.2}s of an 8 s track — that is a cut at the end, not a mix"
    );

    shutdown.store(true, std::sync::atomic::Ordering::Relaxed);
    tx.send(Command::Shutdown).ok();
    let _ = handle.join();
}







#[test]
fn a_container_without_a_duration_falls_back_to_the_callers_figure() {
    assert_eq!(
        known_duration(Some(210.0), 209.0),
        210.0,
        "the container wins"
    );
    assert_eq!(
        known_duration(Some(0.0), 210.0),
        210.0,
        "a zero is not a duration"
    );
    assert_eq!(
        known_duration(None, 210.0),
        210.0,
        "the caller fills the gap"
    );
    assert_eq!(known_duration(None, 0.0), 0.0, "and unknown stays unknown");
    assert_eq!(known_duration(None, f64::NAN), 0.0);
    assert_eq!(known_duration(Some(f64::NAN), 210.0), 210.0);
}




#[test]
fn the_recording_duration_takes_precedence_over_its_mix_out_anchor() {
    let dir = std::env::temp_dir().join("nuvx-mixer-anchor-duration");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("duration.wav");
    test_wav(&path, 10.0, 440.0);
    let request = TrackSource {
        source: path.display().to_string(),
        title: "A".into(),
        artist: String::new(),
        start_seconds: 0.0,
        plan: TransitionPlan {
            transition_end_seconds: 8.0,
            outgoing_duration_seconds: 10.0,
            fade_seconds: 1.0,
            effect_seconds: 0.0,
            effect_start_seconds: 0.0,
            ..TransitionPlan::default()
        },
        headers: std::collections::HashMap::new(),
        claimed_kbps: 0,
        loudness_db: None,
        duration_seconds: 10.0,
    };
    let voice = Voice::open(&request, false, 0.0, 44_100, 1.0, false, false).unwrap();
    let state = MixerState {
        current: Some(voice),
        incoming: None,
        pending_next: None,
        warned_no_duration: false,
        transition: None,
        retiring: Vec::new(),
        render_scratch: Vec::new(),
        voice_output_scratch: Vec::new(),
        effect_dry_scratch: Vec::new(),
        retiring_scratch: Vec::new(),
        playing: true,
        bit_perfect: false,
        volume: 1.0,
        sleep_gain: 1.0,
        hold_end: false,
        crossfade_window_s: 4.0,
        spatial_enabled: false,
        head_yaw: 0.0,
        device_rate: 44_100,
        buffered_frames: Arc::new(AtomicU64::new(0)),
        position_ms: Arc::new(AtomicU64::new(0)),
        duration_ms: Arc::new(AtomicU64::new(0)),
        events: Arc::new(RecordedEvents::default()),
        state: crate::PlaybackState::Playing,
        flush_ring: Arc::new(AtomicBool::new(false)),
        bail_flush: Arc::new(AtomicBool::new(false)),
        playback_speed: 1.0,
        skip_silence: false,
        eq: EqualizerProcessor::new(44_100, 2),
        sound_mode: SoundMode::Transparent,
        clarity_tuning: ClarityTuning::default(),
        clarity: Clarity::new(44_100),
        protector: Protector::new(44_100),
        pipeline_tail_frames: 0,
        output_generation: 0,
        queue_target_ms: 250,
        underrun_counter: None,
        observed_underruns: 0,
        nerd: Arc::new(Mutex::new(NerdSnapshot::default())),
        last_diagnostic_signature: None,
        last_diagnostic_publish: None,
        loudness_enabled: false,
        loudness_mode: LoudnessMode::Off,
        swap_probe: SwapProbe::default(),
    };
    let current = state.current.as_ref().unwrap();
    assert_eq!(state.outgoing_end_s(&request.plan, current), 8.0);
    let late_anchor = TransitionPlan {
        transition_end_seconds: 12.0,
        ..request.plan.clone()
    };
    assert_eq!(state.outgoing_end_s(&late_anchor, current), 10.0);
    assert_eq!(
        state.effective_fade_seconds(current.known_duration.unwrap()),
        10.0 / 3.0
    );
}











#[test]
fn the_blend_ends_at_the_plans_anchor_not_the_file_end() {
    let dir = std::env::temp_dir().join("nuvx-mixer-anchor");
    std::fs::create_dir_all(&dir).unwrap();
    let a = dir.join("out.wav");
    let b = dir.join("in.wav");
    test_wav(&a, 10.0, 440.0);
    test_wav(&b, 10.0, 660.0);

    let events = std::sync::Arc::new(RecordedEvents::default());
    let (tx, rx) = crossbeam_channel::unbounded::<Command>();

    let (consumer_side, mut consumer) = rtrb::RingBuffer::<f32>::new(44_100 / 4 * 2);
    let buffered = std::sync::Arc::new(AtomicU64::new(0));
    let position = std::sync::Arc::new(AtomicU64::new(0));
    let duration = std::sync::Arc::new(AtomicU64::new(0));
    let shutdown = std::sync::Arc::new(AtomicBool::new(false));
    let handle = std::thread::spawn({
        let events = events.clone();
        let buffered = buffered.clone();
        let position = position.clone();
        let duration = duration.clone();
        let shutdown = shutdown.clone();
        move || {
            run_mixer(
                rx,
                consumer_side,
                buffered,
                position,
                duration,
                44_100,
                events,
                shutdown,
                std::sync::Arc::new(AtomicBool::new(false)),
                std::sync::Arc::new(AtomicBool::new(false)),
                std::sync::Arc::new(Mutex::new(NerdSnapshot::default())),
            )
        }
    });

    let source = |path: &std::path::Path, title: &str, plan: TransitionPlan| TrackSource {
        source: path.display().to_string(),
        title: title.into(),
        artist: String::new(),
        start_seconds: 0.0,
        plan,
        headers: std::collections::HashMap::new(),
        claimed_kbps: 0,
        loudness_db: None,
        duration_seconds: 0.0,
    };

    tx.send(Command::SetCrossfadeWindow(1.0)).unwrap();
    tx.send(Command::Load {
        request: source(&a, "A", TransitionPlan::default()),
        reply: crossbeam_channel::bounded(1).0,
    })
    .unwrap();


    tx.send(Command::QueueNext {
        request: source(
            &b,
            "B",
            TransitionPlan {
                fade_seconds: 1.0,
                effect_seconds: 0.0,
                effect_start_seconds: 0.0,
                transition_end_seconds: 3.0,
                ..TransitionPlan::default()
            },
        ),
    })
    .unwrap();

    let mut drained = 0usize;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    while std::time::Instant::now() < deadline {
        while consumer.pop().is_ok() {
            drained += 1;
            let mut current = buffered.load(Ordering::Relaxed);
            while current > 0 {
                match buffered.compare_exchange_weak(
                    current,
                    current - 1,
                    Ordering::Relaxed,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => break,
                    Err(v) => current = v,
                }
            }
        }
        if !events.handoffs.lock().unwrap().is_empty() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(2));
    }

    assert!(
        !events.handoffs.lock().unwrap().is_empty(),
        "the blend must happen at all"
    );
    let started_at = drained as f64 / 2.0 / 44_100.0;
    assert!(
        started_at < 5.0,
        "the blend started {started_at:.2}s in, which is the file end — the plan's anchor was ignored"
    );
    assert!(
        started_at > 0.5,
        "the blend started {started_at:.2}s in, implausibly early"
    );

    shutdown.store(true, Ordering::Relaxed);
    let _ = handle.join();
}
