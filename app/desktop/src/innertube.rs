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

pub async fn audio_stream(http: &reqwest::Client, video_id: &str, visitor_data: &str, opus: bool) -> anyhow::Result<Stream> {
    let resp = vision_player(http, video_id, visitor_data).await?;
    let formats = resp["streamingData"]["adaptiveFormats"].as_array().map(Vec::as_slice).unwrap_or_default();
    let itags: &[u64] = if opus { &[251, 140, 139] } else { &[140, 139] };
    itags
        .iter()
        .find_map(|itag| {
            let f = formats.iter().find(|f| f["itag"] == *itag)?;
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
