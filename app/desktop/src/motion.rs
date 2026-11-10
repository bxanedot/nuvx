//! Looping cover clips (Apple's motion sleeves, Tidal's video covers), played locally.
//!
//! Apple's clip is HLS, but each variant's media playlist is a single byte-ranged
//! fragmented MP4, so it downloads as one file; Tidal's is a plain MP4. Media
//! Foundation decodes either off the UI thread. Only the newest frame is kept: the UI
//! turns it into a texture and draws it in place of the still sleeve.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, Weak};
use std::time::{Duration, Instant};

pub struct Frame {
    pub w: usize,
    pub h: usize,
    pub rgba: Vec<u8>,
}

pub struct Motion {
    latest: Mutex<Option<Arc<Frame>>>,
    version: AtomicU64,
    stop: Arc<AtomicBool>,
}

impl Motion {
    fn new() -> Self {
        Self { latest: Mutex::new(None), version: AtomicU64::new(0), stop: Arc::new(AtomicBool::new(false)) }
    }

    /// The newest frame, with the counter that says it changed.
    pub fn latest(&self) -> Option<(u64, Arc<Frame>)> {
        let frame = self.latest.lock().ok()?.clone()?;
        Some((self.version.load(Ordering::Acquire), frame))
    }

    /// Stop decoding: the page that showed this clip is gone.
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }

    fn publish(&self, ctx: &egui::Context, w: usize, h: usize, rgba: Vec<u8>) {
        if let Ok(mut slot) = self.latest.lock() {
            *slot = Some(Arc::new(Frame { w, h, rgba }));
        }
        self.version.fetch_add(1, Ordering::Release);
        ctx.request_repaint();
    }
}

impl Drop for Motion {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

/// Whether this platform can play the clips: Media Foundation is Windows'. Elsewhere
/// the still art stays, and nothing is looked up.
pub const SUPPORTED: bool = cfg!(windows);

static STREAMS: LazyLock<Mutex<HashMap<String, Weak<Motion>>>> = LazyLock::new(Default::default);

/// Start (or reuse) the clip at `master`, decoded no wider than `max_width`.
pub fn play(ctx: &egui::Context, master: &str, max_width: u32) -> Arc<Motion> {
    let key = format!("{max_width}:{master}");
    if let Ok(mut streams) = STREAMS.lock() {
        if let Some(m) = streams.get(&key).and_then(Weak::upgrade) {
            return m;
        }
        let motion = Arc::new(Motion::new());
        streams.insert(key, Arc::downgrade(&motion));
        let (this, ctx, url) = (motion.clone(), ctx.clone(), master.to_owned());
        let _ = std::thread::Builder::new().name("motion".into()).spawn(move || run(this, ctx, url, max_width));
        return motion;
    }
    Arc::new(Motion::new())
}

fn run(motion: Arc<Motion>, ctx: egui::Context, master: String, max_width: u32) {
    let Some(path) = fetch(&master, max_width) else {
        log::info!("apple: no motion clip downloaded");
        return;
    };
    decode(&motion, &path, &ctx, None);
}

/// A music video's picture from its saved file, held to the song's clock as Android's
/// player does: each frame waits for the audio, and one far off it (a seek) moves the
/// picture there. The audio stays the clock; the picture plays silent.
pub fn video(ctx: &egui::Context, path: PathBuf, clock: Arc<crate::audio::AudioStatus>) -> Arc<Motion> {
    let motion = Arc::new(Motion::new());
    let (this, ctx) = (motion.clone(), ctx.clone());
    let _ = std::thread::Builder::new().name("video".into()).spawn(move || decode(&this, &path, &ctx, Some(&clock)));
    motion
}

/// Pick a variant and save the clip to the cache; a saved copy is reused.
fn fetch(master: &str, max_width: u32) -> Option<PathBuf> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().ok()?;
    rt.block_on(async {
        use sha1::{Digest, Sha1};
        let http = crate::proxy::http();
        let file = if master.contains(".m3u8") {
            let media = crate::apple::variant(&http, master, max_width).await?;
            crate::apple::media_file(&http, &media).await?
        } else {
            crate::tidal::sized(master, max_width)
        };
        let dir = crate::data_dir().join("motion");
        std::fs::create_dir_all(&dir).ok()?;
        let name = format!("{:x}", Sha1::digest(file.as_bytes()));
        let path = dir.join(format!("{}.mp4", &name[..32]));
        if std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0) > 1024 {
            return Some(path);
        }
        let bytes = http
            .get(&file)
            .timeout(Duration::from_secs(30))
            .send()
            .await
            .ok()?
            .error_for_status()
            .ok()?
            .bytes()
            .await
            .ok()?;
        let tmp = path.with_extension("part");
        std::fs::write(&tmp, &bytes).ok()?;
        std::fs::rename(&tmp, &path).ok()?;
        log::info!("apple: motion clip {:.0} KB cached", bytes.len() as f64 / 1024.0);
        Some(path)
    })
}

