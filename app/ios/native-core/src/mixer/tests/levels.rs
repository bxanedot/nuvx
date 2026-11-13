

use super::*;

#[test]
fn transparent_honors_normalization_before_peak_protection() {
    let path = std::env::temp_dir().join(format!(
        "nuvx-transparent-headroom-{}.wav",
        std::process::id()
    ));
    let rate = 44_100u32;
    let pcm: Vec<f32> = (0..rate * 2)
        .flat_map(|i| {
            let x = (i as f64 * std::f64::consts::TAU * 60.0 / rate as f64).sin() as f32 * 1.23;
            [x, x]
        })
        .collect();
    crate::diagnostics::write_float_wav(&path, rate, &pcm).unwrap();
    let mut state = armed_test_state(&path, &path, Phase::Arming);
    let (mut producer, mut consumer) = rtrb::RingBuffer::new(44_100 * 2);
    super::super::commands::handle_command(
        &mut state,
        Command::SetLoudnessMode(LoudnessMode::Track),
        &mut producer,
    );
    let (reply, response) = crossbeam_channel::bounded(1);
    super::super::commands::handle_command(
        &mut state,
        Command::Load {
            request: TrackSource {
                source: path.display().to_string(),
                title: "Hot bass".into(),
                artist: String::new(),
                start_seconds: 0.0,
                plan: TransitionPlan::default(),
                headers: Default::default(),
                claimed_kbps: 0,
                loudness_db: Some(6.0),
                duration_seconds: 2.0,
            },
            reply,
        },
        &mut producer,
    );
    response.recv().unwrap().unwrap();
    let mut output = Vec::new();
    for _ in 0..90 {
        super::super::render::render_available(&mut state, &mut producer);
        while let Ok(x) = consumer.pop() {
            output.push(x);
        }
        state.buffered_frames.store(0, Ordering::Relaxed);
    }
    let stats = state.nerd.lock().unwrap();
    assert_eq!(
        stats.loudness_gain_db,
        Some(-6.0),
        "Transparent ignored the selected normalization mode"
    );
    assert_eq!(
        state.protector.interventions, 0,
        "Source attenuation must leave this bass below the limiter ceiling"
    );
    let delay = state.protector.delay_frames() * 2;
    let gain = 10f32.powf(-6.0 / 20.0);
    for (actual, original) in output[delay + 2048..].iter().zip(&pcm[2048..]) {
        assert!(
            (actual - original * gain).abs() < 1e-5,
            "Normalized bass acquired waveform distortion"
        );
    }
    drop(stats);
    std::fs::remove_file(path).unwrap();
}

#[test]
fn a_transition_matches_like_with_like_and_clamps() {
    let loud = deck(Some(-14.0), None, None);
    let quiet = deck(Some(-20.0), None, None);



    let db = DeckLoudness::match_gain_db(quiet, loud).unwrap();
    assert!((db - MAX_TRANSITION_BOOST_DB).abs() < 1e-9, "{db}");

    let db = DeckLoudness::match_gain_db(loud, quiet).unwrap();
    assert!((db + 6.0).abs() < 1e-9, "{db}");


    let very_loud = deck(Some(-8.0), None, None);
    let db = DeckLoudness::match_gain_db(very_loud, quiet).unwrap();
    assert!((db + MAX_TRANSITION_MATCH_DB).abs() < 1e-9, "{db}");


    let hot = DeckLoudness {
        true_peak_dbtp: Some(-0.5),
        ..quiet
    };
    assert_eq!(DeckLoudness::match_gain_db(hot, loud), Some(0.0));
    let roomy = DeckLoudness {
        true_peak_dbtp: Some(-2.5),
        ..quiet
    };
    let db = DeckLoudness::match_gain_db(roomy, loud).unwrap();
    assert!((db - 1.5).abs() < 1e-9, "{db}");




    let louder = deck(None, Some(6.0), None);
    let reference = deck(None, Some(0.0), None);
    let db = DeckLoudness::match_gain_db(louder, reference).unwrap();
    assert!((db + 6.0).abs() < 1e-9, "louder incoming got {db:+}");
    let db = DeckLoudness::match_gain_db(reference, louder).unwrap();
    assert!(
        (db - MAX_TRANSITION_BOOST_DB).abs() < 1e-9,
        "quieter incoming got {db:+}"
    );


    let mixed = deck(None, Some(3.0), None);
    assert_eq!(DeckLoudness::match_gain_db(mixed, loud), None);

    assert_eq!(
        DeckLoudness::match_gain_db(deck(None, None, None), loud),
        None
    );
}

