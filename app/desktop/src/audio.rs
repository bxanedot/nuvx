use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use crossbeam_channel::{Receiver, Sender, unbounded};
use rodio::Player;

mod control;
mod effects;
mod fading;
mod mix;
mod output;
mod seek;
mod status;
pub use control::{AudioCmd, AudioEvent, AudioHandle};
use effects::{Eq, Meter, Spatial};
use fading::{Fading, HANDOFF_WAIT, QUICK_FADE_MS};
use mix::{mix_ms, norm_gain, slider_gain};
use output::{Output, DEVICE_CHECK};
use seek::{SeekJob, build_seek};
pub use output::{output_devices, OutputDevice};
pub use status::AudioStatus;
#[cfg(test)]
use effects::{AUTOMIX_FADE, FX_MAX_FRAMES, MixFx, Ramp, Tempo};
use crate::stream::Shared;

pub use crate::stream::TrackDecoder;

pub fn spawn(volume: f32, log_volume: bool, device: Option<String>, events: Sender<AudioEvent>, wake: impl Fn() + Send + 'static) -> AudioHandle {
    let (tx, rx) = unbounded();
    let status = Arc::new(AudioStatus::default());
    status.volume.store(volume.to_bits(), Ordering::Relaxed);
    status.log_volume.store(log_volume, Ordering::Relaxed);
    let st = status.clone();
    std::thread::Builder::new()
        .name("audio".into())
        .spawn({
            let tx = tx.clone();
            move || run(rx, tx, st, volume, device, events, wake)
        })
        .expect("spawn audio thread");
    AudioHandle { tx, status }
}

