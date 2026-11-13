use super::*;

pub(super) fn run(
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
            Ok(AudioCmd::Start { generation, decoder, shared: s, at, start_paused }) => {
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
                if !start_paused {
                    player.play();
                }
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
                status.playing.store(!start_paused, Ordering::Relaxed);
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