#[test]
fn normalization_that_already_matched_the_decks_is_not_applied_twice() {



    let loud = deck(Some(-20.0), None, Some(6.0));
    let quiet = deck(Some(-26.0), None, Some(12.0));
    assert_eq!(DeckLoudness::match_gain_db(quiet, loud), Some(0.0));



    let loud_off = deck(Some(-20.0), None, None);
    let quiet_off = deck(Some(-26.0), None, None);
    let db = DeckLoudness::match_gain_db(loud_off, quiet_off).unwrap();
    assert!((db + 6.0).abs() < 1e-9, "{db}");




    let quiet_relative = deck(None, Some(-6.0), None);
    let reference = deck(None, Some(0.0), Some(0.0));
    let db = DeckLoudness::match_gain_db(quiet_relative, reference).unwrap();
    assert!((db - MAX_TRANSITION_BOOST_DB).abs() < 1e-9, "{db}");
}








#[test]
fn the_queue_path_matches_a_measured_deck_to_a_relative_one() {


    let dir = std::env::temp_dir().join(format!("nuvx-mixer-queue-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let loud = dir.join("a.wav");
    let quiet = dir.join("b.wav");
    level_wav(&loud, 3.0, 1_000.0, 0.5);
    level_wav(&quiet, 3.0, 1_500.0, 0.25);

    let events = std::sync::Arc::new(RecordedEvents::default());
    let (tx, rx) = crossbeam_channel::unbounded::<Command>();
    let (consumer_side, mut consumer) = rtrb::RingBuffer::<f32>::new(44_100 * 2 * 4);
    let buffered = std::sync::Arc::new(AtomicU64::new(0));
    let position = std::sync::Arc::new(AtomicU64::new(0));
    let duration = std::sync::Arc::new(AtomicU64::new(0));
    let shutdown = std::sync::Arc::new(AtomicBool::new(false));
    let nerd = std::sync::Arc::new(Mutex::new(NerdSnapshot::default()));

    let handle = std::thread::spawn({
        let events = events.clone();
        let buffered = buffered.clone();
        let position = position.clone();
        let duration = duration.clone();
        let shutdown = shutdown.clone();
        let nerd = nerd.clone();
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
                nerd,
            )
        }
    });

    let source = |path: &std::path::Path, loudness: f64| TrackSource {
        source: path.display().to_string(),
        title: path.display().to_string(),
        artist: String::new(),
        start_seconds: 0.0,
        plan: TransitionPlan::default(),
        headers: std::collections::HashMap::new(),
        claimed_kbps: 0,
        loudness_db: Some(loudness),
        duration_seconds: 3.0,
    };
    tx.send(Command::Load {
        request: source(&loud, 0.0),
        reply: crossbeam_channel::bounded(1).0,
    })
    .unwrap();

    tx.send(Command::SetLoudnessMeasurement(measured(-14.0)))
        .unwrap();


    tx.send(Command::QueueNext {
        request: source(&quiet, 6.0),
    })
    .unwrap();





    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);



    fn drain(consumer: &mut rtrb::Consumer<f32>, buffered: &AtomicU64) {
        while consumer.pop().is_ok() {
            let current = buffered.load(Ordering::Relaxed);
            if current > 0 {
                let _ = buffered.compare_exchange(
                    current,
                    current - 1,
                    Ordering::Relaxed,
                    Ordering::Relaxed,
                );
            }
        }
    }

    let mut armed = None;
    loop {
        drain(&mut consumer, &buffered);
        armed = nerd.lock().unwrap().transition_match_db;
        if armed.is_some() {
            break;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the transition was never armed"
        );
        std::thread::sleep(std::time::Duration::from_millis(2));
    }




    shutdown.store(true, Ordering::Release);
    let stop = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while !handle.is_finished() {
        drain(&mut consumer, &buffered);
        assert!(std::time::Instant::now() < stop, "the mixer did not stop");
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    handle.join().unwrap();

    assert_eq!(
        armed,
        Some(-6.0),
        "a measured outgoing deck and a relative incoming one declined to match"
    );
}

