use std::io::{Read, Seek, SeekFrom};
use std::num::NonZero;
use std::sync::Arc;
use std::time::Duration;

use rusty_opus::OpusDecoder;
use symphonia::core::codecs::CODEC_TYPE_OPUS;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::{MediaSource, MediaSourceStream};
use symphonia::core::units::{Time, TimeBase};
use symphonia::default::formats::MkvReader;

use crate::stream::StreamReader;

const RATE: u32 = 48_000;
const MAX_FRAME: usize = 5760;
const PREROLL: Duration = Duration::from_millis(80);

struct Media {
    reader: StreamReader,
    len: u64,
}

impl Read for Media {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.reader.read(buf)
    }
}

impl Seek for Media {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        self.reader.seek(pos)
    }
}

impl MediaSource for Media {
    fn is_seekable(&self) -> bool {
        true
    }
    fn byte_len(&self) -> Option<u64> {
        Some(self.len)
    }
}

pub struct OpusSource {
    format: MkvReader,
    track: u32,
    time_base: Option<TimeBase>,
    decoder: OpusDecoder,
    channels: u16,
    buf: Vec<f32>,
    pos: usize,
    skip: usize,
    duration: Option<Duration>,
}

impl OpusSource {
    pub fn new(reader: StreamReader, len: u64, duration_ms: u64) -> anyhow::Result<Self> {
        let mss = MediaSourceStream::new(Box::new(Media { reader, len }), Default::default());
        let format = MkvReader::try_new(mss, &FormatOptions::default())?;
        let track = format.tracks().iter().find(|t| t.codec_params.codec == CODEC_TYPE_OPUS).ok_or_else(|| anyhow::anyhow!("no Opus track"))?;
        let head = track.codec_params.extra_data.as_deref().unwrap_or_default();
        let channels = head.get(9).map_or(2, |&c| c as u16);
        anyhow::ensure!(matches!(channels, 1 | 2), "{channels}-channel Opus isn't supported");
        let pre_skip = head.get(10..12).map_or(0, |b| u16::from_le_bytes([b[0], b[1]]) as usize);
        let (track, time_base) = (track.id, track.codec_params.time_base);
        Ok(Self {
            format,
            track,
            time_base,
            decoder: OpusDecoder::new(RATE as i32, channels as usize).map_err(|e| anyhow::anyhow!("opus: {e:?}"))?,
            channels,
            buf: Vec::new(),
            pos: 0,
            skip: pre_skip * channels as usize,
            duration: (duration_ms > 0).then(|| Duration::from_millis(duration_ms)),
        })
    }

    fn refill(&mut self) -> bool {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(_) => return false,
            };
            if packet.track_id() != self.track {
                continue;
            }
            let ch = self.channels as usize;
            self.buf.resize(MAX_FRAME * ch, 0.0);
            match self.decoder.decode(&packet.data, MAX_FRAME, &mut self.buf) {
                Ok(n) => {
                    self.buf.truncate(n * ch);
                    let drop = self.skip.min(self.buf.len());
                    self.skip -= drop;
                    self.pos = drop;
                    if self.pos < self.buf.len() {
                        return true;
                    }
                }
                Err(e) => log::debug!("opus: {e:?}"),
            }
        }
    }
}

impl Iterator for OpusSource {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        if self.pos >= self.buf.len() && !self.refill() {
            return None;
        }
        self.pos += 1;
        Some(self.buf[self.pos - 1])
    }
}

