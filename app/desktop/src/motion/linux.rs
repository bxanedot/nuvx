//! Canvas loops on Linux through GStreamer, which WebKitGTK (already required by the app) brings along.
//! The pipeline paces frames by its own clock; looping is a flushing seek back to the start.

use super::Motion;
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;
use std::sync::atomic::Ordering;
use std::time::Duration;

/// Plays `path` in a loop until the motion is stopped or the clip can't be decoded. Returns whether any frame showed.
pub(super) fn play_loop(motion: &Motion, path: &std::path::Path, ctx: &egui::Context) -> bool {
    if let Err(e) = gst::init() {
        log::info!("motion: GStreamer unavailable: {e}");
        return false;
    }
    let Ok(uri) = url::Url::from_file_path(path) else { return false };
    let pipeline = gst::Pipeline::new();
    let made = (|| -> Result<gst_app::AppSink, gst::glib::BoolError> {
        let source = gst::ElementFactory::make("uridecodebin").property("uri", uri.as_str()).build()?;
        let convert = gst::ElementFactory::make("videoconvert").build()?;
        let caps = gst::Caps::builder("video/x-raw").field("format", "RGBA").build();
        let sink = gst_app::AppSink::builder().caps(&caps).max_buffers(2).drop(true).sync(true).build();
        pipeline.add_many([&source, &convert, sink.upcast_ref()])?;
        convert.link(&sink)?;
        // uridecodebin only knows its pads once it has looked at the file: wire up the video one.
        let convert_weak = convert.downgrade();
        source.connect_pad_added(move |_, pad| {
            let Some(convert) = convert_weak.upgrade() else { return };
            let is_video = pad.current_caps().and_then(|c| c.structure(0).map(|s| s.name().starts_with("video/"))).unwrap_or(false);
            if let Some(sink_pad) = convert.static_pad("sink").filter(|p| is_video && !p.is_linked()) {
                let _ = pad.link(&sink_pad);
            }
        });
        Ok(sink)
    })();
    let sink = match made {
        Ok(sink) => sink,
        Err(e) => {
            log::info!("motion: GStreamer is missing an element: {e}");
            return false;
        }
    };
    let mut shown = false;
    if pipeline.set_state(gst::State::Playing).is_ok() {
        let mut loops = 0;
        while !motion.stop.load(Ordering::Relaxed) && loops < 100 {
            match sink.try_pull_sample(gst::ClockTime::from_mseconds(200)) {
                Some(sample) => {
                    if let Some(frame) = rgba(&sample) {
                        shown = true;
                        motion.publish(ctx, frame.0, frame.1, frame.2);
                    }
                }
                None if sink.is_eos() => {
                    loops += 1;
                    if pipeline.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT, gst::ClockTime::ZERO).is_err() {
                        break;
                    }
                }
                None => {
                    if pipeline.bus().and_then(|b| b.pop_filtered(&[gst::MessageType::Error])).is_some() {
                        break; // undecodable clip: the static cover stays
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
            }
        }
    }
    let _ = pipeline.set_state(gst::State::Null); // releases the decoder and the file
    shown
}

/// Width, height and tightly packed RGBA rows from a sample (GStreamer may pad each row).
fn rgba(sample: &gst::Sample) -> Option<(usize, usize, Vec<u8>)> {
    let s = sample.caps()?.structure(0)?;
    let (w, h) = (s.get::<i32>("width").ok()? as usize, s.get::<i32>("height").ok()? as usize);
    let buffer = sample.buffer()?;
    let map = buffer.map_readable().ok()?;
    pack(map.as_slice(), w, h)
}

/// Copies `h` rows of `w * 4` bytes out of a buffer whose rows may carry padding.
pub(super) fn pack(data: &[u8], w: usize, h: usize) -> Option<(usize, usize, Vec<u8>)> {
    let row = w.checked_mul(4)?;
    if w == 0 || h == 0 || data.len() < row * h {
        return None;
    }
    let stride = data.len() / h;
    let mut out = Vec::with_capacity(row * h);
    for y in 0..h {
        out.extend_from_slice(&data[y * stride..y * stride + row]);
    }
    Some((w, h, out))
}
