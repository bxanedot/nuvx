use serde_json::Value;
use unicode_normalization::UnicodeNormalization;

const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;

pub struct Signature {
    pub width: usize,
    pub height: usize,
    pub rgba: Vec<u8>,
}

pub fn endpoint(raw: &str) -> String {
    let Some(start) = raw.find("http") else { return String::new() };
    let rest = &raw[start..];
    if !(rest.starts_with("http://") || rest.starts_with("https://")) {
        return String::new();
    }
    let end = rest.find(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '"' | '\'' | '\\')).unwrap_or(rest.len());
    rest[..end].trim_end_matches(['/', '.', ',', ';', ':']).to_owned()
}

pub fn request_url(endpoint: &str, encoded: &str) -> String {
    let path = endpoint.split_once("://").map_or(endpoint, |(_, rest)| rest);
    let path = path.split('?').next().unwrap_or(path).trim_end_matches('/');
    if endpoint.contains("{artist}") {
        endpoint.replace("{artist}", encoded)
    } else if !path.contains('/') {
        format!("{}/v1/signatures?artist={encoded}&type=handwritten", endpoint.trim_end_matches('/'))
    } else {
        let sep = if endpoint.contains('?') { '&' } else { '?' };
        format!("{endpoint}{sep}artist={encoded}&type=handwritten")
    }
}

pub fn key(name: &str) -> String {
    name.nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .flat_map(char::to_lowercase)
        .filter(|c| c.is_alphanumeric())
        .collect()
}

pub async fn lookup(http: &reqwest::Client, endpoint: &str, name: &str) -> Option<Signature> {
    if endpoint.is_empty() || key(name).is_empty() {
        return None;
    }
    let encoded: String = url::form_urlencoded::byte_serialize(name.as_bytes()).collect();
    let url = request_url(endpoint, &encoded);
    let body = match http.get(&url).send().await.and_then(|r| r.error_for_status()) {
        Ok(r) => limited_body(r, MAX_RESPONSE_BYTES).await?,
        Err(e) => {
            log::debug!("artist signature: the service did not answer ({e})");
            return None;
        }
    };
    let root: Value = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(e) => {
            log::debug!("artist signature: the answer is not JSON ({e})");
            return None;
        }
    };
    let asset = match pick_url(&root) {
        Some(a) => a,
        None => {
            log::debug!("artist signature: nothing in the answer for '{name}' can be drawn");
            return None;
        }
    };
    let bytes = match http.get(&asset).send().await.and_then(|r| r.error_for_status()) {
        Ok(r) => limited_body(r, MAX_IMAGE_BYTES).await?,
        Err(e) => {
            log::debug!("artist signature: the asset did not download ({e})");
            return None;
        }
    };
    let mut reader = image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format().ok()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(2048);
    limits.max_alloc = Some(32 * 1024 * 1024);
    reader.limits(limits);
    let img = match reader.decode() {
        Ok(i) => i.to_rgba8(),
        Err(e) => {
            log::debug!("artist signature: the asset is not an image ({e})");
            return None;
        }
    };
    let img = if img.width() > 720 {
        let h = (u32::from(img.height()) * 720 / img.width()).max(1) as u32;
        image::imageops::resize(&img, 720, h, image::imageops::FilterType::Lanczos3)
    } else {
        img
    };
    Some(ink(&img))
}

async fn limited_body(mut response: reqwest::Response, max_bytes: usize) -> Option<Vec<u8>> {
    if response.content_length().is_some_and(|size| size > max_bytes as u64) {
        return None;
    }
    let capacity = response.content_length().unwrap_or(0).min(max_bytes as u64) as usize;
    let mut body = Vec::with_capacity(capacity);
    while let Some(chunk) = response.chunk().await.ok()? {
        if !append_limited(&mut body, &chunk, max_bytes) {
            return None;
        }
    }
    Some(body)
}

fn append_limited(body: &mut Vec<u8>, chunk: &[u8], max_bytes: usize) -> bool {
    if body.len().checked_add(chunk.len()).is_none_or(|size| size > max_bytes) {
        return false;
    }
    body.extend_from_slice(chunk);
    true
}

fn ink(img: &image::RgbaImage) -> Signature {
    let mut rgba = img.as_raw().clone();
    let transparent = rgba.chunks_exact(4).any(|p| p[3] < 250);
    for p in rgba.chunks_exact_mut(4) {
        let a = if transparent {
            p[3]
        } else {
            let luma = (u32::from(p[0]) * 299 + u32::from(p[1]) * 587 + u32::from(p[2]) * 114) / 1000;
            (255 - luma) as u8
        };
        p.copy_from_slice(&[255, 255, 255, a]);
    }
    Signature { width: img.width() as usize, height: img.height() as usize, rgba }
}

