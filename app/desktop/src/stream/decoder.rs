use std::io::{Read, Seek, SeekFrom};
use std::sync::Arc;
use std::time::Duration;

use super::{Format, Shared, StreamReader};

pub fn decoder(shared: Arc<Shared>, seekable: bool) -> anyhow::Result<TrackDecoder> {
    let size = shared.total;
    match shared.format {
        Format::Opus => {
            let duration_ms = shared.duration_ms;
            Ok(TrackDecoder::Opus(Box::new(crate::opus::OpusSource::new(StreamReader::new(shared), size, duration_ms)?)))
        }
        Format::Pcm { rate, channels } => Ok(TrackDecoder::Pcm(PcmSource::new(shared, rate, channels))),
        Format::Aac => {
            let builder = rodio::Decoder::builder()
                .with_data(StreamReader::new(shared.clone()))
                .with_byte_len(size)
                .with_seekable(seekable)
                .with_mime_type("audio/mp4")
                .with_hint("m4a");
            Ok(TrackDecoder::Rodio(builder.build()?))
        }
        Format::File { ref hint, ref mime } => {
            let hint = *hint;
            let mime = mime.clone();
            let builder = rodio::Decoder::builder()
                .with_data(StreamReader::new(shared))
                .with_byte_len(size)
                .with_seekable(seekable)
                .with_hint(hint);
            let builder = match mime.as_deref() {
                Some(mime) => builder.with_mime_type(mime),
                None => builder,
            };
            Ok(TrackDecoder::Rodio(builder.build()?))
        }
    }
}

pub enum TrackDecoder {
    Rodio(rodio::Decoder<StreamReader>),
    Live(rodio::Decoder<crate::radio::LiveReader>),
    Opus(Box<crate::opus::OpusSource>),
    Pcm(PcmSource),
}

impl Iterator for TrackDecoder {
    type Item = rodio::Sample;

    fn next(&mut self) -> Option<rodio::Sample> {
        match self {
            Self::Rodio(d) => d.next(),
            Self::Live(d) => d.next(),
            Self::Opus(d) => d.next(),
            Self::Pcm(d) => d.next(),
        }
    }
}

impl rodio::Source for TrackDecoder {
    fn current_span_len(&self) -> Option<usize> {
        match self {
            Self::Rodio(d) => d.current_span_len(),
            Self::Live(d) => d.current_span_len(),
            Self::Opus(d) => d.current_span_len(),
            Self::Pcm(d) => d.current_span_len(),
        }
    }
    fn channels(&self) -> rodio::ChannelCount {
        match self {
            Self::Rodio(d) => d.channels(),
            Self::Live(d) => d.channels(),
            Self::Opus(d) => d.channels(),
            Self::Pcm(d) => d.channels(),
        }
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        match self {
            Self::Rodio(d) => d.sample_rate(),
            Self::Live(d) => d.sample_rate(),
            Self::Opus(d) => d.sample_rate(),
            Self::Pcm(d) => d.sample_rate(),
        }
    }
    fn total_duration(&self) -> Option<Duration> {
        match self {
            Self::Rodio(d) => d.total_duration(),
            Self::Live(d) => d.total_duration(),
            Self::Opus(d) => d.total_duration(),
            Self::Pcm(d) => d.total_duration(),
        }
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        match self {
            Self::Rodio(d) => d.try_seek(pos),
            Self::Live(d) => d.try_seek(pos),
            Self::Opus(d) => d.try_seek(pos),
            Self::Pcm(d) => d.try_seek(pos),
        }
    }
}

pub struct PcmSource {
    reader: StreamReader,
    rate: u32,
    channels: u16,
    duration: Option<Duration>,
}

impl PcmSource {
    fn new(shared: Arc<Shared>, rate: u32, channels: u16) -> Self {
        let duration_ms = shared.duration_ms;
        Self { reader: StreamReader::new(shared), rate, channels, duration: (duration_ms > 0).then(|| Duration::from_millis(duration_ms)) }
    }
}

impl Iterator for PcmSource {
    type Item = rodio::Sample;
    fn next(&mut self) -> Option<rodio::Sample> {
        let mut buf = [0u8; 2];
        if self.reader.read_exact(&mut buf).is_err() {
            return None;
        }
        Some(i16::from_le_bytes(buf) as f32 / 32768.0)
    }
}

impl rodio::Source for PcmSource {
    fn current_span_len(&self) -> Option<usize> { None }
    fn channels(&self) -> rodio::ChannelCount { std::num::NonZeroU16::new(self.channels).unwrap_or(std::num::NonZeroU16::MIN) }
    fn sample_rate(&self) -> rodio::SampleRate { std::num::NonZeroU32::new(self.rate).unwrap_or(std::num::NonZeroU32::MIN) }
    fn total_duration(&self) -> Option<Duration> { self.duration }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        let frame = (pos.as_secs_f64() * self.rate as f64) as u64;
        let byte = frame * self.channels as u64 * 2;
        self.reader.seek(SeekFrom::Start(byte)).map_err(|e| rodio::source::SeekError::Other(std::sync::Arc::new(e)))?;
        Ok(())
    }
}
