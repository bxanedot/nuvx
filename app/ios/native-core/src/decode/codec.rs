


use super::DecodeError;
use symphonia::core::codecs::audio::well_known::CODEC_ID_OPUS;
use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::formats::MediaInfo;




pub(super) fn alac_bit_depth(mut cookie: &[u8]) -> Option<u32> {
    for atom in [b"frma", b"alac"] {
        if cookie.get(4..8) == Some(atom.as_slice()) {
            cookie = cookie.get(12..)?;
        }
    }
    if !matches!(cookie.len(), 24 | 48) {
        return None;
    }
    match cookie.get(5).copied()? {
        depth @ (16 | 20 | 24 | 32) => Some(u32::from(depth)),
        _ => None,
    }
}

pub(super) fn open_audio_decoder(
    audio: &symphonia::core::codecs::audio::AudioCodecParameters,
) -> Result<Box<dyn AudioDecoder>, DecodeError> {
    let opts = AudioDecoderOptions::default();
    if audio.codec == CODEC_ID_OPUS {
        return super::opus::OpusDecoder::open(audio, true);
    }

    let codecs = symphonia::default::get_codecs();



    log::debug!(
        "audio params extra={}B ch={:?} rate={:?} profile={:?}",
        audio.extra_data.as_ref().map(|d| d.len()).unwrap_or(0),
        audio.channels.as_ref().map(|c| c.count()),
        audio.sample_rate,
        audio.profile,
    );

    #[cfg(target_vendor = "apple")]
    if is_he_aac(audio)
        || (crate::diagnostics::prefer_apple_aac()
            && audio.codec == symphonia::core::codecs::audio::well_known::CODEC_ID_AAC)
    {
        return super::apple_aac::AppleAacDecoder::open(audio);
    }
    match codecs.make_audio_decoder(audio, &opts) {
        Ok(decoder) => Ok(decoder),
        Err(error) => {
            #[cfg(target_vendor = "apple")]
            if audio.codec == symphonia::core::codecs::audio::well_known::CODEC_ID_AAC {
                return super::apple_aac::AppleAacDecoder::open(audio);
            }
            Err(DecodeError(format!("decoder: {error}")))
        }
    }
}



pub(super) fn is_he_aac(audio: &symphonia::core::codecs::audio::AudioCodecParameters) -> bool {
    audio
        .extra_data
        .as_ref()
        .is_some_and(|d| is_he_aac_config(d.as_ref()))
}

pub(super) fn is_he_aac_config(extra: &[u8]) -> bool {
    if extra.is_empty() {
        return false;
    }
    match aac_audio_object_type(extra) {
        Some(5 | 29) => true,
        Some(2) => has_sbr_sync(extra),
        _ => has_sbr_sync(extra),
    }
}


pub(super) fn has_sbr_sync(extra: &[u8]) -> bool {
    if extra.len() < 2 {
        return false;
    }
    let mut acc = 0u32;
    let mut bits = 0u32;
    for &b in extra {
        acc = (acc << 8) | u32::from(b);
        bits += 8;
        while bits >= 11 {
            if (acc >> (bits - 11)) & 0x7FF == 0x2B7 {
                return true;
            }
            bits -= 1;
        }
    }
    false
}

pub(super) fn aac_audio_object_type(extra: &[u8]) -> Option<u8> {
    if extra.is_empty() {
        return None;
    }
    let aot = extra[0] >> 3;
    if aot != 31 {
        return Some(aot);
    }
    if extra.len() < 2 {
        return None;
    }
    Some(32 + (((extra[0] & 0x07) << 3) | (extra[1] >> 5)))
}