#[cfg(windows)]
fn decode(motion: &Arc<Motion>, path: &std::path::Path, ctx: &egui::Context, clock: Option<&crate::audio::AudioStatus>) {
    use windows::Win32::Media::MediaFoundation::{MFShutdown, MFStartup, MFSTARTUP_FULL, MF_VERSION};
    use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx, CoUninitialize};
    unsafe {
        if CoInitializeEx(None, COINIT_MULTITHREADED).is_err() {
            return;
        }
        if MFStartup(MF_VERSION, MFSTARTUP_FULL).is_err() {
            CoUninitialize();
            return;
        }
        let mut wide: Vec<u16> = path.to_string_lossy().encode_utf16().collect();
        wide.push(0);
        let url = windows::core::PCWSTR(wide.as_ptr());
        // Each pass plays the clip once; then it starts again from the top.
        for _ in 0..100 {
            if motion.stop.load(Ordering::Relaxed) || !play_once(motion, ctx, url, clock) {
                break;
            }
        }
        let _ = MFShutdown();
        CoUninitialize();
    }
}

#[cfg(windows)]
unsafe fn play_once(motion: &Motion, ctx: &egui::Context, url: windows::core::PCWSTR, clock: Option<&crate::audio::AudioStatus>) -> bool {
    use windows::Win32::Media::MediaFoundation::*;
    let played = unsafe {
        (|| -> windows::core::Result<bool> {
        let mut attrs = None;
        MFCreateAttributes(&mut attrs, 1)?;
        let attrs = attrs.unwrap();
        // Lets the reader hand back RGB32 rather than the decoder's NV12.
        attrs.SetUINT32(&MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)?;
        let reader = MFCreateSourceReaderFromURL(url, &attrs)?;
        let stream = MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32;
        reader.SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS.0 as u32, false)?;
        reader.SetStreamSelection(stream, true)?;
        let ty = MFCreateMediaType()?;
        ty.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        ty.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)?;
        reader.SetCurrentMediaType(stream, None, &ty)?;
        let current = reader.GetCurrentMediaType(stream)?;
        let size = current.GetUINT64(&MF_MT_FRAME_SIZE)?;
        let (w, h) = ((size >> 32) as usize, (size & 0xffff_ffff) as usize);
        if w == 0 || h == 0 {
            return Ok(false);
        }
        let stride = current.GetUINT32(&MF_MT_DEFAULT_STRIDE).map(|s| s as i32).unwrap_or(-(w as i32 * 4));
        let row = w * 4;
        let start = Instant::now();
        let mut base: Option<i64> = None;
        // Following a clock: where the last seek went (frames before it are dropped, as
        // the reader lands on the keyframe ahead of it), and the last frame's time.
        let mut seeked: Option<i64> = None;
        let mut last = 0i64;
        loop {
            if motion.stop.load(Ordering::Relaxed) {
                return Ok(false);
            }
            let (mut flags, mut ts) = (0u32, 0i64);
            let mut sample: Option<IMFSample> = None;
            reader.ReadSample(stream, 0, None, Some(&mut flags), Some(&mut ts), Some(&mut sample))?;
            if flags & MF_SOURCE_READERF_ERROR.0 as u32 != 0 {
                return Ok(false);
            }
            if flags & MF_SOURCE_READERF_ENDOFSTREAM.0 as u32 != 0 {
                let Some(clock) = clock else { return Ok(true) };
                // The video has ended: hold the last frame until the song goes back.
                loop {
                    if motion.stop.load(Ordering::Relaxed) {
                        return Ok(false);
                    }
                    let at = song_at(clock);
                    if at + 10_000_000 < last {
                        seek(&reader, at)?;
                        seeked = Some(at);
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
                continue;
            }
            let Some(sample) = sample else { continue };
            last = ts;
            match clock {
                Some(clock) => {
                    if seeked.is_some_and(|to| ts < to - 5_000_000) {
                        continue;
                    }
                    // The first frame after a start or a seek shows at once: paused, the song
                    // would never reach it.
                    let first = seeked.take().is_some() || motion.version.load(Ordering::Acquire) == 0;
                    if !first && !wait_for(&reader, motion, clock, ts)? {
                        seeked = Some(song_at(clock));
                        continue;
                    }
                }
                None => {
                    let base = *base.get_or_insert(ts);
                    // Timestamps are in 100 ns units.
                    let due = Duration::from_micros(((ts - base).max(0) / 10) as u64);
                    let elapsed = start.elapsed();
                    if due > elapsed {
                        std::thread::sleep(due - elapsed);
                    }
                }
            }
            let buffer = sample.ConvertToContiguousBuffer()?;
            let (mut src, mut len) = (std::ptr::null_mut(), 0u32);
            buffer.Lock(&mut src, None, Some(&mut len))?;
            let mut rgba = vec![0u8; row * h];
            if !src.is_null() && len as usize >= row * h {
                let data = std::slice::from_raw_parts(src, len as usize);
                let (stride, bottom_up) = layout(w, h, data.len(), stride);
                for y in 0..h {
                    let o = if bottom_up { h - 1 - y } else { y } * stride;
                    if o + row > data.len() {
                        break;
                    }
                    for x in 0..w {
                        let i = o + x * 4;
                        let j = (y * w + x) * 4;
                        rgba[j] = data[i + 2];
                        rgba[j + 1] = data[i + 1];
                        rgba[j + 2] = data[i];
                        rgba[j + 3] = 255;
                    }
                }
            }
            buffer.Unlock()?;
            if motion.version.load(Ordering::Acquire) == 0 {
                log::info!("apple: motion playing at {w}x{h}");
            }
            motion.publish(ctx, w, h, rgba);
        }
    })()
    };
    match played {
        Ok(ok) => ok,
        Err(e) => {
            log::warn!("apple: motion decode failed: {e}");
            false
        }
    }
}