pub fn pick_url(root: &Value) -> Option<String> {
    let signatures = root.get("signatures")?.as_array()?;
    let mut best: Option<(u32, u64, String)> = None;
    for s in signatures {
        let Some(asset) = s.get("asset") else { continue };
        let Some(url) = asset.get("url").and_then(Value::as_str).filter(|u| u.starts_with("https://")) else { continue };
        let format = asset.get("format").and_then(Value::as_str).map(str::to_ascii_lowercase);
        let format_score = match format.as_deref() {
            Some("svg") => 2,
            Some("png") => 1,
            _ => continue,
        };
        let license = match s.pointer("/license/status").and_then(Value::as_str) {
            Some("known") => 2,
            Some("requires_review") => 1,
            _ => 0,
        };
        let verified = match s.get("verification").and_then(Value::as_str) {
            Some("artist_verified") => 3,
            Some("verified") => 2,
            Some("unverified") => 1,
            _ => 0,
        };
        let width = asset.get("width").and_then(Value::as_u64).unwrap_or(0);
        let height = asset.get("height").and_then(Value::as_u64).unwrap_or(0);
        let panoramic = u32::from(height > 0 && width.saturating_mul(10) >= height.saturating_mul(14));
        let score = license * 1000 + format_score * 100 + verified * 10 + panoramic;
        let Some(raster) = (if format.as_deref() == Some("svg") { wikimedia_raster(url) } else { Some(url.split('?').next().unwrap_or(url).to_owned()) }) else { continue };
        let area = width.saturating_mul(height);
        if best.as_ref().is_none_or(|(s, a, _)| (score, area) > (*s, *a)) {
            best = Some((score, area, raster));
        }
    }
    best.map(|(_, _, url)| url)
}

pub fn wikimedia_raster(url: &str) -> Option<String> {
    let path = url.split('?').next().unwrap_or(url);
    let prefix = "https://upload.wikimedia.org/wikipedia/commons/";
    let rel = path.strip_prefix(prefix)?;
    if rel.starts_with("thumb/") {
        return None;
    }
    let file = rel.rsplit('/').next()?;
    Some(format!("{prefix}thumb/{rel}/960px-{file}.png"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pasted_sentence_leaves_its_address() {
        assert_eq!(endpoint("try this https://sig.example.net/api, thanks!"), "https://sig.example.net/api");
        assert_eq!(endpoint("https://sig.example.net/."), "https://sig.example.net");
        assert_eq!(endpoint("no link here"), "");
    }

    #[test]
    fn requests_follow_the_three_shapes() {
        assert_eq!(request_url("https://sig.example.net/api/{artist}", "Slay%20yyyter"), "https://sig.example.net/api/Slay%20yyyter");
        assert_eq!(request_url("https://sig.example.net", "A"), "https://sig.example.net/v1/signatures?artist=A&type=handwritten");
        assert_eq!(request_url("https://sig.example.net/api?x=1", "A"), "https://sig.example.net/api?x=1&artist=A&type=handwritten");
    }

    #[test]
    fn keys_ignore_accents_case_and_punctuation() {
        assert_eq!(key("Beyoncé"), "beyonce");
        assert_eq!(key("Tyler, The Creator"), "tylerthecreator");
    }

    #[test]
    fn the_best_drawable_signature_wins() {
        let root = serde_json::json!({"signatures": [
            {"asset": {"url": "https://x/a.png", "format": "png", "width": 100, "height": 40}, "license": {"status": "known"}, "verification": "verified"},
            {"asset": {"url": "https://x/b.svg", "format": "svg", "width": 100, "height": 40}, "license": {"status": "requires_review"}, "verification": "unverified"},
            {"asset": {"url": "https://x/c.jpg", "format": "jpeg", "width": 999, "height": 400}, "license": {"status": "known"}, "verification": "artist_verified"}
        ]});
        assert_eq!(pick_url(&root).as_deref(), Some("https://x/a.png"));
    }

    #[test]
    fn extreme_signature_dimensions_do_not_wrap() {
        let root = serde_json::json!({"signatures": [
            {"asset": {"url": "https://x/huge.png", "format": "png", "width": u64::MAX / 10 + 1, "height": 1}, "license": {"status": "known"}, "verification": "verified"},
            {"asset": {"url": "https://x/wide.png", "format": "png", "width": 14, "height": 10}, "license": {"status": "known"}, "verification": "verified"}
        ]});
        assert_eq!(pick_url(&root).as_deref(), Some("https://x/huge.png"));
    }

    #[test]
    fn wikimedia_marks_get_a_thumbnail_url() {
        assert_eq!(
            wikimedia_raster("https://upload.wikimedia.org/wikipedia/commons/a/ab/Signature.svg").as_deref(),
            Some("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Signature.svg/960px-Signature.svg.png"),
        );
        assert_eq!(wikimedia_raster("https://example.com/Signature.svg"), None);
    }

    #[test]
    fn a_transparent_mark_keeps_its_alpha() {
        let img = image::RgbaImage::from_pixel(2, 1, image::Rgba([12, 30, 200, 128]));
        let s = ink(&img);
        assert_eq!(s.rgba, vec![255, 255, 255, 128, 255, 255, 255, 128]);
    }

    #[test]
    fn a_scan_on_paper_takes_its_darkness_as_ink() {
        let dark = ink(&image::RgbaImage::from_pixel(1, 1, image::Rgba([0, 0, 0, 255])));
        assert_eq!(dark.rgba, vec![255, 255, 255, 255]);
        let paper = ink(&image::RgbaImage::from_pixel(1, 1, image::Rgba([255, 255, 255, 255])));
        assert_eq!(paper.rgba, vec![255, 255, 255, 0]);
    }

    #[test]
    fn oversized_remote_bodies_are_rejected_before_append() {
        let mut body = vec![1, 2];
        assert!(append_limited(&mut body, &[3, 4], 4));
        assert!(!append_limited(&mut body, &[5], 4));
        assert_eq!(body, [1, 2, 3, 4]);
    }
}
