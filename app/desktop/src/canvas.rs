//! Song canvases beyond Apple Music and Tidal: Nuvx Community Canvas (canvas.nuvx.lol) and Spotify Canvas.
//! Same contracts and matching as Android's `CommunityCanvas.kt` and `SpotifyCanvas.kt`.

use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const COMMUNITY: &str = "https://canvas.nuvx.lol";
const INDEX_TTL: Duration = Duration::from_secs(30 * 60);
const SEARCH: &str = "https://api.spotify.com/v1/search";
const CANVAZ: &str = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases";

/// The key the canvas upload Worker files a song under (`core/worker/src/canvas.ts` `canvasKey`).
pub fn community_key(title: &str, artist: &str) -> String {
    let clean = |v: &str| v.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    let artist = clean(artist);
    let artist = artist.strip_suffix(" - topic").unwrap_or(&artist).to_owned();
    format!("{}|{artist}", clean(title))
}

/// A YouTube Music video id, as used for the community file name: no path tricks, nothing but id characters.
fn is_video_id(id: &str) -> bool {
    (6..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// The community index: `title|artist` → video id. Unreadable replies (an outage page, junk) count as empty.
pub fn parse_index(body: &str) -> HashMap<String, String> {
    serde_json::from_str::<HashMap<String, Value>>(body).unwrap_or_default().into_iter()
        .filter_map(|(k, v)| Some((k, v.as_str().filter(|id| is_video_id(id))?.to_owned())))
        .collect()
}

/// The community clip for this song: its exact video id first, then the uploaded title and artist.
pub fn community_match(index: &HashMap<String, String>, video_id: Option<&str>, title: &str, artist: &str) -> Option<String> {
    let id = video_id.filter(|id| is_video_id(id) && index.values().any(|v| v == id)).map(str::to_owned)
        .or_else(|| index.get(&community_key(title, artist)).cloned())?;
    Some(format!("{COMMUNITY}/{id}.mp4"))
}

static INDEX: Mutex<Option<(Instant, HashMap<String, String>)>> = Mutex::new(None);

/// Looks the song up in the downloaded list; asking the server per song would reveal every play.
pub async fn community(http: &reqwest::Client, video_id: Option<&str>, title: &str, artist: &str) -> Option<String> {
    let fresh = INDEX.lock().ok().and_then(|g| g.as_ref().filter(|(at, _)| at.elapsed() < INDEX_TTL).map(|(_, i)| i.clone()));
    let index = match fresh {
        Some(index) => index,
        None => {
            let body = async { http.get(format!("{COMMUNITY}/index.json")).timeout(Duration::from_secs(10)).send().await.ok()?.error_for_status().ok()?.text().await.ok() }.await;
            let index = body.as_deref().map(parse_index).unwrap_or_default();
            // A failed fetch is cached too, so an outage costs one request per half hour, not one per song.
            if let Ok(mut g) = INDEX.lock() {
                *g = Some((Instant::now(), index.clone()));
            }
            index
        }
    };
    community_match(&index, video_id, title, artist)
}

/// Spotify credits the same song: title equal after normalising, and every credited artist we know is on it.
pub fn spotify_match(got_title: &str, got_artists: &[&str], title: &str, artist: &str) -> bool {
    crate::apple::norm(got_title) == crate::apple::norm(title) && crate::apple::artists_match(artist, &got_artists.join(", "))
}

fn first_match(search: &Value, title: &str, artist: &str) -> Option<String> {
    search.pointer("/tracks/items")?.as_array()?.iter().find_map(|t| {
        let name = t.get("name")?.as_str()?;
        let artists: Vec<&str> = t.get("artists")?.as_array()?.iter().filter_map(|a| a.get("name")?.as_str()).collect();
        spotify_match(name, &artists, title, artist).then(|| t.get("uri")?.as_str().map(str::to_owned)).flatten()
    })
}

/// `EntityCanvazRequest { entities: [ { entity_uri } ] }` as protobuf bytes.
pub fn canvaz_request(track_uri: &str) -> Vec<u8> {
    fn field(tag: u8, bytes: &[u8]) -> Vec<u8> {
        let mut out = vec![tag];
        let mut n = bytes.len();
        loop {
            let b = (n & 0x7f) as u8;
            n >>= 7;
            if n == 0 {
                out.push(b);
                break;
            }
            out.push(b | 0x80);
        }
        out.extend_from_slice(bytes);
        out
    }
    field(0x0a, &field(0x0a, track_uri.as_bytes()))
}

/// The clip URL in a canvaz-cache reply. Only Spotify's own canvas CDN, only `.cnvs.mp4`.
pub fn canvaz_url(reply: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(reply);
    let start = text.find("https://")?;
    let rest = &text[start..];
    // The string is followed by the next protobuf tag, which may be any byte: the URL ends at its suffix.
    let url = &rest[..rest.find(".cnvs.mp4")? + ".cnvs.mp4".len()];
    if url.contains(|c: char| c.is_control() || c.is_whitespace() || c == '"' || c == '\'') {
        return None;
    }
    let host = url::Url::parse(url).ok()?;
    let ok_host = host.host_str().is_some_and(|h| h == "canvaz.scdn.co" || h.ends_with(".scdn.co") || h.ends_with(".spotifycdn.com"));
    (host.scheme() == "https" && ok_host && url.ends_with(".cnvs.mp4")).then(|| url.to_owned())
}

/// The Spotify canvas for this song, through the web-player token the Spotify import already uses
/// (the saved Spotify sign-in when there is one, anonymous otherwise). No new sign-in.
pub async fn spotify(http: &reqwest::Client, token: &str, title: &str, artist: &str, album: Option<&str>) -> Option<String> {
    let q = [Some(title), Some(artist), album].into_iter().flatten().collect::<Vec<_>>().join(" ");
    let search: Value = http.get(SEARCH).query(&[("q", q.as_str()), ("type", "track"), ("limit", "10")])
        .bearer_auth(token).timeout(Duration::from_secs(10)).send().await.ok()?.error_for_status().ok()?.json().await.ok()?;
    let uri = first_match(&search, title, artist)?;
    let reply = http.post(CANVAZ).bearer_auth(token).header("Content-Type", "application/protobuf").header("Accept", "application/protobuf")
        .body(canvaz_request(&uri)).timeout(Duration::from_secs(10)).send().await.ok()?.error_for_status().ok()?.bytes().await.ok()?;
    canvaz_url(&reply)
}

/// Where a canvas can come from, in the order they are tried (Android's default: community first, Spotify last).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Source {
    Community,
    Apple,
    Tidal,
    Spotify,
}

/// The sources worth asking: community needs a song (albums have no community clips), Spotify needs a sign-in.
pub fn order(song: bool, spotify_signed_in: bool) -> Vec<Source> {
    [Source::Community, Source::Apple, Source::Tidal, Source::Spotify].into_iter()
        .filter(|s| match s {
            Source::Community => song,
            Source::Spotify => spotify_signed_in,
            _ => true,
        })
        .collect()
}

static SPOTIFY_TOKEN: Mutex<Option<(Instant, String)>> = Mutex::new(None);

/// A signed-in web-player token, reused for 45 minutes. None without a saved Spotify sign-in: an anonymous
/// token gets no canvases, so asking would only cost requests.
pub async fn spotify_token(http: &reqwest::Client) -> Option<String> {
    let cookie = crate::transfer::saved_cookie(&crate::data_dir())?;
    if let Some((at, token)) = SPOTIFY_TOKEN.lock().ok()?.as_ref()
        && at.elapsed() < Duration::from_secs(45 * 60)
    {
        return Some(token.clone());
    }
    let token = crate::transfer::spotify::web_token(http, Some(&cookie)).await.inspect_err(|e| log::debug!("spotify canvas: no token: {e}")).ok()?;
    if let Ok(mut g) = SPOTIFY_TOKEN.lock() {
        *g = Some((Instant::now(), token.clone()));
    }
    Some(token)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn sources_follow_androids_order_and_skip_what_cannot_answer() {
        use Source::*;
        assert_eq!(order(true, true), vec![Community, Apple, Tidal, Spotify]);
        assert_eq!(order(true, false), vec![Community, Apple, Tidal], "no Spotify sign-in, no Spotify requests");
        assert_eq!(order(false, true), vec![Apple, Tidal, Spotify], "albums have no community clips");
    }

    #[test]
    fn community_keys_match_the_upload_worker() {
        assert_eq!(community_key("  Bohemian   Rhapsody ", "Queen - Topic"), "bohemian rhapsody|queen");
        assert_eq!(community_key("A", "B - TOPIC"), "a|b");
        assert_eq!(community_key("Dancing Queen", "ABBA"), "dancing queen|abba");
    }

    #[test]
    fn community_prefers_the_exact_video_id_then_title_and_artist() {
        let index = parse_index(r#"{"bohemian rhapsody|queen":"fJ9rUzIMcZQ","other|x":"abcdefABC12"}"#);
        assert_eq!(community_match(&index, Some("abcdefABC12"), "Unrelated", "Nobody").as_deref(), Some("https://canvas.nuvx.lol/abcdefABC12.mp4"));
        assert_eq!(community_match(&index, Some("notInIndex1"), "Bohemian Rhapsody", "Queen - Topic").as_deref(), Some("https://canvas.nuvx.lol/fJ9rUzIMcZQ.mp4"));
        assert_eq!(community_match(&index, None, "Somebody to Love", "Queen"), None);
    }

    #[test]
    fn malformed_or_hostile_indexes_give_nothing() {
        assert!(parse_index("<!DOCTYPE html><html>503</html>").is_empty(), "an outage page");
        assert!(parse_index("[1,2,3]").is_empty());
        let index = parse_index(r#"{"a|b":"../../etc/passwd","c|d":"x y","e|f":42,"g|h":"okVideoId1"}"#);
        assert_eq!(index.len(), 1, "only real video ids survive: {index:?}");
        assert_eq!(community_match(&index, Some("../../etc/passwd"), "a", "b"), None);
    }

    #[test]
    fn spotify_needs_the_same_title_and_artists() {
        assert!(spotify_match("Blinding Lights", &["The Weeknd"], "Blinding Lights", "The Weeknd"));
        assert!(!spotify_match("Blinding Lights - Cover", &["Some Band"], "Blinding Lights", "The Weeknd"));
        assert!(!spotify_match("Blinding Lights", &["Someone Else"], "Blinding Lights", "The Weeknd"));
        let search = json!({ "tracks": { "items": [
            { "name": "Blinding Lights", "artists": [{ "name": "Tribute Band" }], "uri": "spotify:track:wrong" },
            { "name": "Blinding Lights", "artists": [{ "name": "The Weeknd" }], "uri": "spotify:track:right" },
        ]}});
        assert_eq!(first_match(&search, "Blinding Lights", "The Weeknd").as_deref(), Some("spotify:track:right"));
        assert_eq!(first_match(&json!({ "error": 401 }), "Blinding Lights", "The Weeknd"), None);
    }

    #[test]
    fn canvaz_request_and_reply_are_read_strictly() {
        let req = canvaz_request("spotify:track:abc");
        assert_eq!(req, [&[0x0a, 19, 0x0a, 17][..], b"spotify:track:abc"].concat());
        let reply = [&b"\x0a\x40\x0a\x05abcde\x12"[..], b"\x2fhttps://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4\x2a\x00"].concat();
        assert_eq!(canvaz_url(&reply).as_deref(), Some("https://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4"));
        assert_eq!(canvaz_url(b"\x12\x20https://evil.example.com/a.cnvs.mp4"), None, "only Spotify's CDN");
        assert_eq!(canvaz_url(b"\x12\x20http://canvaz.scdn.co/a.cnvs.mp4"), None, "only https");
        assert_eq!(canvaz_url(b"\x12\x20https://canvaz.scdn.co/a.jpg"), None, "only canvas clips");
        assert_eq!(canvaz_url(b""), None);
    }

    /// Live: `cargo test canvas::tests::spotify_live -- --ignored --nocapture`.
    #[tokio::test]
    #[ignore]
    async fn spotify_live() {
        let http = reqwest::Client::new();
        let cookie = crate::transfer::saved_cookie(&crate::data_dir());
        println!("saved Spotify sign-in: {}", cookie.is_some());
        let token = crate::transfer::spotify::web_token(&http, cookie.as_deref()).await.expect("token");
        println!("token: {} chars", token.len());
        let search = http.get(SEARCH).query(&[("q", "Blinding Lights The Weeknd"), ("type", "track"), ("limit", "10")]).bearer_auth(&token).send().await.unwrap();
        println!("search: {}", search.status());
        let v: Value = search.json().await.unwrap_or_default();
        let uri = first_match(&v, "Blinding Lights", "The Weeknd").or(Some("spotify:track:0VjIjW4GlUZAMYd2vXMi3b".into()));
        println!("uri: {uri:?}");
        if let Some(uri) = uri {
            let r = http.post(CANVAZ).bearer_auth(&token).header("Content-Type", "application/protobuf").header("Accept", "application/protobuf").body(canvaz_request(&uri)).send().await.unwrap();
            let status = r.status();
            let bytes = r.bytes().await.unwrap();
            println!("canvaz: {status} {} bytes -> {:?}", bytes.len(), canvaz_url(&bytes));
        }
    }
}

#[cfg(test)]
mod live {
    /// `cargo test canvas::live -- --ignored --nocapture`: the community index (today: an outage page) falls through to Apple.
    #[tokio::test]
    #[ignore]
    async fn community_then_apple_for_a_real_song() {
        let http = crate::proxy::http();
        let community = super::community(&http, Some("4NRXx6U8ABQ"), "Blinding Lights", "The Weeknd").await;
        println!("community: {community:?}");
        let apple = crate::apple::album_motion(&http, "After Hours", "The Weeknd").await.and_then(|m| m.square);
        println!("apple album motion: {apple:?}");
        assert!(community.is_some() || apple.is_some(), "some source must answer");
    }
}
