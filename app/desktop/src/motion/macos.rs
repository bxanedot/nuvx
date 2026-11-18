//! Canvas loops on macOS through AVFoundation, the system decoder: an AVAssetReader hands out BGRA frames,
//! paced here by their presentation times. Each loop starts a fresh reader; everything is released on return.

use super::Motion;
use objc2::rc::Retained;
use objc2::runtime::AnyObject;
use objc2_av_foundation::{AVAssetReader, AVAssetReaderStatus, AVAssetReaderTrackOutput, AVMediaTypeVideo, AVURLAsset};
use objc2_core_video::{
    CVPixelBufferGetBaseAddress, CVPixelBufferGetBytesPerRow, CVPixelBufferGetHeight, CVPixelBufferGetWidth,
    CVPixelBufferLockBaseAddress, CVPixelBufferLockFlags, CVPixelBufferUnlockBaseAddress, kCVPixelBufferPixelFormatTypeKey,
    kCVPixelFormatType_32BGRA,
};
use objc2_foundation::{NSDictionary, NSNumber, NSString, NSURL};
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

/// Plays `path` in a loop until the motion is stopped or the clip can't be decoded. Returns whether any frame showed.
pub(super) fn play_loop(motion: &Motion, path: &std::path::Path, ctx: &egui::Context) -> bool {
    let mut shown = false;
    for _ in 0..100 {
        if motion.stop.load(Ordering::Relaxed) {
            break;
        }
        match objc2::rc::autoreleasepool(|_| unsafe { play_once(motion, path, ctx) }) {
            Some(any) => shown |= any,
            None => break, // undecodable: the static cover stays
        }
    }
    shown
}

/// One pass through the clip. None when it can't be opened or decoded at all.
unsafe fn play_once(motion: &Motion, path: &std::path::Path, ctx: &egui::Context) -> Option<bool> {
    unsafe {
        let url = NSURL::fileURLWithPath(&NSString::from_str(path.to_str()?));
        let asset = AVURLAsset::URLAssetWithURL_options(&url, None);
        #[allow(deprecated)] // the async loader needs a run loop this thread doesn't have; the file is local
        let tracks = asset.tracksWithMediaType(AVMediaTypeVideo?);
        if tracks.count() == 0 {
            return None;
        }
        let track = tracks.objectAtIndex(0);
        let reader: Retained<AVAssetReader> = AVAssetReader::assetReaderWithAsset_error(&asset).ok()?;
        let key: &NSString = &*(kCVPixelBufferPixelFormatTypeKey as *const _ as *const NSString); // toll-free bridged
        let format = NSNumber::numberWithUnsignedInt(kCVPixelFormatType_32BGRA);
        let value: &AnyObject = &format;
        let settings = NSDictionary::<NSString, AnyObject>::from_slices(&[key], &[value]);
        let output = AVAssetReaderTrackOutput::assetReaderTrackOutputWithTrack_outputSettings(&track, Some(&settings));
        output.setAlwaysCopiesSampleData(false);
        if !reader.canAddOutput(&output) {
            return None;
        }
        reader.addOutput(&output);
        if !reader.startReading() {
            return None;
        }
        let start = Instant::now();
        let mut shown = false;
        while !motion.stop.load(Ordering::Relaxed) {
            let Some(sample) = output.copyNextSampleBuffer() else { break };
            let due = sample.presentation_time_stamp().seconds();
            if due.is_finite() && due > 0.0 {
                let wait = Duration::from_secs_f64(due).saturating_sub(start.elapsed());
                std::thread::sleep(wait.min(Duration::from_secs(1)));
            }
            let Some(image) = sample.image_buffer() else { continue };
            if CVPixelBufferLockBaseAddress(&image, CVPixelBufferLockFlags::ReadOnly) != 0 {
                continue;
            }
            let (w, h, stride) = (CVPixelBufferGetWidth(&image), CVPixelBufferGetHeight(&image), CVPixelBufferGetBytesPerRow(&image));
            let base = CVPixelBufferGetBaseAddress(&image) as *const u8;
            let frame = (!base.is_null() && stride >= w * 4).then(|| bgra_to_rgba(std::slice::from_raw_parts(base, stride * h), w, h, stride));
            CVPixelBufferUnlockBaseAddress(&image, CVPixelBufferLockFlags::ReadOnly);
            if let Some(rgba) = frame {
                shown = true;
                motion.publish(ctx, w, h, rgba);
            }
        }
        if reader.status() == AVAssetReaderStatus::Reading {
            reader.cancelReading();
        }
        if reader.status() == AVAssetReaderStatus::Failed && !shown {
            return None;
        }
        Some(shown)
    }
}

/// Packed RGBA from BGRA rows of `stride` bytes.
pub(super) fn bgra_to_rgba(data: &[u8], w: usize, h: usize, stride: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        for px in data[y * stride..y * stride + w * 4].chunks_exact(4) {
            out.extend_from_slice(&[px[2], px[1], px[0], px[3]]);
        }
    }
    out
}