#[cfg(test)]
pub(super) fn lc_stereo_asc(sample_rate: u32) -> [u8; 2] {
    let freq_idx: u16 = match sample_rate {
        96_000 => 0,
        88_200 => 1,
        64_000 => 2,
        48_000 => 3,
        44_100 => 4,
        32_000 => 5,
        24_000 => 6,
        22_050 => 7,
        16_000 => 8,
        12_000 => 9,
        11_025 => 10,
        8_000 => 11,
        _ => 4,
    };
    let bits: u16 = (2 << 11) | (freq_idx << 7) | (2 << 3);
    [(bits >> 8) as u8, bits as u8]
}

pub(super) fn codec_label(
    audio: &symphonia::core::codecs::audio::AudioCodecParameters,
) -> String {
    use symphonia::core::codecs::audio::well_known::*;





    if audio.codec == CODEC_ID_OPUS {
        return "Opus".into();
    }
    if audio.codec == CODEC_ID_FLAC {
        return "FLAC".into();
    }
    if audio.codec == CODEC_ID_ALAC {
        return "ALAC".into();
    }
    if matches!(
        audio.codec,
        CODEC_ID_PCM_S32LE
            | CODEC_ID_PCM_S32LE_PLANAR
            | CODEC_ID_PCM_S32BE
            | CODEC_ID_PCM_S32BE_PLANAR
            | CODEC_ID_PCM_S24LE
            | CODEC_ID_PCM_S24LE_PLANAR
            | CODEC_ID_PCM_S24BE
            | CODEC_ID_PCM_S24BE_PLANAR
            | CODEC_ID_PCM_S16LE
            | CODEC_ID_PCM_S16LE_PLANAR
            | CODEC_ID_PCM_S16BE
            | CODEC_ID_PCM_S16BE_PLANAR
            | CODEC_ID_PCM_S8
            | CODEC_ID_PCM_S8_PLANAR
            | CODEC_ID_PCM_U32LE
            | CODEC_ID_PCM_U32LE_PLANAR
            | CODEC_ID_PCM_U32BE
            | CODEC_ID_PCM_U32BE_PLANAR
            | CODEC_ID_PCM_U24LE
            | CODEC_ID_PCM_U24LE_PLANAR
            | CODEC_ID_PCM_U24BE
            | CODEC_ID_PCM_U24BE_PLANAR
            | CODEC_ID_PCM_U16LE
            | CODEC_ID_PCM_U16LE_PLANAR
            | CODEC_ID_PCM_U16BE
            | CODEC_ID_PCM_U16BE_PLANAR
            | CODEC_ID_PCM_U8
            | CODEC_ID_PCM_U8_PLANAR
    ) {
        return "PCM".into();
    }
    if matches!(
        audio.codec,
        CODEC_ID_PCM_F32LE
            | CODEC_ID_PCM_F32LE_PLANAR
            | CODEC_ID_PCM_F32BE
            | CODEC_ID_PCM_F32BE_PLANAR
            | CODEC_ID_PCM_F64LE
            | CODEC_ID_PCM_F64LE_PLANAR
            | CODEC_ID_PCM_F64BE
            | CODEC_ID_PCM_F64BE_PLANAR
    ) {
        return "PCM Float".into();
    }
    if audio.codec == CODEC_ID_PCM_ALAW {
        return "G.711 A-law".into();
    }
    if audio.codec == CODEC_ID_PCM_MULAW {
        return "G.711 μ-law".into();
    }
    match audio.codec {
        CODEC_ID_MP1 => "MP1",
        CODEC_ID_MP2 => "MP2",
        CODEC_ID_MP3 => "MP3",
        CODEC_ID_AAC => "AAC",
        CODEC_ID_VORBIS => "Vorbis",
        _ => "Unknown",
    }.into()
}











pub(super) fn container_duration_secs(media: &MediaInfo) -> Option<f64> {
    let (Some(time_base), Some(duration)) = (media.time_base, media.duration) else {
        return None;
    };
    let seconds = time_base.calc_duration_saturating(duration).as_secs_f64();
    (seconds.is_finite() && seconds > 0.0).then_some(seconds)
}
