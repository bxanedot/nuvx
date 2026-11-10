use serde_json::Value;

const SEARCH: &str = "https://api.tidal.com/v1/search";
const TOKEN: &str = "vNVdglQOjFJJGG2U";

pub async fn cover(http: &reqwest::Client, title: &str, artist: &str, album: Option<&str>, is_album: bool) -> Option<String> {
    let (kind, query) = match (is_album, album.filter(|a| !a.is_empty())) {
        (true, _) => ("ALBUMS", format!("{title} {artist}")),
        (false, Some(a)) => ("TRACKS", format!("{a} {artist} {title}")),
        (false, None) => ("TRACKS", format!("{artist} {title}")),
    };
    let url = url::Url::parse_with_params(SEARCH, &[("query", query.as_str()), ("limit", "10"), ("types", kind), ("countryCode", "US")]).ok()?;
    let v: Value = http.get(url).header("X-Tidal-Token", TOKEN).send().await.ok()?.error_for_status().ok()?.json().await.ok()?;
    let items = v.pointer(if is_album { "/albums/items" } else { "/tracks/items" })?.as_array()?;
    items.iter().find_map(|item| {
        let name = item.get("title")?.as_str()?;
        let credits: Vec<&str> = item.get("artists")?.as_array()?.iter().filter_map(|a| a.get("name")?.as_str()).collect();
        if crate::apple::norm(name) != crate::apple::norm(title) || !crate::apple::artists_match(artist, &credits.join(", ")) {
            return None;
        }
        let id = if is_album { item.get("videoCover") } else { item.pointer("/album/videoCover") }?.as_str()?;
        clip_url(id)
    })
}

fn clip_url(id: &str) -> Option<String> {
    let parts: Vec<&str> = id.split('-').collect();
    (parts.len() == 5).then(|| format!("https://resources.tidal.com/videos/{}/1280x1280.mp4", parts.join("/")))
}

pub fn sized(url: &str, width: u32) -> String {
    let side = [320, 640, 750, 1280].into_iter().find(|&s| s >= width).unwrap_or(1280);
    match url.rsplit_once('/') {
        Some((dir, file)) if file.ends_with(".mp4") && dir.starts_with("https://resources.tidal.com/") => format!("{dir}/{side}x{side}.mp4"),
        _ => url.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cover_ids_become_clip_urls_at_the_size_asked_for() {
        let id = "e9e126a9-97b6-4f99-b7b6-c242cb6eb31d";
        assert_eq!(clip_url(id).unwrap(), "https://resources.tidal.com/videos/e9e126a9/97b6/4f99/b7b6/c242cb6eb31d/1280x1280.mp4");
        assert_eq!(sized(&clip_url(id).unwrap(), 600), "https://resources.tidal.com/videos/e9e126a9/97b6/4f99/b7b6/c242cb6eb31d/640x640.mp4");
        assert_eq!(clip_url("not-an-id"), None);
        assert_eq!(sized("https://example.com/a.m3u8", 600), "https://example.com/a.m3u8");
    }
}