fn run(
    rx: Receiver<AudioCmd>,
    tx: Sender<AudioCmd>,
    status: Arc<AudioStatus>,
    volume: f32,
    device: Option<String>,
    events: Sender<AudioEvent>,
    wake: impl Fn(),
) {
    let mut output = Output::new(tx.clone(), &status, device);
    if output.sink.is_none() {
        let _ = events.send(AudioEvent::Error("No audio output device".into()));
        wake();
    }
    let mut player = Player::connect_new(&output.mixer);
    player.set_volume(slider_gain(&status, volume));
    let mut fade_out = Arc::new(AtomicU32::new(0));
    let mut fading: Vec<Fading> = Vec::new();
    let mut ending_sent = false;
    let mut length: Option<Duration> = None;

    let mut current: u64 = 0;
    let mut shared: Option<Arc<Shared>> = None;
    let mut offset = Duration::ZERO;
    let mut seekable = false;
    let mut seek_seq: u64 = 0;
    let seek_job: Arc<std::sync::Mutex<Option<SeekJob>>> = Default::default();
    let seek_busy = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let mut dev_lost = std::env::var("NUVX_DEVICE_LOST").ok().and_then(|v| v.parse::<f32>().ok()).map(Duration::from_secs_f32);
    loop {
        let timeout = if (current != 0 || !fading.is_empty()) && !player.is_paused() { Duration::from_millis(100) } else { Duration::from_secs(3600) };
        match rx.recv_timeout(timeout) {
            Ok(AudioCmd::Start { generation, decoder, shared: s, at }) => {
                if generation != status.wanted_gen.load(Ordering::Relaxed) {
                    log::debug!("audio: dropping stale generation {generation}");
                    continue;
                }
                log::debug!("audio: start generation {generation}");
                if output.sink.is_none() || output.default_changed() {
                    output.open(tx.clone(), &status);
                }
                player.clear();
                let gain = norm_gain(&status, Some(&s));
                log::info!("audio: loudness {:?} dB, normalization gain {gain:.3}", s.loudness_db);
                player.set_volume(slider_gain(&status, f32::from_bits(status.volume.load(Ordering::Relaxed))) * gain);
                fading.retain(|f| !f.player.empty());
                let skip_len = status.crossfade_ms.load(Ordering::Relaxed);
                let natural_len = mix_ms(&status, length);
                let mut fade_in = 0;
                for f in fading.iter_mut().filter(|f| f.waiting.is_some()) {
                    fade_in = f.start(if f.natural { natural_len } else { skip_len }, status.automix.load(Ordering::Relaxed));
                    log::debug!("audio: crossfade into generation {generation} over {fade_in} ms");
                }
                fade_out = Arc::new(AtomicU32::new(0));
                let mix = fade_in > 0 && status.automix.load(Ordering::Relaxed);
                status.bpm.store(s.tempo.get().copied().flatten().unwrap_or(0.0).to_bits(), Ordering::Relaxed);
                status.mix_duration_ms.store(fade_in, Ordering::Relaxed);
                status.mix_active.store(mix, Ordering::Relaxed);
                status.mix_generation.store(generation, Ordering::Release);
                player.append(Meter::new(Eq::new(Spatial::new(decoder, status.clone()), status.clone()), status.clone(), fade_in, fade_out.clone(), mix, s.tempo.clone()));
                player.play();
                if s.duration_ms > 0 {
                    status.duration_ms.store(s.duration_ms, Ordering::Relaxed);
                }
                length = status.duration();
                if let Some(old) = shared.replace(s.clone())
                    && !Arc::ptr_eq(&old, &s)
                {
                    old.cancel();
                }
                current = generation;
                offset = at;
                seekable = !at.is_zero();
                ending_sent = false;
                status.playing.store(true, Ordering::Relaxed);
            }
            Ok(AudioCmd::Replace { generation, seek, decoder, at, seekable: s }) => {
                if generation != current || seek != seek_seq {
                    continue;
                }
                log::debug!("audio: seek to {at:?} via new decoder (seekable: {s})");
                seekable = s;
                let paused = player.is_paused();
                player.clear();
                fade_out = Arc::new(AtomicU32::new(0));
                let analysis = shared.as_ref().expect("a current generation has a stream").tempo.clone();
                player.append(Meter::new(Eq::new(Spatial::new(decoder, status.clone()), status.clone()), status.clone(), 0, fade_out.clone(), false, analysis));
                if !paused {
                    player.play();
                }
                offset = at;
            }
            Ok(AudioCmd::Pause) => {
                log::debug!("audio: pause at {:?}", offset + player.get_pos());
                player.pause();
                fading.iter().for_each(|f| f.player.pause());
                status.playing.store(false, Ordering::Relaxed);
                wake();
            }
            Ok(AudioCmd::Resume) => {
                log::debug!("audio: resume (generation {current})");
                if current != 0 {
                    if output.sink.is_none() || output.default_changed() {
                        output.open(tx.clone(), &status);
                    }
                    player.play();
                    fading.iter().for_each(|f| f.player.play());
                    status.playing.store(true, Ordering::Relaxed);
                    wake();
                }
            }
            Ok(AudioCmd::Handoff)
                if mix_ms(&status, length) > 0
                    && current != 0
                    && !player.is_paused()
                    && !player.empty()
                    && fading.iter().all(|f| f.player.empty()) =>
            {
                let left = length.map(|d| d.saturating_sub(offset + player.get_pos()));
                log::debug!("audio: handing off from generation {current}, {left:?} left");
                let old = std::mem::replace(&mut player, Player::connect_new(&output.mixer));
                fading.push(Fading { player: old, offset, shared: shared.take(), out: fade_out.clone(), waiting: Some((Instant::now(), left)), natural: ending_sent });
                current = 0;
                status.playing.store(false, Ordering::Relaxed);
                status.set_position(0);
            }
            Ok(cmd @ (AudioCmd::Stop | AudioCmd::Handoff)) => {
                if matches!(cmd, AudioCmd::Handoff) {
                    for f in fading.iter_mut() {
                        f.waiting = None;
                        f.out.store(QUICK_FADE_MS, Ordering::Relaxed);
                    }
                    log::debug!("audio: skipped mid-crossfade, cutting over");
                    if current != 0 && !player.is_paused() && !player.empty() {
                        fade_out.store(QUICK_FADE_MS, Ordering::Relaxed);
                        let old = std::mem::replace(&mut player, Player::connect_new(&output.mixer));
                        fading.push(Fading { player: old, offset, shared: shared.take(), out: fade_out.clone(), waiting: None, natural: false });
                    } else {
                        player.clear();
                    }
                } else {
                    player.clear();
                    fading.clear();
                }
                current = 0;
                status.mix_active.store(false, Ordering::Relaxed);
                status.mix_generation.store(0, Ordering::Release);
                if let Some(old) = shared.take() {
                    old.cancel();
                }
                status.playing.store(false, Ordering::Relaxed);
                status.set_position(0);
            }
            Ok(cmd @ (AudioCmd::Seek(_) | AudioCmd::SeekLive { .. })) => {
                let (pos, live) = match cmd {
                    AudioCmd::SeekLive { pos, at } => (pos + at.elapsed(), Some((pos, at))),
                    AudioCmd::Seek(pos) => (pos, None),
                    _ => unreachable!(),
                };
                seek_seq += 1;
                if current == 0 {
                    continue;
                }
                for f in &fading {
                    f.out.store(QUICK_FADE_MS, Ordering::Relaxed);
                }
                if seekable && player.try_seek(pos).is_ok() {
                    log::debug!("audio: seek to {pos:?} in place");
                    offset = Duration::ZERO;
                    status.set_position(pos.as_millis() as u64);
                    continue;
                }
                if let Some(s) = shared.clone() {
                    *seek_job.lock().unwrap() = Some(SeekJob { shared: s, pos, live, generation: current, seek: seek_seq });
                    if !seek_busy.swap(true, Ordering::AcqRel) {
                        let (job, busy, tx, events) = (seek_job.clone(), seek_busy.clone(), tx.clone(), events.clone());
                        std::thread::spawn(move || {
                            loop {
                                let next = job.lock().unwrap().take();
                                match next {
                                    Some(j) => build_seek(j, &tx, &events),
                                    None => {
                                        busy.store(false, Ordering::Release);
                                        if job.lock().unwrap().is_none() || busy.swap(true, Ordering::AcqRel) {
                                            break;
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }
            Ok(AudioCmd::DeviceLost) => {
                let was = output.device.clone();
                output.open(tx.clone(), &status);
                if output.device != was && !player.is_paused() && (current != 0 || !fading.is_empty()) {
                    log::info!("audio: output device gone, pausing");
                    player.pause();
                    fading.iter().for_each(|f| f.player.pause());
                    status.playing.store(false, Ordering::Relaxed);
                    wake();
                }
            }
            Ok(AudioCmd::SetDevice(id)) => {
                log::info!("audio: output device set to {id:?}");
                output.preferred = id;
                if output.default_changed() || output.sink.is_none() {
                    output.open(tx.clone(), &status);
                }
            }
            Ok(AudioCmd::Volume(v)) => {
                let v = slider_gain(&status, v);
                player.set_volume(v * norm_gain(&status, shared.as_deref()));
                for f in &fading {
                    f.player.set_volume(v * norm_gain(&status, f.shared.as_deref()));
                }
            }
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => return,
        }

        if current != 0 && dev_lost.is_some_and(|at| offset + player.get_pos() >= at) {
            dev_lost = None;
            log::info!("audio: dev hook: output device lost");
            output.sink = None;
            output.device = Some("dev-hook-gone".into());
            output.lost.store(true, Ordering::Relaxed);
            let _ = tx.send(AudioCmd::DeviceLost);
        }
        if (current != 0 || !fading.is_empty())
            && !player.is_paused()
            && output.checked.elapsed() >= DEVICE_CHECK
            && !output.lost.load(Ordering::Relaxed)
            && (output.sink.is_none() || output.default_changed())
        {
            output.open(tx.clone(), &status);
        }
        if !fading.is_empty() {
            if let Some(f) = fading.iter().find(|f| f.natural) {
                status.sample_outgoing(f.offset + f.player.get_pos(), !f.player.is_paused() && !f.player.empty());
            }
            for f in fading.iter_mut().filter(|f| !status.automix.load(Ordering::Relaxed) && f.waiting.is_some_and(|(at, _)| at.elapsed() > HANDOFF_WAIT)) {
                let len = if f.natural { mix_ms(&status, length) } else { status.crossfade_ms.load(Ordering::Relaxed) };
                log::debug!("audio: next song not ready, fading out over {} ms", f.start(len, status.automix.load(Ordering::Relaxed)));
            }
            fading.retain(|f| !f.player.empty());
            if fading.is_empty() {
                status.mix_active.store(false, Ordering::Relaxed);
                wake();
                log::debug!("audio: crossfade done");
            }
        }
        if current != 0 {
            let pos = offset + player.get_pos();
            status.set_position(pos.as_millis() as u64);
            let crossfade = Duration::from_millis(mix_ms(&status, length) as u64);
            if !ending_sent
                && !crossfade.is_zero()
                && let Some(d) = length
                && d > crossfade * 2
                && pos + crossfade >= d
                && shared.as_ref().is_some_and(|s| s.error().is_none())
            {
                ending_sent = true;
                status.sample_outgoing(pos, !player.is_paused());
                let _ = events.send(AudioEvent::Ending { generation: current });
                wake();
            }
            if player.empty() {
                let event = match shared.as_ref().and_then(|s| s.error()) {
                    Some(reason) => AudioEvent::Interrupted { generation: current, at: offset + player.get_pos(), reason },
                    None => AudioEvent::Finished { generation: current },
                };
                let _ = events.send(event);
                current = 0;
                shared = None;
                status.playing.store(false, Ordering::Relaxed);
                wake();
            }
        }
    }
}

#[cfg(test)]
mod tests {
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
}