/// Blank out (rename to `free`) each track's edit list. YouTube's video files carry one
/// that trims ~40 ms off the start, and Media Foundation's reader stalls on it for good.
pub fn drop_edit_lists(mp4: &mut [u8]) {
    fn children(d: &[u8], start: usize, end: usize) -> Vec<(usize, usize)> {
        let mut out = Vec::new();
        let mut i = start;
        while i + 8 <= end {
            let n = u32::from_be_bytes([d[i], d[i + 1], d[i + 2], d[i + 3]]) as usize;
            if n < 8 || i + n > end {
                break;
            }
            out.push((i, i + n));
            i += n;
        }
        out
    }
    let kind = |d: &[u8], at: usize| <[u8; 4]>::try_from(&d[at + 4..at + 8]).unwrap_or_default();
    for (m, m_end) in children(mp4, 0, mp4.len()) {
        if &kind(mp4, m) != b"moov" {
            continue;
        }
        for (t, t_end) in children(mp4, m + 8, m_end) {
            if &kind(mp4, t) != b"trak" {
                continue;
            }
            for (e, _) in children(mp4, t + 8, t_end) {
                if &kind(mp4, e) == b"edts" {
                    mp4[e + 4..e + 8].copy_from_slice(b"free");
                }
            }
        }
    }
}

/// Where the song is, in Media Foundation's 100 ns units.
#[cfg(windows)]
fn song_at(clock: &crate::audio::AudioStatus) -> i64 {
    clock.position_smooth().as_micros() as i64 * 10
}

