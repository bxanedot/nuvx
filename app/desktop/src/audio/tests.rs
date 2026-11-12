use super::*;

    #[test]
    fn outgoing_clock_survives_incoming_position_reset() {
        let status = AudioStatus::default();
        status.sample_outgoing(Duration::from_secs(180), false);
        status.set_position(0);
        assert_eq!(status.outgoing_position(), Duration::from_secs(180));
        status.set_position(2500);
        assert_eq!(status.outgoing_position(), Duration::from_secs(180));
    }

    #[test]
    fn paused_outgoing_clock_does_not_extrapolate() {
        let status = AudioStatus::default();
        status.sample_outgoing(Duration::from_secs(42), false);
        status.outgoing_sampled_at.store(0, Ordering::Relaxed);
        assert!(!status.outgoing_playing());
        assert_eq!(status.outgoing_position(), Duration::from_secs(42));
    }

    #[test]
    fn crossfade_ramps_mirror_each_other_and_end_the_fading_song() {
        use std::num::NonZero;
        let ones = || rodio::buffer::SamplesBuffer::new(NonZero::new(1).unwrap(), NonZero::new(1000).unwrap(), vec![1.0; 1000]);
        let status = Arc::new(AudioStatus::default());
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(ones(), status.clone(), 100, Arc::new(AtomicU32::new(0)), false, Default::default());
        let mut outgoing = Meter::new(ones(), status, 0, out.clone(), false, Default::default());
        assert_eq!(outgoing.next(), Some(1.0));
        out.store(100, Ordering::Relaxed);
        let (a, b): (Vec<f32>, Vec<f32>) = (0..100).map(|_| (incoming.next().unwrap(), outgoing.next().unwrap())).unzip();
        assert_eq!(a[0], 0.0);
        assert_eq!(b[0], 1.0);
        for (x, y) in a.iter().zip(&b) {
            assert!((x + y - 1.0).abs() < 1e-4);
        }
        assert!((b[50] - 0.5).abs() < 1e-4);
        assert_eq!(incoming.next(), Some(1.0));
        assert_eq!(outgoing.next(), None);
    }

    #[test]
    fn automix_envelopes_hold_then_exchange_at_sixty_five_percent() {
        use std::num::NonZero;
        let ones = || rodio::buffer::SamplesBuffer::new(NonZero::new(1).unwrap(), NonZero::new(1000).unwrap(), vec![1.0; 1000]);
        let status = Arc::new(AudioStatus::default());
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(ones(), status.clone(), 100, Arc::new(AtomicU32::new(0)), true, Default::default());
        let mut outgoing = Meter::new(ones(), status, 0, out.clone(), false, Default::default());
        assert_eq!(outgoing.next(), Some(1.0));
        out.store(100 | AUTOMIX_FADE, Ordering::Relaxed);
        let (a, b): (Vec<f32>, Vec<f32>) = (0..100).map(|_| (incoming.next().unwrap(), outgoing.next().unwrap())).unzip();
        assert_eq!(a[25], 0.0);
        assert_eq!(b[25], 1.0);
        for (x, y) in a.iter().zip(&b) {
            assert!((x + y - 1.0).abs() < 1e-4);
        }
        assert!((a[65] - 0.5).abs() < 1e-4);
        assert_eq!(incoming.next(), Some(1.0));
        assert_eq!(outgoing.next(), None);
    }

    #[test]
    fn transition_fx_are_dry_outside_the_blend_and_keep_channels_separate() {
        let mut fx = MixFx::new(48_000, 2).unwrap();
        for i in 0..48_000 {
            let input = (i as f32 * 0.03).sin() * 0.5;
            assert_eq!(fx.process(input, None), input);
            assert_eq!(fx.process(0.0, None), 0.0);
        }
        fx.begin(120.0);
        let mut difference = 0.0;
        for frame in 0..48_000 {
            let input = (frame as f32 * 0.03).sin() * 0.5;
            let ramp = Some(Ramp::MixOut { from: 1.0, at: frame * 2, len: 96_000 });
            let left = fx.process(input, ramp);
            let right = fx.process(0.0, ramp);
            assert!(left.is_finite() && left.abs() < 1.0);
            assert_eq!(right, 0.0);
            difference += (left - input).abs();
        }
        assert!(difference > 10.0);
        assert_eq!(fx.process(0.25, None), 0.25);
        assert_eq!(fx.process(-0.25, None), -0.25);
    }

    #[test]
    fn automix_repeat_and_flanger_are_audible() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(120.0);
        let ramp = Some(Ramp::MixOut { from: 1.0, at: 60_000, len: 100_000 });
        let out: Vec<f32> = (0..30_000).map(|i| fx.process(if i == 0 { 1.0 } else { 0.0 }, ramp)).collect();
        let comb: f32 = out[20..200].iter().map(|x| x.abs()).sum();
        assert!(comb > 0.01, "no flanged comb after the impulse: {comb}");
        let echo: f32 = out[11_950..12_060].iter().map(|x| x.abs()).sum();
        assert!(echo > 0.01, "no half-beat repeat: {echo}");
    }

    #[test]
    fn enabled_effects_finish_without_changing_the_incoming_position() {
        use std::num::NonZero;
        let rate = 48_000;
        let source = |step: f32| rodio::buffer::SamplesBuffer::new(
            NonZero::new(1).unwrap(),
            NonZero::new(rate).unwrap(),
            (0..rate * 2).map(|i| (i as f32 * step).sin() * 0.3).collect::<Vec<_>>(),
        );
        let status = Arc::new(AudioStatus::default());
        status.automix.store(true, Ordering::Relaxed);
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(source(0.04), status.clone(), 1000, Default::default(), true, Default::default());
        let mut outgoing = Meter::new(source(0.03), status, 0, out.clone(), false, Arc::new(OnceLock::from(Some(120.0))));
        assert!(incoming.fx.is_some() && outgoing.fx.is_some());
        assert_eq!(outgoing.by_ref().take(12_000).count(), 12_000);
        out.store(1000 | AUTOMIX_FADE, Ordering::Relaxed);
        for _ in 0..rate {
            let mixed = incoming.next().unwrap() + outgoing.next().unwrap();
            assert!(mixed.is_finite() && mixed.abs() < 1.0);
        }
        assert_eq!(outgoing.next(), None);
        assert_eq!(incoming.next(), Some((rate as f32 * 0.04).sin() * 0.3));
        assert!(incoming.ramp.is_none());
    }

    #[test]
    fn a_quick_fade_retains_the_effect_instead_of_jumping_to_dry() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(120.0);
        let mut before = 0.0;
        for at in 0..36_000 {
            before = fx.process(0.4, Some(Ramp::MixOut { from: 1.0, at, len: 48_000 }));
        }
        let after = fx.process(0.4, Some(Ramp::Out { from: 0.3, at: 0, len: 1920 }));
        assert!((after - before).abs() < 0.01);
        assert!((after - 0.4).abs() > 0.05);
    }

    #[test]
    fn transition_buffers_are_bounded_and_do_not_grow() {
        for rate in [8_000, 44_100, 48_000, 96_000, 192_000] {
            let mut fx = MixFx::new(rate, 2).unwrap();
            let bytes = fx.ring.capacity() * std::mem::size_of::<f32>();
            assert!(bytes <= FX_MAX_FRAMES * 2 * 4);
            let address = fx.ring.as_ptr();
            fx.begin(70.0);
            assert!(fx.beat <= (fx.ring.len() / fx.channels - 1) as f32);
            for at in 0..rate * 2 {
                fx.process(0.1, Some(Ramp::MixOut { from: 1.0, at, len: rate * 2 }));
            }
            assert_eq!(fx.ring.as_ptr(), address);
            assert_eq!(fx.ring.capacity() * 4, bytes);
        }
        assert!(MixFx::new(48_000, 9).is_none());
    }

    #[test]
    fn unknown_tempo_uses_a_finite_tail_and_seek_clears_history() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(0.0);
        assert!(!fx.rhythmic);
        let mut tail = 0.0;
        for at in 0..48_000 {
            let input = if at == 24_000 { 0.5 } else { 0.0 };
            let sample = fx.process(input, Some(Ramp::MixOut { from: 1.0, at, len: 48_000 }));
            assert!(sample.is_finite() && sample.abs() <= 0.5);
            if at > 24_001 {
                tail += sample.abs();
            }
        }
        assert!(tail > 0.001);
        fx.reset();
        assert!(fx.ring.iter().all(|v| *v == 0.0));
        assert_eq!(fx.process(0.0, None), 0.0);
    }

    #[test]
    fn tempo_analysis_finishes_once_even_for_silence() {
        for silence in [false, true] {
            let mut tempo = Tempo::new(20.0);
            let mut result = None;
            for i in 0..300 {
                result = tempo.push(if !silence && i % 25 == 0 { 1.0 } else { 0.0 });
            }
            assert!(tempo.done);
            if silence {
                assert!(result.is_none());
            } else {
                assert!((result.unwrap() - 120.0).abs() < 1.0);
            }
            let history = tempo.hist;
            for _ in 0..1000 {
                assert!(tempo.push(0.8).is_none());
            }
            assert_eq!(tempo.hist, history);
            assert_eq!(tempo.count, 300);
        }
    }

    #[test]
    fn replacing_a_decoder_reuses_its_completed_analysis() {
        use std::num::NonZero;
        for result in [Some(120.0), None] {
            let analysis = Arc::new(OnceLock::from(result));
            let source = rodio::buffer::SamplesBuffer::new(NonZero::new(2).unwrap(), NonZero::new(48_000).unwrap(), vec![0.0; 4096]);
            let status = Arc::new(AudioStatus::default());
            status.automix.store(true, Ordering::Relaxed);
            let mut meter = Meter::new(source, status, 0, Default::default(), false, analysis.clone());
            assert!(meter.tempo.done);
            assert_eq!(meter.by_ref().count(), 4096);
            assert_eq!(meter.tempo.count, 0);
            assert_eq!(analysis.get(), Some(&result));
        }
    }

    #[test]
    fn mix_status_is_bound_to_the_started_generation() {
        let status = AudioStatus::default();
        status.mix_duration_ms.store(12_345, Ordering::Relaxed);
        status.mix_active.store(true, Ordering::Relaxed);
        status.mix_generation.store(7, Ordering::Release);
        assert_eq!(status.mix_duration(6), None);
        assert_eq!(status.mix_duration(7), Some(12.345));
        assert!(status.mixing());
        status.mix_active.store(false, Ordering::Relaxed);
        assert!(!status.mixing());
    }

    #[test]
    fn normalization_only_turns_loud_tracks_down() {
        let status = AudioStatus::default();
        let loud = Shared::new(1, 0, Some(7.29), crate::stream::Format::Aac, None, false);
        let quiet = Shared::new(1, 0, Some(-4.0), crate::stream::Format::Aac, None, false);
        let unknown = Shared::new(1, 0, None, crate::stream::Format::Aac, None, false);
        status.normalize.store(true, Ordering::Relaxed);
        assert!((norm_gain(&status, Some(&loud)) - 0.432).abs() < 0.001);
        assert_eq!(norm_gain(&status, Some(&quiet)), 1.0);
        assert_eq!(norm_gain(&status, Some(&unknown)), 1.0);
        assert_eq!(norm_gain(&status, None), 1.0);
        status.normalize.store(false, Ordering::Relaxed);
        assert_eq!(norm_gain(&status, Some(&loud)), 1.0);
    }

    #[test]
    fn spatial_widens_stereo_and_leaves_it_alone_when_off() {
        let status = Arc::new(AudioStatus::default());
        let frames = [0.5f32, -0.5, 0.25, 0.25].repeat(2000);
        let src = || rodio::buffer::SamplesBuffer::new(rodio::ChannelCount::new(2).unwrap(), rodio::SampleRate::new(44100).unwrap(), frames.clone());
        let off: Vec<f32> = Spatial::new(src(), status.clone()).collect();
        assert_eq!(off, frames);
        status.spatial.store(true, Ordering::Relaxed);
        let on: Vec<f32> = Spatial::new(src(), status).collect();
        assert_eq!(on.len(), frames.len());
        assert!((on[0] - 0.5 * 2.5 * 0.82).abs() < 1e-6 && (on[1] + 0.5 * 2.5 * 0.82).abs() < 1e-6);
        assert!((on[2] - on[3]).abs() < 1e-6);
    }

    #[test]
    fn log_volume_keeps_loudness_when_switched() {
        let (tx, _rx) = crossbeam_channel::unbounded();
        let audio = AudioHandle { tx, status: Arc::new(AudioStatus::default()) };
        audio.set_volume(0.125);
        audio.set_log_volume(true);
        assert!((audio.volume() - 0.5).abs() < 1e-6);
        assert!((slider_gain(&audio.status, audio.volume()) - 0.125).abs() < 1e-6);
        audio.set_log_volume(true);
        assert!((audio.volume() - 0.5).abs() < 1e-6);
        audio.set_log_volume(false);
        assert!((audio.volume() - 0.125).abs() < 1e-6);
    }