#[test]
fn the_voice_path_matches_a_six_lu_blend_and_releases() {

    let (loud, quiet) = levels();
    let mut outgoing = open_voice(&quiet);
    let mut incoming = open_voice(&loud);
    outgoing.measured_loudness = Some(measured(-20.0));
    incoming.measured_loudness = Some(measured(-14.0));

    incoming.arm_transition_match(Some(&outgoing), false);
    assert!(
        (incoming.match_db + 6.0).abs() < 1e-6,
        "armed {}",
        incoming.match_db
    );


    assert!(
        (incoming.smoothed_match - db_to_gain(-6.0) as f32).abs() < 1e-6,
        "the match was not in place at arm: {}",
        incoming.smoothed_match
    );

    let mixed = render_blend(&mut outgoing, &mut incoming, false);
    let (spread, peak) = level_spread(&mixed);
    assert!(spread < 1.0, "level moved {spread:.2} dB across the blend");
    assert!(peak <= 1.0, "peak {peak} clipped");
    assert!(
        incoming.smoothed_match < 0.55,
        "the cut did not hold: {}",
        incoming.smoothed_match
    );


    incoming.release_transition_match();
    assert_eq!(incoming.match_db, 0.0);
    let mut previous = incoming.smoothed_match;
    for _ in 0..120 {
        let frames = incoming.pull(1024, 48_000);
        let mut out = vec![0.0f32; frames.len()];
        incoming.mix_into(&frames, &mut out, 1.0, false);
        assert!(
            incoming.smoothed_match >= previous - 1e-6,
            "release went back down: {} < {previous}",
            incoming.smoothed_match
        );
        previous = incoming.smoothed_match;
    }
    assert!(previous > 0.7, "release stalled at {}", previous);
}

#[test]
fn the_same_blend_without_the_match_dips() {
    let (loud, quiet) = levels();
    let mut outgoing = open_voice(&loud);
    let mut incoming = open_voice(&quiet);
    outgoing.measured_loudness = Some(measured(-14.0));
    incoming.measured_loudness = Some(measured(-20.0));

    let mixed = render_blend(&mut outgoing, &mut incoming, false);
    let (spread, _) = level_spread(&mixed);
    assert!(
        spread > 1.0,
        "the unmatched blend should dip, moved {spread:.2} dB"
    );
}

#[test]
fn a_normalized_blend_is_not_corrected_twice() {
    let (loud, quiet) = levels();
    let mut outgoing = open_voice(&loud);
    let mut incoming = open_voice(&quiet);
    outgoing.normalization_mode = LoudnessMode::Track;
    incoming.normalization_mode = LoudnessMode::Track;
    outgoing.measured_loudness = Some(measured(-14.0));
    incoming.measured_loudness = Some(measured(-20.0));




    incoming.arm_transition_match(Some(&outgoing), true);
    assert_eq!(
        incoming.match_db, 0.0,
        "the same difference was corrected twice"
    );

    let mixed = render_blend(&mut outgoing, &mut incoming, true);
    let (spread, peak) = level_spread(&mixed);
    assert!(spread < 1.0, "normalized blend moved {spread:.2} dB");
    assert!(peak <= 1.0, "peak {peak} clipped");
}

#[test]
fn silence_floor_uses_device_rate_not_source_rate() {



    assert!(!silence_exceeds_floor(44_100, 96_000));
    assert!(!silence_exceeds_floor(48_000, 48_000));
    assert!(silence_exceeds_floor(48_001, 48_000));
    assert!(silence_exceeds_floor(96_001, 96_000));
}

#[test]
fn silence_classifier_matches_media3_int16_threshold() {
    let just_under = 1023.0 / 32768.0;
    let just_over = 1025.0 / 32768.0;
    assert!(is_silent(&vec![just_under; 64]));
    assert!(!is_silent(&vec![just_over; 64]));
    let mut mixed = vec![0.0f32; 64];
    mixed[10] = just_over;
    assert!(!is_silent(&mixed));
    assert!(!is_silent(&[]));
}