/// Hold the frame at `ts` until the song reaches it (paused, it waits). False: the song
/// is elsewhere, half a second behind it or two ahead, and the reader has been sent there.
#[cfg(windows)]
unsafe fn wait_for(reader: &windows::Win32::Media::MediaFoundation::IMFSourceReader, motion: &Motion, clock: &crate::audio::AudioStatus, ts: i64) -> windows::core::Result<bool> {
    loop {
        if motion.stop.load(Ordering::Relaxed) {
            return Ok(false);
        }
        let at = song_at(clock);
        if ts < at - 5_000_000 || ts > at + 20_000_000 {
            unsafe { seek(reader, at)? };
            return Ok(false);
        }
        if ts <= at {
            return Ok(true);
        }
        std::thread::sleep(Duration::from_micros(((ts - at) / 10).min(20_000) as u64));
    }
}

#[cfg(windows)]
unsafe fn seek(reader: &windows::Win32::Media::MediaFoundation::IMFSourceReader, to: i64) -> windows::core::Result<()> {
    use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
    let mut at = PROPVARIANT::default();
    unsafe {
        (*at.Anonymous.Anonymous).vt = windows::Win32::System::Variant::VT_I8;
        (*at.Anonymous.Anonymous).Anonymous.hVal = to.max(0);
        reader.SetCurrentPosition(&windows::core::GUID::zeroed(), &at)
    }
}

#[cfg(not(windows))]
fn decode(_motion: &Arc<Motion>, _path: &std::path::Path, _ctx: &egui::Context, _clock: Option<&crate::audio::AudioStatus>) {}

/// The frame buffer's real row length in bytes, and whether its rows run bottom-up. Media
/// Foundation's video processor pads the frame (a 1080 px clip comes back 1088 px wide, rows
/// 4352 bytes apart) but still reports the unpadded stride, which would shear every row by
/// 8 px; the buffer's length gives the padded one away.
#[cfg(windows)]
fn layout(w: usize, h: usize, len: usize, reported: i32) -> (usize, bool) {
    let row = w * 4;
    let bottom_up = reported < 0;
    let reported = if reported == 0 { row as i32 } else { reported }.unsigned_abs() as usize;
    let fits = |s: usize| s >= row && len % s == 0 && (h..h + 64).contains(&(len / s));
    if fits(reported) {
        return (reported, bottom_up);
    }
    for rows in h..h + 64 {
        let s = len / rows;
        if len % rows == 0 && s >= row && s <= row + 256 {
            return (s, bottom_up);
        }
    }
    (reported.max(row), bottom_up)
}

/// Media Foundation decoding of a real cached sleeve. Needs a clip in the cache (play
/// something in the app first): `cargo test --release -- --ignored decodes_a_cached_clip`.
#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn edit_lists_are_blanked_and_nothing_else() {
        let b = |kind: &[u8; 4], body: &[u8]| [&((body.len() + 8) as u32).to_be_bytes()[..], kind, body].concat();
        let trak = b(b"trak", &[b(b"tkhd", &[0; 4]), b(b"edts", &b(b"elst", &[1; 4])), b(b"mdia", &[2; 4])].concat());
        let mut mp4 = [b(b"ftyp", b"dash"), b(b"moov", &trak), b(b"mdat", b"edts")].concat();
        drop_edit_lists(&mut mp4);
        let text = String::from_utf8_lossy(&mp4);
        assert!(text.contains("free") && text.contains("elst") && text.contains("mdia"));
        // Only the box inside a track: media data that happens to read "edts" stays.
        assert_eq!(text.matches("edts").count(), 1);
    }

    #[test]
    #[ignore]
    fn decodes_a_cached_clip() {
        let dir = crate::data_dir().join("motion");
        let path = std::fs::read_dir(&dir)
            .expect("no motion cache")
            .flatten()
            .map(|e| e.path())
            .find(|p| p.extension().is_some_and(|e| e == "mp4"))
            .expect("no cached clip");
        let ctx = egui::Context::default();
        let motion = Arc::new(Motion::new());
        let this = motion.clone();
        std::thread::spawn(move || decode(&this, &path, &ctx, None));
        let start = Instant::now();
        while motion.latest().is_none() && start.elapsed() < Duration::from_secs(15) {
            std::thread::sleep(Duration::from_millis(50));
        }
        motion.stop();
        let (_, frame) = motion.latest().expect("no frame decoded");
        assert!(frame.w > 0 && frame.h > 0, "empty frame");
        assert_eq!(frame.rgba.len(), frame.w * frame.h * 4);
    }
}


