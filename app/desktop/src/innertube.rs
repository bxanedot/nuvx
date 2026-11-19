use serde_json::{Value, json};

pub const UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

pub struct Stream {
    pub url: String,
    pub size: u64,
    pub duration_ms: u64,
    pub loudness_db: Option<f32>,
    pub opus: bool,
}

pub fn loudness(resp: &Value, format: &Value) -> Option<f32> {
    format["loudnessDb"].as_f64().map(|v| v as f32).or_else(|| {
        let a = &resp["playerConfig"]["audioConfig"];
        let track = a["trackAbsoluteLoudnessLkfs"].as_f64().or_else(|| a["perceptualLoudnessDb"].as_f64())?;
        Some((track - a["loudnessTargetLkfs"].as_f64().unwrap_or(-14.0)) as f32)
    })
}

pub async fn album_queue(http: &reqwest::Client, playlist_id: &str) -> anyhow::Result<Value> {
    const VERSION: &str = "1.73.21";
    let body = json!({
        "context": {"client": {
            "clientName": "ANDROID_VR",
            "clientVersion": VERSION,
            "deviceMake": "Pico",
            "deviceModel": "A8110",
            "osName": "Android",
            "osVersion": "10",
            "androidSdkVersion": 29,
            "hl": "en",
            "gl": "US",
        }},
        "playlistId": playlist_id,
        "contentCheckOk": true,
        "racyCheckOk": true,
    });
    Ok(http
        .post("https://youtubei.googleapis.com/youtubei/v1/next?prettyPrint=false")
        .header("User-Agent", format!("com.google.android.apps.youtube.vr.pico/{VERSION} (Linux; U; Android 10; A8110 Build/QKQ1) gzip"))
        .header("X-YouTube-Client-Name", "28")
        .header("X-YouTube-Client-Version", VERSION)
        .timeout(std::time::Duration::from_secs(8))
        .json(&body)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

async fn vision_player(http: &reqwest::Client, video_id: &str, visitor_data: &str) -> anyhow::Result<Value> {
    let body = json!({
        "context": {"client": {
            "clientName": "VISIONOS",
            "clientVersion": "1.02",
            "deviceMake": "Apple",
            "deviceModel": "RealityDevice17,1",
            "userAgent": UA,
            "osName": "visionOS",
            "osVersion": "26.5.23O471",
            "hl": "en",
            "gl": "US",
            "visitorData": visitor_data,
        }},
        "videoId": video_id,
        "contentCheckOk": true,
        "racyCheckOk": true,
    });
    let resp: Value = http
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", UA)
        .header("X-YouTube-Client-Name", "101")
        .header("X-YouTube-Client-Version", "1.02")
        .header("X-Goog-Visitor-Id", visitor_data)
        .header("Origin", "https://www.youtube.com")
        .timeout(std::time::Duration::from_secs(8))
        .json(&body)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let status = &resp["playabilityStatus"];
    if status["status"] != "OK" {
        anyhow::bail!(
            "{}: {}",
            status["status"].as_str().unwrap_or("UNPLAYABLE"),
            status["reason"].as_str().unwrap_or("no reason given")
        );
    }
    Ok(resp)
}

pub async fn video_stream(http: &reqwest::Client, video_id: &str, visitor_data: &str, height: u32) -> anyhow::Result<(String, u64)> {
    let resp = vision_player(http, video_id, visitor_data).await?;
    let formats = resp["streamingData"]["adaptiveFormats"].as_array().map(Vec::as_slice).unwrap_or_default();
    video_itags(height)
        .iter()
        .find_map(|itag| {
            let f = formats.iter().find(|f| f["itag"] == *itag)?;
            Some((f["url"].as_str()?.to_owned(), f["contentLength"].as_str()?.parse().ok()?))
        })
        .ok_or_else(|| anyhow::anyhow!("no H.264 video stream for {video_id}"))
}

fn video_itags(height: u32) -> Vec<u64> {
    const LADDER: [(u32, u64); 5] = [(1080, 137), (720, 136), (480, 135), (360, 134), (240, 133)];
    let (fit, over): (Vec<_>, Vec<_>) = LADDER.iter().partition(|(h, _)| *h <= height);
    fit.iter().chain(over.iter().rev()).map(|(_, i)| *i).collect()
}

#[cfg(test)]
#[test]
fn video_itags_fit_the_height_then_go_up() {
    assert_eq!(video_itags(480), [135, 134, 133, 136, 137]);
    assert_eq!(video_itags(100), [133, 134, 135, 136, 137]);
}

/// The format for `itag`. Multi-language videos list one per audio track under the same itag,
/// so take the source-language one over dubbed or auto-dubbed tracks, then YouTube's default.
pub fn audio_format(formats: &[Value], itag: u64) -> Option<&Value> {
    let same: Vec<&Value> = formats.iter().filter(|f| f["itag"] == itag).collect();
    same.iter().find(|f| is_original_audio(f)).or_else(|| same.iter().find(|f| f["audioTrack"]["audioIsDefault"] == true)).or(same.first()).copied()
}

// shortcut: reads the plain `acont=original` URL tag and the English track label (requests send hl=en), not the base64 `xtags` field; decode that if YouTube drops the URL tag.
fn is_original_audio(f: &Value) -> bool {
    let tagged = ["url", "signatureCipher"].iter().filter_map(|k| f[k].as_str()).any(|s| s.contains("acont%3Doriginal") || s.contains("acont%253Doriginal") || s.contains("acont=original"));
    tagged || f["audioTrack"]["displayName"].as_str().is_some_and(|n| n.to_lowercase().contains("original"))
}

pub async fn audio_stream(http: &reqwest::Client, video_id: &str, visitor_data: &str, opus: bool) -> anyhow::Result<Stream> {
    let resp = vision_player(http, video_id, visitor_data).await?;
    let formats = resp["streamingData"]["adaptiveFormats"].as_array().map(Vec::as_slice).unwrap_or_default();
    let itags: &[u64] = if opus { &[251, 140, 139] } else { &[140, 139] };
    itags
        .iter()
        .find_map(|itag| {
            let f = audio_format(formats, *itag)?;
            log::info!("visionOS: {video_id} itag {itag} ({} kbps)", f["averageBitrate"].as_u64().or(f["bitrate"].as_u64()).unwrap_or(0) / 1000);
            Some(Stream {
                url: f["url"].as_str()?.to_owned(),
                size: f["contentLength"].as_str()?.parse().ok()?,
                duration_ms: f["approxDurationMs"].as_str().and_then(|d| d.parse().ok()).unwrap_or(0),
                loudness_db: loudness(&resp, f),
                opus: *itag == 251,
            })
        })
        .ok_or_else(|| anyhow::anyhow!("no audio stream in visionOS player response"))
}

#[cfg(test)]
mod audio_track_tests {
    use super::audio_format;
    use serde_json::json;

    #[test]
    fn the_original_track_beats_a_dubbed_one_listed_first() {
        let formats = [
            json!({"itag": 251, "url": "https://x/videoplayback?xtags=acont%3Ddubbed-auto%3Alang%3Dde", "audioTrack": {"displayName": "German", "audioIsDefault": true}}),
            json!({"itag": 251, "url": "https://x/videoplayback?xtags=acont%3Doriginal%3Alang%3Den", "audioTrack": {"displayName": "English original", "audioIsDefault": false}}),
            json!({"itag": 140, "url": "https://x/aac"}),
        ];
        assert!(audio_format(&formats, 251).unwrap()["url"].as_str().unwrap().contains("original"));
    }

    #[test]
    fn a_ciphered_original_is_found_too() {
        let formats = [
            json!({"itag": 140, "signatureCipher": "s=AB&url=https%3A%2F%2Fx%2Fvp%3Fxtags%3Dacont%253Ddubbed%253Alang%253Dfr", "audioTrack": {"displayName": "French", "audioIsDefault": true}}),
            json!({"itag": 140, "signatureCipher": "s=CD&url=https%3A%2F%2Fx%2Fvp%3Fxtags%3Dacont%253Doriginal%253Alang%253Dja", "audioTrack": {"displayName": "Japanese", "audioIsDefault": false}}),
        ];
        assert!(audio_format(&formats, 140).unwrap()["signatureCipher"].as_str().unwrap().starts_with("s=CD"));
    }

    #[test]
    fn without_an_original_marker_it_falls_back_to_the_default_then_the_first() {
        let unmarked = [
            json!({"itag": 251, "url": "https://x/a", "audioTrack": {"displayName": "Spanish", "audioIsDefault": false}}),
            json!({"itag": 251, "url": "https://x/b", "audioTrack": {"displayName": "English", "audioIsDefault": true}}),
        ];
        assert_eq!(audio_format(&unmarked, 251).unwrap()["url"], "https://x/b");
        let single = [json!({"itag": 251, "url": "https://x/only"})];
        assert_eq!(audio_format(&single, 251).unwrap()["url"], "https://x/only");
        assert!(audio_format(&single, 140).is_none());
    }
}
