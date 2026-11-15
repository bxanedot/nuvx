use super::*;
use serde_json::Value;

const PLACEHOLDER_SIZE: u64 = 512 * 1024 * 1024;
const RJ_AGENT: &str = "Radio Javan/5.0.0 (Desktop) com.radioJavan.rj.desktop";

pub(super) async fn jiosaavn(http: &reqwest::Client, track: &Track) -> anyhow::Result<ResolvedStream> {
    let artist = track.artist_line();
    let query = format!("{} {artist}", track.title);
    let search = jio_api(http, "search.getResults", &[("q", &query), ("n", "25"), ("p", "1")]).await?;
    let row = rows(&search)
        .into_iter()
        .find(|row| {
            let info = &row["more_info"];
            crate::hires::matching::metadata_matches(
                &track.title, &artist, track.duration,
                text(row, "title").unwrap_or_default(), &jio_artist(row),
                duration(text(info, "duration")),
            )
        })
        .ok_or_else(|| anyhow::anyhow!("JioSaavn has no matching track"))?;
    let encrypted = text(&row["more_info"], "encrypted_media_url")
        .ok_or_else(|| anyhow::anyhow!("JioSaavn returned no media token"))?;
    let token = jio_api(http, "song.generateAuthToken", &[("url", encrypted), ("bitrate", "320")]).await?;
    let url = text(&token, "auth_url")
        .filter(|url| url.starts_with("https://"))
        .ok_or_else(|| anyhow::anyhow!("JioSaavn returned no HTTPS stream"))?;
    let duration_ms = duration(text(&row["more_info"], "duration")).or(track.duration).unwrap_or_default() as u64 * 1000;
    media_stream(http, url, duration_ms).await
}

pub(super) async fn radiojavan(http: &reqwest::Client, track: &Track) -> anyhow::Result<ResolvedStream> {
    let artist = track.artist_line();
    let query = format!("{} {artist}", track.title);
    let search = rj_api(http, "search", &[("query", &query)]).await?;
    let row = rows(&search)
        .into_iter()
        .find(|row| crate::hires::matching::metadata_matches(
            &track.title, &artist, track.duration,
            text(row, "song").or_else(|| text(row, "title")).unwrap_or_default(),
            text(row, "artist").unwrap_or_default(), duration(text(row, "duration")),
        ))
        .ok_or_else(|| anyhow::anyhow!("Radio Javan has no matching track"))?;
    let id = text(row, "id").ok_or_else(|| anyhow::anyhow!("Radio Javan returned no track id"))?;
    let details = rj_api(http, "mp3", &[("id", id)]).await?;
    let details = rows(&details).into_iter().next().unwrap_or(&details);
    let url = text(details, "hq_link")
        .or_else(|| text(details, "lq_link"))
        .filter(|url| valid_https(url))
        .ok_or_else(|| anyhow::anyhow!("Radio Javan returned no HTTPS stream"))?;
    let duration_ms = duration(text(details, "duration")).or(track.duration).unwrap_or_default() as u64 * 1000;
    media_stream(http, url, duration_ms).await
}

async fn jio_api(http: &reqwest::Client, call: &str, args: &[(&str, &str)]) -> anyhow::Result<Value> {
    let mut query = vec![("__call", call), ("_format", "json"), ("_marker", "0"), ("api_version", "4"), ("ctx", "android")];
    query.extend_from_slice(args);
    get_json(http, "https://www.jiosaavn.com/api.php", &query, &[]).await
}

async fn rj_api(http: &reqwest::Client, path: &str, query: &[(&str, &str)]) -> anyhow::Result<Value> {
    get_json(http, &format!("https://rj-deskcloud.com/api2/{path}"), query,
        &[("x-rj-user-agent", RJ_AGENT), ("User-Agent", RJ_AGENT)]).await
}

async fn get_json(http: &reqwest::Client, url: &str, query: &[(&str, &str)], headers: &[(&str, &str)]) -> anyhow::Result<Value> {
    let mut request = http.get(url).query(query);
    for (name, value) in headers { request = request.header(*name, *value); }
    let body = request.send().await?.error_for_status()?.text().await?;
    Ok(serde_json::from_str(&body)?)
}

fn rows(root: &Value) -> Vec<&Value> {
    root.as_array().map(|items| items.iter().collect()).or_else(|| {
        ["mp3s", "results", "data", "songs"].iter().find_map(|key| root.get(*key).and_then(Value::as_array).map(|items| items.iter().collect()))
    }).unwrap_or_else(|| root.get("mp3").or_else(|| root.get("song")).or_else(|| root.get("id").map(|_| root)).into_iter().collect())
}

fn text<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    value.get(key).and_then(Value::as_str).filter(|text| !text.trim().is_empty())
}

fn jio_artist(row: &Value) -> String {
    let artists = row["more_info"]["artistMap"]["primary_artists"].as_array()
        .into_iter().flatten().filter_map(|artist| text(artist, "name")).collect::<Vec<_>>();
    if artists.is_empty() { text(&row["more_info"], "singers").unwrap_or_default().to_owned() }
    else { artists.join(", ") }
}

fn duration(raw: Option<&str>) -> Option<u32> {
    let raw = raw?;
    if let Some((minutes, seconds)) = raw.split_once(':') {
        return Some(minutes.parse::<u32>().ok()?.saturating_mul(60).saturating_add(seconds.parse().ok()?));
    }
    raw.parse::<f64>().ok().filter(|seconds| *seconds > 0.0).map(|seconds| seconds as u32)
}

fn valid_https(url: &str) -> bool {
    url::Url::parse(url).is_ok_and(|url| url.scheme() == "https" && url.host_str().is_some_and(|host| !host.is_empty()) && url.username().is_empty())
}

async fn media_stream(http: &reqwest::Client, url: &str, duration_ms: u64) -> anyhow::Result<ResolvedStream> {
    anyhow::ensure!(valid_https(url), "source returned a URL that isn't HTTPS");
    let path = url.split('?').next().unwrap_or(url);
    let ext = path.rsplit('.').next().unwrap_or_default().to_ascii_lowercase();
    let (hint, mime) = match ext.as_str() {
        "mp3" => ("mp3", "audio/mpeg"),
        "m4a" | "mp4" => ("m4a", "audio/mp4"),
        _ => anyhow::bail!("source returned an unsupported audio format"),
    };
    let size = http.head(url).send().await.ok().and_then(|response| response.content_length()).filter(|size| *size > 0).unwrap_or(PLACEHOLDER_SIZE);
    Ok(ResolvedStream {
        url: url.to_owned(), size, duration_ms, user_agent: "Mozilla/5.0".to_owned(), loudness_db: None,
        format: stream::Format::File { hint, mime: Some(mime.to_owned()) }, info: None,
        ranging: stream::Ranging::Header, data: None,
    })
}

#[cfg(test)]
mod tests {
    use super::{duration, rows, valid_https};
    use serde_json::json;

    #[test]
    fn accepts_provider_row_shapes_and_rejects_unsafe_media_urls() {
        let response = json!({"mp3s": [{"id": "1"}]});
        assert_eq!(rows(&response).len(), 1);
        assert_eq!(duration(Some("3:21")), Some(201));
        assert!(valid_https("https://media.example/song.m4a"));
        assert!(!valid_https("http://media.example/song.mp3"));
        assert!(!valid_https("https://user@media.example/song.mp3"));
    }
}
