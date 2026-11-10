use sha2::{Digest, Sha256};

const API: &str = "https://sponsor.ajay.app/api/skipSegments";
const CATEGORIES: &str = r#"["music_offtopic","sponsor","selfpromo","interaction"]"#;

#[derive(Clone, Copy, Debug)]
pub struct Segment {
    pub start: f64,
    pub end: f64,
    pub video_duration: f64,
}

impl Segment {
    pub fn fits(&self, duration: f64) -> bool {
        self.video_duration <= 0.0 || (self.video_duration - duration).abs() < 3.0
    }
}

#[derive(serde::Deserialize)]
struct Video {
    #[serde(rename = "videoID")]
    video_id: String,
    segments: Vec<Raw>,
}

#[derive(serde::Deserialize)]
struct Raw {
    segment: [f64; 2],
    #[serde(rename = "actionType")]
    action: String,
    #[serde(rename = "videoDuration", default)]
    duration: f64,
}

pub async fn segments(http: &reqwest::Client, video_id: &str) -> anyhow::Result<Vec<Segment>> {
    let hash = Sha256::digest(video_id.as_bytes());
    let url = url::Url::parse_with_params(&format!("{API}/{:02x}{:02x}", hash[0], hash[1]), [("categories", CATEGORIES), ("actionType", "skip")])?;
    let resp = http.get(url).send().await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(Vec::new());
    }
    let videos: Vec<Video> = resp.error_for_status()?.json().await?;
    let mut out: Vec<Segment> = videos
        .into_iter()
        .filter(|v| v.video_id == video_id)
        .flat_map(|v| v.segments)
        .filter(|s| s.action == "skip" && s.segment[1] > s.segment[0] + 0.5)
        .map(|s| Segment { start: s.segment[0], end: s.segment[1], video_duration: s.duration })
        .collect();
    out.sort_by(|a, b| a.start.total_cmp(&b.start));
    Ok(out)
}