impl rodio::Source for OpusSource {
    fn current_span_len(&self) -> Option<usize> {
        Some((self.buf.len() - self.pos).max(self.channels as usize))
    }
    fn channels(&self) -> rodio::ChannelCount {
        NonZero::new(self.channels).unwrap_or(NonZero::<u16>::MIN)
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        NonZero::new(RATE).unwrap_or(NonZero::<u32>::MIN)
    }
    fn total_duration(&self) -> Option<Duration> {
        self.duration
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        let from = pos.saturating_sub(PREROLL);
        let to = SeekTo::Time { time: Time::from(from.as_secs_f64()), track_id: Some(self.track) };
        let seeked = self.format.seek(SeekMode::Accurate, to).map_err(|e| rodio::source::SeekError::Other(Arc::new(e)))?;
        let at = self.time_base.map_or(from.as_secs_f64(), |tb| {
            let t = tb.calc_time(seeked.actual_ts);
            t.seconds as f64 + t.frac
        });
        let ahead = (pos.as_secs_f64() - at).max(0.0);
        if let Ok(d) = OpusDecoder::new(RATE as i32, self.channels as usize) {
            self.decoder = d;
        }
        self.buf.clear();
        self.pos = 0;
        self.skip = (ahead * RATE as f64) as usize * self.channels as usize;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use rodio::Source;

    struct Head<S> {
        inner: S,
        left: usize,
    }

    impl<S: Source> Iterator for Head<S> {
        type Item = f32;
        fn next(&mut self) -> Option<f32> {
            self.left = self.left.checked_sub(1)?;
            self.inner.next()
        }
    }

    impl<S: Source> Source for Head<S> {
        fn current_span_len(&self) -> Option<usize> {
            self.inner.current_span_len().map(|n| n.min(self.left))
        }
        fn channels(&self) -> rodio::ChannelCount {
            self.inner.channels()
        }
        fn sample_rate(&self) -> rodio::SampleRate {
            self.inner.sample_rate()
        }
        fn total_duration(&self) -> Option<Duration> {
            None
        }
    }

    #[tokio::test]
    #[ignore]
    async fn opus_then_aac_rate() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-opus-test"));
        let vd = api.visitor_data(false).await.unwrap();
        let http = reqwest::Client::new();
        let s = crate::innertube::audio_stream(&http, "zuO2fClon98", &vd, true).await.unwrap();
        let shared = crate::stream::Shared::new(s.size, s.duration_ms, s.loudness_db, crate::stream::Format::from_opus(s.opus), None, false);
        crate::stream::download(http.clone(), s.url, crate::innertube::UA.to_owned(), shared.clone(), None, crate::stream::Ranging::Query).await;
        let opus = crate::stream::decoder(shared, true).unwrap();
        assert_eq!(opus.sample_rate().get(), 48_000);
        let (tx, rx) = rodio::queue::queue(false);
        tx.append(Head { inner: opus, left: 48_000 * 2 });
        let one_sec = rodio::buffer::SamplesBuffer::new(std::num::NonZero::new(2).unwrap(), std::num::NonZero::new(44_100).unwrap(), vec![1.0f32; 44_100 * 2]);
        tx.append(one_sec);
        let out: Vec<f32> = rodio::source::UniformSourceIterator::new(rx, std::num::NonZero::new(2).unwrap(), std::num::NonZero::new(48_000).unwrap()).collect();
        let second = out.iter().skip(48_000 * 2).filter(|&&x| (x - 1.0).abs() < 1e-3).count() / 2;
        println!("44.1 kHz second came out as {second} frames at 48 kHz");
        assert!((second as i64 - 48_000).abs() < 200, "played at the wrong rate: {second} frames, not 48000");
    }

    #[tokio::test]
    #[ignore]
    async fn opus_stream_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-opus-test"));
        let vd = api.visitor_data(false).await.unwrap();
        let http = reqwest::Client::new();
        for id in ["zuO2fClon98", "N48TFWsBA-k"] {
            for opus in [true, false] {
                let s = crate::innertube::audio_stream(&http, id, &vd, opus).await.unwrap();
                assert_eq!(s.opus, opus);
                let shared = crate::stream::Shared::new(s.size, s.duration_ms, s.loudness_db, crate::stream::Format::from_opus(s.opus), None, false);
                crate::stream::download(http.clone(), s.url, crate::innertube::UA.to_owned(), shared.clone(), None, crate::stream::Ranging::Query).await;
                assert!(shared.error().is_none(), "{:?}", shared.error());
                let mut d = crate::stream::decoder(shared.clone(), true).unwrap();
                let (ch, rate) = (d.channels().get() as usize, d.sample_rate().get() as usize);
                let t = Instant::now();
                let (mut n, mut sq) = (0usize, 0f64);
                for x in d.by_ref() {
                    n += 1;
                    sq += (x as f64) * (x as f64);
                }
                let secs = n as f64 / (ch * rate) as f64;
                println!(
                    "{id} {}: {} kbps, {ch} ch {rate} Hz, {secs:.2} s decoded of {:.2} s, rms {:.4}, decoded in {:?}",
                    if opus { "opus" } else { "aac " },
                    s.size * 8 / s.duration_ms.max(1),
                    s.duration_ms as f64 / 1000.0,
                    (sq / n as f64).sqrt(),
                    t.elapsed()
                );
                assert!((secs - s.duration_ms as f64 / 1000.0).abs() < 0.5, "length off");
                let mut d = crate::stream::decoder(shared, true).unwrap();
                let half = Duration::from_millis(s.duration_ms / 2);
                d.try_seek(half).unwrap();
                let left = d.count() as f64 / (ch * rate) as f64;
                println!("    after seeking to {:.2} s: {left:.2} s left", half.as_secs_f64());
                assert!((left - (s.duration_ms as f64 / 1000.0 - half.as_secs_f64())).abs() < 0.5, "seek off");
            }
        }
    }
}
