mod matching;
mod parsing;
mod search;
mod album;
mod playlist;
mod home;
mod artist;
pub use artist::artist;
pub use home::{home, home_more, mood_art, mood_page, moods, podcasts, HomePage, PODCASTS};
#[cfg(test)]
use home::parse_moods;
pub use playlist::{playlist, playlist_more, playlist_start};
#[cfg(test)]
use playlist::parse_playlist;
pub use album::{album, paired_song, searched_song};
#[cfg(test)]
use album::{album_songs, parse_album, parse_paired_song, swap_songs};
pub use search::{forget_search, search_cards, search_songs, search_tracks_signed_in, suggestions, youtube_search, Filter, Suggestion};
use search::search_as;
#[cfg(test)]
use search::{parse_suggestions, youtube_videos};
use parsing::*;
pub use matching::{cover_song, video_of};
use matching::{same_song, same_title, song_title, without_artist};

use serde_json::{Value, json};

use crate::backend::{ArtistPage, Collection};
use crate::model::{Card, Chip, Link, Mood, MoodPage, MoodSection, Shelf, ShelfKind, Target, Thumb, Track};
use crate::ytmapi::Api;

fn header(two: &Value) -> Option<&Value> {
    let c = &two["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0];
    [&c["musicResponsiveHeaderRenderer"], &c["musicEditablePlaylistDetailHeaderRenderer"]["header"]["musicResponsiveHeaderRenderer"]]
        .into_iter()
        .find(|h| !h.is_null())
}

pub async fn radio(api: &Api, video_id: &str, auth: bool, limit: usize) -> anyhow::Result<Vec<Track>> {
    let body = json!({
        "playlistId": format!("RDAMVM{video_id}"),
        "params": "wAEB8gECeAE%3D",
        "enablePersistentPlaylistPanel": true,
        "isAudioOnly": true,
        "tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
    });
    let v = api.call("next", body, auth).await?;
    let panel = &v["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]
        ["tabRenderer"]["content"]["musicQueueRenderer"]["content"]["playlistPanelRenderer"];
    let (mut items, mut token) = panel_page(panel);
    while radio_tracks(&items).count() < limit {
        let Some(t) = token.take() else { break };
        let body = json!({ "continuation": t, "enablePersistentPlaylistPanel": true, "isAudioOnly": true });
        let v = api.call("next", body, auth).await?;
        let (more, next) = panel_page(&v["continuationContents"]["playlistPanelContinuation"]);
        if more.is_empty() {
            break;
        }
        items.extend(more);
        token = next;
    }
    Ok(radio_tracks(&items).cloned().collect())
}

fn radio_tracks(items: &[(Track, bool)]) -> impl Iterator<Item = &Track> {
    let songs = items.iter().any(|(_, video)| !video);
    items.iter().filter(move |(_, video)| !songs || !video).map(|(t, _)| t)
}

pub async fn server_queue(api: &Api) -> anyhow::Result<Option<ServerQueue>> {
    let body = json!({"watchNextType": "WATCH_NEXT_TYPE_GET_QUEUE", "queueContextParams": "", "enablePersistentPlaylistPanel": true, "isAudioOnly": true});
    let v = api.call_signed_in("next", body).await?;
    Ok(parse_server_queue(&v))
}

pub struct ServerQueue {
    pub tracks: Vec<Track>,
    pub index: usize,
    pub watched_at: u64,
}

fn parse_server_queue(v: &Value) -> Option<ServerQueue> {
    let queue = &v["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["musicQueueRenderer"];
    let (mut tracks, mut index) = (Vec::new(), None);
    for i in arr(&queue["content"]["playlistPanelRenderer"]["contents"]) {
        let wrapped = &i["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"];
        let r = if wrapped.is_null() { &i["playlistPanelVideoRenderer"] } else { wrapped };
        let Some(t) = panel_item(r) else { continue };
        if r["selected"].as_bool() == Some(true) {
            index = Some(tracks.len());
        }
        tracks.push(t);
    }
    let watched_at = queue["musicQueueConfig"]["musicQueueRestorationInfo"]["lastWatchTimestamp"]["seconds"].as_str().and_then(|s| s.parse().ok()).unwrap_or(0);
    Some(ServerQueue { index: index?, tracks, watched_at })
}

fn panel_page(panel: &Value) -> (Vec<(Track, bool)>, Option<String>) {
    let tracks = arr(&panel["contents"])
        .iter()
        .filter_map(|i| {
            let wrapped = &i["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"];
            let r = if wrapped.is_null() { &i["playlistPanelVideoRenderer"] } else { wrapped };
            Some((panel_item(r)?, is_video(r)))
        })
        .collect();
    let token = panel["continuations"][0]["nextRadioContinuationData"]["continuation"].as_str().map(str::to_owned);
    (tracks, token)
}

pub struct Charts {
    pub top: Vec<Track>,
    pub playlists: Vec<Card>,
    pub artists: Vec<String>,
}

pub async fn charts(api: &Api) -> anyhow::Result<Charts> {
    let body = json!({ "browseId": "FEmusic_charts", "params": "sgYPRkVtdXNpY19leHBsb3Jl", "formData": { "selectedValues": ["US"] } });
    Ok(parse_charts(&api.call("browse", body, false).await?))
}

fn parse_charts(v: &Value) -> Charts {
    let mut c = Charts { top: Vec::new(), playlists: Vec::new(), artists: Vec::new() };
    let sections = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    for s in arr(sections) {
        for i in arr(&s["musicCarouselShelfRenderer"]["contents"]).iter().filter(|i| !is_video(i)) {
            if let Some(card) = two_row(&i["musicTwoRowItemRenderer"]) {
                if matches!(card.target, Target::Playlist(_)) {
                    c.playlists.push(card);
                }
                continue;
            }
            let r = &i["musicResponsiveListItemRenderer"];
            if let Some(Card { target: Target::Artist(id), .. }) = list_card(r) {
                c.artists.push(id);
            } else if c.top.len() < 100
                && let Some(t) = list_item(r)
            {
                c.top.push(t);
            }
        }
    }
    c
}

pub async fn new_albums(api: &Api, auth: bool) -> anyhow::Result<Vec<Card>> {
    let v = api.call("browse", json!({ "browseId": "FEmusic_new_releases_albums" }), auth).await?;
    let sections = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    Ok(grid_cards(arr(sections)))
}

fn grid_cards(sections: &[Value]) -> Vec<Card> {
    sections
        .iter()
        .flat_map(|s| arr(&s["gridRenderer"]["items"]))
        .filter_map(|i| two_row(&i["musicTwoRowItemRenderer"]))
        .collect()
}

async fn saved(api: &Api, token: &str) -> anyhow::Result<Vec<Card>> {
    let v = api.call_signed_in("browse", json!({ "continuation": token })).await?;
    let sections = arr(&v["continuationContents"]["sectionListContinuation"]["contents"]);
    let mut cards = grid_cards(sections);
    let mut token = sections.iter().find_map(|s| grid_next(&s["gridRenderer"]));
    for _ in 0..100 {
        let Some(t) = token.take() else { break };
        let v = match api.call_signed_in("browse", json!({ "continuation": t })).await {
            Ok(v) => v,
            Err(e) => {
                log::warn!("library page: {e}");
                break;
            }
        };
        let (more, next) = grid_continuation(&v);
        if more.is_empty() {
            break;
        }
        cards.extend(more);
        token = next;
    }
    Ok(cards)
}

fn grid_continuation(v: &Value) -> (Vec<Card>, Option<String>) {
    let items = &v["onResponseReceivedActions"][0]["appendContinuationItemsAction"]["continuationItems"];
    if !items.is_null() {
        return (two_rows(arr(items)), item_token(arr(items)));
    }
    let grid = &v["continuationContents"]["gridContinuation"];
    (two_rows(arr(&grid["items"])), grid_next(grid))
}

fn grid_next(grid: &Value) -> Option<String> {
    continuation(grid).or_else(|| item_token(arr(&grid["items"])))
}

fn two_rows(items: &[Value]) -> Vec<Card> {
    items.iter().filter_map(|i| two_row(&i["musicTwoRowItemRenderer"])).collect()
}

fn item_token(items: &[Value]) -> Option<String> {
    items.iter().find_map(|i| i["continuationItemRenderer"]["continuationEndpoint"]["continuationCommand"]["token"].as_str().map(str::to_owned))
}

pub async fn saved_playlists(api: &Api) -> anyhow::Result<Vec<Card>> {
    saved(api, "4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D").await
}

pub async fn saved_albums(api: &Api) -> anyhow::Result<Vec<Card>> {
    saved(api, "4qmFsgIoEhRGRW11c2ljX2xpa2VkX2FsYnVtcxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D").await
}

pub async fn account(api: &Api) -> anyhow::Result<(Option<String>, Option<String>)> {
    let v = api.call_signed_in("account/account_menu", json!({})).await?;
    let h = find_key(&v, "activeAccountHeaderRenderer").ok_or_else(|| anyhow::anyhow!("no account header"))?;
    let name = text(&h["accountName"]);
    let photo = arr(&h["accountPhoto"]["thumbnails"]).last().and_then(|t| t["url"].as_str()).map(str::to_owned);
    log::info!("account menu: name {}, photo {}", name.is_some(), photo.is_some());
    Ok((name, photo))
}

#[derive(Clone, Debug, PartialEq)]
pub struct Channel {
    pub name: String,
    pub handle: Option<String>,
    pub has_channel: bool,
    pub page_id: Option<String>,
}

pub async fn channels(api: &Api) -> anyhow::Result<Vec<Channel>> {
    let v = api.call_as_google_account("account/accounts_list", json!({})).await?;
    let list = parse_channels(&v);
    log::info!("account switcher: {} channel(s)", list.len());
    anyhow::ensure!(!list.is_empty(), "the account switcher listed no channels");
    Ok(list)
}

fn parse_channels(v: &Value) -> Vec<Channel> {
    fn items<'a>(v: &'a Value, out: &mut Vec<&'a Value>) {
        match v {
            Value::Object(m) => m.iter().for_each(|(k, x)| if k == "accountItem" { out.push(x) } else { items(x, out) }),
            Value::Array(a) => a.iter().for_each(|x| items(x, out)),
            _ => {}
        }
    }
    let plain = |v: &Value| v["simpleText"].as_str().map(str::to_owned).or_else(|| text(v)).map(|s| s.trim().to_owned()).filter(|s| !s.is_empty());
    let mut found = Vec::new();
    items(v, &mut found);
    let listed: Vec<(bool, Option<String>, Channel)> = found
        .into_iter()
        .filter_map(|i| {
            let tokens = arr(&i["serviceEndpoint"]["selectActiveIdentityEndpoint"]["supportedTokens"]);
            let datasync = tokens.iter().find_map(|t| t["datasyncIdToken"]["datasyncIdToken"].as_str()).and_then(|d| d.split_once("||"));
            let page_id = tokens.iter().find_map(|t| t["pageIdToken"]["pageId"].as_str()).map(str::to_owned);
            let login = datasync.map(|(a, b)| if b.is_empty() { a } else { b }).filter(|l| !l.is_empty()).map(str::to_owned);
            let handle = plain(&i["channelHandle"]).or_else(|| plain(&i["accountByline"]).filter(|b| b.starts_with('@')));
            let has_channel = i["hasChannel"].as_bool().or_else(|| tokens.iter().find_map(|t| t["accountStateToken"]["hasChannel"].as_bool())).unwrap_or(true);
            let channel = Channel { name: plain(&i["accountName"])?, handle, has_channel, page_id };
            Some((i["isSelected"].as_bool().unwrap_or(false), login, channel))
        })
        .collect();
    let Some(own) = listed.iter().position(|(sel, _, c)| *sel && c.page_id.is_none()) else {
        return Vec::new();
    };
    let login = listed[own].1.clone();
    let alone = listed.iter().filter(|(_, _, c)| c.page_id.is_none()).count() == 1;
    let mut out = vec![listed[own].2.clone()];
    for (_, l, c) in listed {
        let theirs = match (&login, &l) {
            (Some(own), Some(l)) => own == l,
            _ => alone,
        };
        if c.page_id.is_some() && theirs && !out.iter().any(|o| o.page_id == c.page_id) {
            out.push(c);
        }
    }
    out
}

fn find_key<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    match v {
        Value::Object(m) => m.get(key).or_else(|| m.values().find_map(|x| find_key(x, key))),
        Value::Array(a) => a.iter().find_map(|x| find_key(x, key)),
        _ => None,
    }
}

pub async fn history(api: &Api) -> anyhow::Result<Vec<Track>> {
    let v = api.call_signed_in("browse", json!({ "browseId": "FEmusic_history", "params": "oggECgIIAQ%3D%3D" })).await?;
    let sections = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    let mut seen = std::collections::HashSet::new();
    Ok(arr(sections)
        .iter()
        .flat_map(|s| arr(&s["musicShelfRenderer"]["contents"]))
        .filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"]))
        .filter(|t| seen.insert(t.id.clone()))
        .collect())
}

pub async fn uploads(api: &Api) -> anyhow::Result<Vec<Track>> {
    let v = api.call_signed_in("browse", json!({ "browseId": "FEmusic_library_privately_owned_tracks" })).await?;
    let tabs = arr(&v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"]);
    let shelf = tabs
        .iter()
        .flat_map(|t| arr(&t["tabRenderer"]["content"]["sectionListRenderer"]["contents"]))
        .map(|c| &c["musicShelfRenderer"])
        .find(|s| !s.is_null());
    let Some(shelf) = shelf else { return Ok(Vec::new()) };
    let mut tracks = upload_rows(shelf);
    let mut token = continuation(shelf);
    for _ in 0..20 {
        let Some(t) = token.take() else { break };
        let v = api.call_signed_in("browse", json!({ "continuation": t })).await?;
        let more = &v["continuationContents"]["musicShelfContinuation"];
        tracks.extend(upload_rows(more));
        token = continuation(more);
    }
    Ok(tracks)
}

fn upload_rows(shelf: &Value) -> Vec<Track> {
    arr(&shelf["contents"]).iter().filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"])).collect()
}

pub async fn set_like(api: &Api, video_id: &str, like: bool) -> anyhow::Result<()> {
    let endpoint = if like { "like/like" } else { "like/removelike" };
    write(api, endpoint, json!({ "target": { "videoId": video_id } })).await?;
    Ok(())
}

pub async fn dislike(api: &Api, video_id: &str) -> anyhow::Result<()> {
    write(api, "like/dislike", json!({"target": {"videoId": video_id}})).await?;
    Ok(())
}

pub async fn report_play(api: &Api, video_id: &str) -> anyhow::Result<Watchtime> {
    let player = api.call_signed_in("player", json!({"videoId": video_id, "contentCheckOk": true, "racyCheckOk": true})).await?;
    let tracking = &player["playbackTracking"];
    let url = tracking["videostatsPlaybackUrl"]["baseUrl"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("no playback tracking URL ({})", player["playabilityStatus"]["status"].as_str().unwrap_or("?")))?;
    let cpn = cpn();
    api.get_signed_in(url, &[("ver", "2"), ("c", "WEB_REMIX"), ("cpn", &cpn)]).await?;
    Ok(Watchtime {
        url: tracking["videostatsWatchtimeUrl"]["baseUrl"].as_str().map(str::to_owned),
        cpn,
        flush_at: arr(&tracking["videostatsScheduledFlushWalltimeSeconds"]).iter().filter_map(Value::as_u64).collect(),
        flush_every: tracking["videostatsDefaultFlushIntervalSeconds"].as_u64().filter(|&s| s > 0).unwrap_or(40),
    })
}

pub struct Watchtime {
    url: Option<String>,
    cpn: String,
    pub flush_at: Vec<u64>,
    pub flush_every: u64,
}

pub async fn report_watchtime(api: &Api, w: &Watchtime, from: f64, to: f64, elapsed: f64, last: bool) -> anyhow::Result<()> {
    let Some(url) = &w.url else { return Ok(()) };
    let (st, et, rt) = (format!("{from:.3}"), format!("{to:.3}"), format!("{elapsed:.3}"));
    let mut query = vec![("ver", "2"), ("c", "WEB_REMIX"), ("cpn", w.cpn.as_str()), ("cmt", et.as_str()), ("st", st.as_str()), ("et", et.as_str()), ("rt", rt.as_str()), ("state", if last { "paused" } else { "playing" })];
    if last {
        query.push(("final", "1"));
    }
    api.get_signed_in(url, &query).await
}

fn cpn() -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut x = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0x9e37_79b9_7f4a_7c15, |d| d.as_nanos() as u64) | 1;
    (0..16)
        .map(|_| {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            ALPHABET[(x & 63) as usize] as char
        })
        .collect()
}

async fn write(api: &Api, endpoint: &str, body: Value) -> anyhow::Result<Value> {
    let value = api.call_signed_in(endpoint, body).await?;
    if value["status"].as_str().is_some_and(|s| !s.contains("SUCCEEDED")) {
        anyhow::bail!("YouTube Music rejected the change");
    }
    Ok(value)
}

pub async fn create_playlist(api: &Api, title: &str, video_id: Option<&str>) -> anyhow::Result<String> {
    let mut body = json!({"title": title, "description": "", "privacyStatus": "PRIVATE"});
    if let Some(id) = video_id {
        body["videoIds"] = json!([id]);
    }
    let value = write(api, "playlist/create", body).await?;
    value["playlistId"].as_str().map(str::to_owned).ok_or_else(|| anyhow::anyhow!("YouTube Music did not return a playlist"))
}

pub async fn create_playlist_with(api: &Api, title: &str, video_ids: &[String]) -> anyhow::Result<String> {
    let title: String = title.chars().filter(|c| !matches!(c, '<' | '>')).collect();
    let value = write(api, "playlist/create", json!({"title": title, "description": "", "privacyStatus": "PRIVATE", "videoIds": video_ids})).await?;
    value["playlistId"].as_str().map(str::to_owned).ok_or_else(|| anyhow::anyhow!("YouTube Music did not return a playlist"))
}

pub async fn add_videos(api: &Api, playlist_id: &str, video_ids: &[String]) -> anyhow::Result<()> {
    let actions: Vec<Value> = video_ids.iter().map(|v| json!({"action": "ACTION_ADD_VIDEO", "addedVideoId": v, "dedupeOption": "DEDUPE_OPTION_SKIP"})).collect();
    write(api, "browse/edit_playlist", json!({"playlistId": playlist_id, "actions": actions})).await?;
    Ok(())
}

pub async fn edit_playlist(api: &Api, id: &str, title: &str, description: &str, privacy: Option<&str>) -> anyhow::Result<()> {
    let mut actions = vec![
        json!({"action": "ACTION_SET_PLAYLIST_NAME", "playlistName": title}),
        json!({"action": "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription": description}),
    ];
    if let Some(privacy) = privacy {
        actions.push(json!({"action": "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy": privacy}));
    }
    write(api, "browse/edit_playlist", json!({"playlistId": id, "actions": actions})).await?;
    Ok(())
}

pub async fn remove_videos(api: &Api, playlist_id: &str, items: &[(String, String)]) -> anyhow::Result<()> {
    let actions: Vec<Value> = items.iter().map(|(v, set)| json!({"action": "ACTION_REMOVE_VIDEO", "removedVideoId": v, "setVideoId": set})).collect();
    write(api, "browse/edit_playlist", json!({"playlistId": playlist_id, "actions": actions})).await?;
    Ok(())
}

pub async fn delete_playlist(api: &Api, id: &str) -> anyhow::Result<()> {
    let response = write(api, "playlist/delete", json!({"playlistId": id})).await?;
    let deleted_id = arr(&response["command"]["commandExecutorCommand"]["commands"])
        .iter()
        .find_map(|command| command["handlePlaylistDeletionCommand"]["playlistId"].as_str());
    anyhow::ensure!(deleted_id == Some(id), "YouTube Music did not confirm playlist deletion");
    Ok(())
}

pub async fn save_collection(api: &Api, playlist_id: &str, save: bool) -> anyhow::Result<()> {
    let endpoint = if save { "like/like" } else { "like/removelike" };
    write(api, endpoint, json!({"target": {"playlistId": playlist_id}})).await?;
    Ok(())
}

#[derive(Clone)]
pub struct Collaboration {
    pub playlist_id: String,
    pub private: bool,
    pub on: bool,
    pub allow_new: bool,
    pub invite_link: Option<String>,
    pub collaborators: Vec<String>,
    turn_on: Option<Value>,
    turn_off: Option<Value>,
    allow: Option<Value>,
    revoke: Option<Value>,
}

pub enum CollabChange {
    Collaborate(bool),
    AllowNew(bool),
}

pub async fn collaboration(api: &Api, playlist_id: &str) -> anyhow::Result<Collaboration> {
    let page = api.call_signed_in("browse", json!({"browseId": format!("VL{playlist_id}")})).await?;
    let header = find_key(&page, "musicPlaylistEditHeaderRenderer").ok_or_else(|| anyhow::anyhow!("only your own playlists can be shared for collaboration"))?;
    let private = header["privacy"].as_str() == Some("PRIVATE");
    let Some(params) = header["collaborationSettingsCommand"]["showEngagementPanelEndpoint"]["globalConfiguration"]["params"].as_str() else {
        anyhow::ensure!(private, "YouTube Music doesn't offer collaboration on this playlist");
        return Ok(Collaboration { playlist_id: playlist_id.to_owned(), private, on: false, allow_new: false, invite_link: None, collaborators: Vec::new(), turn_on: None, turn_off: None, allow: None, revoke: None });
    };
    let panel = api.call_signed_in("get_panel", json!({"panelId": "PAplaylist_collaborate", "params": params})).await?;
    parse_collaboration(playlist_id, private, &panel).ok_or_else(|| anyhow::anyhow!("couldn't read the playlist's collaboration settings"))
}

fn parse_collaboration(playlist_id: &str, private: bool, panel: &Value) -> Option<Collaboration> {
    let vm = find_key(panel, "playlistCollaborationViewModel")?;
    let values = &vm["playlistCollaborationFormSchema"]["initialValues"];
    let switch = |key: &str| &vm[key]["switchListItemViewModel"];
    let edit = |cmd: &Value| find_key(cmd, "playlistEditEndpoint").filter(|e| e["actions"].is_array()).cloned();
    let collaborate = switch("collaboratePlaylistCollaborationSetting");
    let allow_new = switch("allowNewCollaboratorsPlaylistCollaborationSetting");
    let link = find_key(&vm["copyLinkButton"], "copyLinkCommand").and_then(|c| c["shortUrl"].as_str()).filter(|u| !u.is_empty());
    let mut collaborators = Vec::new();
    collect_titles(&vm["playlistCollaborators"], &mut collaborators);
    Some(Collaboration {
        playlist_id: playlist_id.to_owned(),
        private,
        on: values["isCollaborationEnabled"].as_bool().or(collaborate["switchedOnByDefault"].as_bool()).unwrap_or(false),
        allow_new: values["isAllowNewCollaboratorsEnabled"].as_bool().or(allow_new["switchedOnByDefault"].as_bool()).unwrap_or(false),
        invite_link: link.map(str::to_owned),
        collaborators,
        turn_on: edit(&collaborate["switchOnCommand"]),
        turn_off: edit(&collaborate["switchOffCommand"]),
        allow: edit(&allow_new["switchOnCommand"]),
        revoke: edit(&allow_new["switchOffCommand"]),
    })
}

fn collect_titles(v: &Value, out: &mut Vec<String>) {
    match v {
        Value::Object(m) => {
            if let Some(t) = m.get("title").and_then(|t| t["content"].as_str()) {
                out.push(t.to_owned());
                return;
            }
            m.values().for_each(|x| collect_titles(x, out));
        }
        Value::Array(a) => a.iter().for_each(|x| collect_titles(x, out)),
        _ => {}
    }
}

pub async fn set_collaboration(api: &Api, c: &Collaboration, change: CollabChange) -> anyhow::Result<()> {
    let id = c.playlist_id.as_str();
    let fallback = |action: Value| json!({"playlistId": id, "actions": [action]});
    let body = match change {
        CollabChange::Collaborate(true) if c.private => {
            write(api, "browse/edit_playlist", fallback(json!({"action": "ACTION_SET_PLAYLIST_PRIVACY", "playlistPrivacy": "UNLISTED"}))).await?;
            let invite = fallback(json!({"action": "ACTION_CREATE_COLLABORATION_INVITE_LINK"}));
            for _ in 0..8 {
                match write(api, "browse/edit_playlist", invite.clone()).await {
                    Err(e) if e.downcast_ref::<crate::ytmapi::ApiError>().is_some_and(|a| a.status == 400) => tokio::time::sleep(std::time::Duration::from_secs(2)).await,
                    r => return r.map(drop),
                }
            }
            invite
        }
        CollabChange::Collaborate(true) => c.turn_on.clone().unwrap_or_else(|| fallback(json!({"action": "ACTION_CREATE_COLLABORATION_INVITE_LINK"}))),
        CollabChange::Collaborate(false) => c.turn_off.clone().unwrap_or_else(|| fallback(json!({"action": "ACTION_SET_CLOSED_TO_CONTRIBUTIONS", "closedToContributions": true}))),
        CollabChange::AllowNew(true) => c.allow.clone().unwrap_or_else(|| fallback(json!({"action": "ACTION_CREATE_COLLABORATION_INVITE_LINK"}))),
        CollabChange::AllowNew(false) => c.revoke.clone().unwrap_or_else(|| fallback(json!({"action": "ACTION_REVOKE_COLLABORATION_TOKENS"}))),
    };
    write(api, "browse/edit_playlist", body).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn moods_parse_and_drop_shortcuts() {
        let tile = |t: &str, p: &str, c: Value| json!({"musicNavigationButtonRenderer": {
            "buttonText": {"runs": [{"text": t}]}, "solid": {"leftStripeColor": c},
            "clickCommand": {"browseEndpoint": {"browseId": "FEmusic_moods_and_genres_category", "params": p}}}});
        let grid = |t: &str, items: Vec<Value>| json!({"gridRenderer": {"header": {"gridHeaderRenderer": {"title": {"runs": [{"text": t}]}}}, "items": items}});
        let v = json!({"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            grid("For you", vec![tile("Chill", "c", json!(4278190335u64))]),
            grid("Shortcuts", vec![tile("Chill", "c", json!(1))]),
            grid("Moods & moments", vec![tile("Chill", "c", json!(4294901760u64)), tile("Focus", "f", json!("4278255360"))]),
        ]}}}}]}}});
        let s = parse_moods(&v);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].title, "Moods & moments");
        assert_eq!(s[0].items[0].color, Some(0xFF0000));
        assert_eq!(s[0].items[1].color, Some(0x00FF00));
        assert_eq!(s[0].items[1].params.as_deref(), Some("f"));
    }

    fn account_item(name: &str, handle: Option<&str>, selected: bool, page: Option<&str>, datasync: &str) -> Value {
        let mut tokens = vec![json!({"accountStateToken": {"hasChannel": true}}), json!({"datasyncIdToken": {"datasyncIdToken": datasync}})];
        if let Some(p) = page {
            tokens.push(json!({"pageIdToken": {"pageId": p}}));
        }
        let mut item = json!({
            "accountName": {"runs": [{"text": name}]},
            "isSelected": selected,
            "serviceEndpoint": {"selectActiveIdentityEndpoint": {"supportedTokens": tokens}},
        });
        if let Some(h) = handle {
            item["channelHandle"] = json!({"simpleText": h});
        }
        json!({"accountItem": item})
    }

    fn switcher(sections: Vec<Vec<Value>>) -> Value {
        let sections: Vec<Value> = sections
            .into_iter()
            .map(|items| json!({"accountSectionListRenderer": {"contents": [{"accountItemSectionRenderer": {"contents": items}}]}}))
            .collect();
        json!({"actions": [{"getMultiPageMenuAction": {"menu": {"multiPageMenuRenderer": {"sections": sections}}}}]})
    }

    #[test]
    fn channels_are_the_accounts_own_then_its_brand_channels() {
        let v = switcher(vec![
            vec![
                account_item("Ada", Some("@ada"), true, None, "111||"),
                account_item("Ada Records", Some("@adarecords"), false, Some("900"), "900||111"),
                account_item("Ada Live", None, false, Some("901"), "901||111"),
            ],
            vec![account_item("Bob", Some("@bob"), false, None, "222||"), account_item("Bob's Band", None, false, Some("902"), "902||222")],
        ]);
        let list = parse_channels(&v);
        let ids: Vec<_> = list.iter().map(|c| (c.name.as_str(), c.page_id.as_deref())).collect();
        assert_eq!(ids, [("Ada", None), ("Ada Records", Some("900")), ("Ada Live", Some("901"))]);
        assert_eq!(list[0].handle.as_deref(), Some("@ada"));
        assert!(list.iter().all(|c| c.has_channel));
    }

    #[tokio::test]
    #[ignore]
    async fn channels_live() {
        let api = Api::new(&std::path::PathBuf::from(std::env::var("NUVX_DATA_DIR").unwrap()));
        let current = api.account().expect("no session in the vault").channel().map(str::to_owned);
        for c in channels(&api).await.unwrap() {
            let mark = if c.page_id == current { "*" } else { " " };
            println!("{mark} {} {:?} channel {} page {:?}", c.name, c.handle, c.has_channel, c.page_id);
        }
    }

    #[test]
    fn a_google_account_without_a_channel_is_listed_as_such() {
        let mut own = account_item("Ada", None, true, None, "111||");
        own["accountItem"]["hasChannel"] = json!(false);
        own["accountItem"]["accountByline"] = json!({"runs": [{"text": "No channel"}]});
        let v = switcher(vec![vec![own, account_item("Ada", Some("@ada5564"), false, Some("900"), "900||111")]]);
        let list = parse_channels(&v);
        assert_eq!(list.iter().map(|c| (c.has_channel, c.handle.as_deref())).collect::<Vec<_>>(), [(false, None), (true, Some("@ada5564"))]);
    }

    #[test]
    fn a_switcher_without_the_accounts_own_channel_lists_nothing() {
        assert!(parse_channels(&json!({"actions": []})).is_empty());
        let only_brand = switcher(vec![vec![account_item("Ada Records", None, true, Some("900"), "900||111")]]);
        assert!(parse_channels(&only_brand).is_empty());
        let unselected = switcher(vec![vec![account_item("Ada", None, false, None, "111||"), account_item("Ada Records", None, false, Some("900"), "900||111")]]);
        assert!(parse_channels(&unselected).is_empty());
    }

    #[test]
    fn brand_channels_of_an_unknown_account_are_left_out_beside_other_accounts() {
        let pages = |v: &Value| parse_channels(v).into_iter().map(|c| c.page_id).collect::<Vec<_>>();
        let mut brand = account_item("Bob's Band", None, false, Some("902"), "");
        brand["accountItem"]["serviceEndpoint"]["selectActiveIdentityEndpoint"]["supportedTokens"] = json!([{"pageIdToken": {"pageId": "902"}}]);
        let two_accounts = switcher(vec![vec![account_item("Ada", None, true, None, "111||"), account_item("Bob", None, false, None, "222||"), brand.clone()]]);
        assert_eq!(pages(&two_accounts), [None]);
        let one_account = switcher(vec![vec![account_item("Ada", None, true, None, "111||"), brand]]);
        assert_eq!(pages(&one_account), [None, Some("902".to_owned())]);
    }

    #[test]
    fn past_searches_come_with_their_remove_token() {
        let v = json!({"contents": [
            {"searchSuggestionsSectionRenderer": {"contents": [
                {"historySuggestionRenderer": {"suggestion": {"runs": [{"text": "lo", "bold": true}, {"text": "fi beats"}]},
                    "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "tok"}},
                    "navigationEndpoint": {"searchEndpoint": {"query": "lofi beats"}}}},
                {"searchSuggestionRenderer": {"suggestion": {"runs": [{"text": "lo"}, {"text": "ve songs"}]}}}
            ]}},
            {"searchSuggestionsSectionRenderer": {"contents": [{"musicResponsiveListItemRenderer": {}}]}}
        ]});
        assert_eq!(
            parse_suggestions(&v),
            [
                Suggestion { text: "lofi beats".into(), forget: Some("tok".into()) },
                Suggestion { text: "love songs".into(), forget: None },
            ]
        );
    }

    #[test]
    fn revoked_session_reads_as_logged_out() {
        let v = json!({
            "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                {"itemSectionRenderer": {"contents": [{"messageRenderer": {"text": {"runs": [{"text": "Looking for what you’ve liked?"}]}}}]}}
            ]}}}}]}},
            "responseContext": {"serviceTrackingParams": [{"service": "GFEEDBACK", "params": [{"key": "logged_in", "value": "0"}]}]},
        });
        assert!(parse_playlist(&v, "LM").is_none());
        assert!(crate::ytmapi::logged_out(&v));
        assert!(!crate::ytmapi::logged_out(&json!({"responseContext": {"serviceTrackingParams": [{"params": [{"key": "logged_in", "value": "1"}]}]}})));
    }

    const SEARCH_ROW: &str = r#"{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/JzRduI","width":60,"height":60}]}}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Blinding Lights","navigationEndpoint":{"watchEndpoint":{"videoId":"_l3v7ZyJZUI"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Emily Dawn","navigationEndpoint":{"browseEndpoint":{"browseId":"UCpTBon3nXs5Y4OuFVr2q6Ug","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":", "},{"text":"Vive","navigationEndpoint":{"browseEndpoint":{"browseId":"UCZOyQvwe70uoAMFSoUyWJWw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" & "},{"text":"Sandy Beach","navigationEndpoint":{"browseEndpoint":{"browseId":"UCgWx_rcbegYfQuvrkwqKdEw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" • "},{"text":"Blinding Lights","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_91LK9pVGLhy","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},{"text":" • "},{"text":"2:52"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"2.8K plays"}]}}}],"playlistItemData":{"videoId":"_l3v7ZyJZUI"}}}"#;
    const PLAYLIST_ROW: &str = r#"{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/Xl4q7A","width":60,"height":60}]}}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Universe...","navigationEndpoint":{"watchEndpoint":{"videoId":"F9apex1lPEw","playlistId":"RDCLAK5uy_mPolD_J22gS1SKxufARWcTZd1UrAH_0ZI"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"R3HAB","navigationEndpoint":{"browseEndpoint":{"browseId":"UCjpBcfMn2g8afa3XIZmS3mw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" & "},{"text":"Jason Stephenson"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"259K plays"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Dream inside a dream…","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_t6uDCi8Ljsr","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}],"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"1:52"}]}}}],"playlistItemData":{"videoId":"F9apex1lPEw"}}}"#;
    const ALBUM_ROW: &str = r#"{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Alone Again","navigationEndpoint":{"watchEndpoint":{"videoId":"JH398xAYpZA","playlistId":"OLAK5uy_l4UqNJCpAF3kNaV37LHdRc_A07MmVdiSU"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"54M plays"}]}}}],"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"4:11"}]}}}],"playlistItemData":{"videoId":"JH398xAYpZA"},"index":{"runs":[{"text":"1"}]}}}"#;
    const QUICK_PICK: &str = r#"{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/r6AM19","width":60,"height":60}]}}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"地味な色 - Sober Color","navigationEndpoint":{"watchEndpoint":{"videoId":"ccOWvt_7Xig","playlistId":"RDAMVMccOWvt_7Xig"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"mei ehara","navigationEndpoint":{"browseEndpoint":{"browseId":"UC6E1po8d95GkLmZD2azY9Ig","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" • "},{"text":"37K plays"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Sway","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_ZVsoTQ0vbS4","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}],"playlistItemData":{"videoId":"ccOWvt_7Xig"}}}"#;
    const PANEL_ITEM: &str = r#"{"playlistPanelVideoRenderer":{"title":{"runs":[{"text":"Resonance"}]},"longBylineText":{"runs":[{"text":"Home","navigationEndpoint":{"browseEndpoint":{"browseId":"UCfWnoT0YX8RfjFaXmg2Eq5g","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" • "},{"text":"Odyssey","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_83fMW9gQPxu","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},{"text":" • "},{"text":"2014"}]},"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/S6e6T4","width":60,"height":60}]},"lengthText":{"runs":[{"text":"3:33"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"aBhrEv5Z7uc","playlistId":"RDAMVMJ7p4bzqLvCw","index":3}},"videoId":"aBhrEv5Z7uc"}}"#;
    const ARTIST_ROW: &str = r#"{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/U-SAmN","width":60,"height":60}]}}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"The Weeknd"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"239M monthly audience"}]}}}],"navigationEndpoint":{"browseEndpoint":{"browseId":"UClYV6hHlupm_S_ObS1W-DYw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}}"#;

    fn row(s: &str) -> Value {
        serde_json::from_str::<Value>(s).unwrap()["musicResponsiveListItemRenderer"].clone()
    }

    fn names(t: &Track) -> Vec<&str> {
        t.artists.iter().map(|a| a.name.as_str()).collect()
    }

    #[test]
    fn search_row_has_artists_album_length_and_plays() {
        let t = list_item(&row(SEARCH_ROW)).unwrap();
        assert_eq!(names(&t), ["Emily Dawn", "Vive", "Sandy Beach"]);
        assert!(t.artists.iter().all(|a| a.id.is_some()));
        assert_eq!(t.album.as_ref().map(|a| a.name.as_str()), Some("Blinding Lights"));
        assert_eq!(t.duration, Some(172));
        assert_eq!(t.plays, Some(2_800));
    }

    #[test]
    fn playlist_row_keeps_unlinked_artists() {
        let t = list_item(&row(PLAYLIST_ROW)).unwrap();
        assert_eq!(names(&t), ["R3HAB", "Jason Stephenson"]);
        assert_eq!(t.artists[0].id.as_deref(), Some("UCjpBcfMn2g8afa3XIZmS3mw"));
        assert_eq!(t.artists[1].id, None);
        assert_eq!(t.album.as_ref().and_then(|a| a.id.as_deref()), Some("MPREb_t6uDCi8Ljsr"));
        assert_eq!((t.duration, t.plays), (Some(112), Some(259_000)));
    }

    #[test]
    fn album_row_has_number_and_no_artists() {
        let t = list_item(&row(ALBUM_ROW)).unwrap();
        assert_eq!((t.title.as_str(), t.track_nr, t.duration), ("Alone Again", Some(1), Some(251)));
        assert!(t.artists.is_empty());
    }

    #[test]
    fn quick_pick_artist_is_not_its_play_count() {
        let t = list_item(&row(QUICK_PICK)).unwrap();
        assert_eq!(names(&t), ["mei ehara"]);
        assert_eq!(t.plays, Some(37_000));
        assert_eq!(t.album.as_ref().map(|a| a.name.as_str()), Some("Sway"));
    }

    #[test]
    fn radio_item() {
        let v: Value = serde_json::from_str(PANEL_ITEM).unwrap();
        let t = panel_item(&v["playlistPanelVideoRenderer"]).unwrap();
        assert!(!t.id.is_empty() && !t.title.is_empty());
        assert!(!t.artists.is_empty() && t.album.is_some() && t.duration.is_some());
    }

    #[test]
    fn artist_search_row_becomes_a_round_card() {
        let c = list_card(&row(ARTIST_ROW)).unwrap();
        assert_eq!(c.target, Target::Artist("UClYV6hHlupm_S_ObS1W-DYw".into()));
        assert_eq!((c.title.as_str(), c.subtitle.as_str(), c.round), ("The Weeknd", "239M monthly audience", true));
    }

    #[test]
    fn album_header() {
        let v = json!({"contents": {"twoColumnBrowseResultsRenderer": {
            "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicResponsiveHeaderRenderer": {
                "title": {"runs": [{"text": "After Hours"}]},
                "subtitle": {"runs": [{"text": "Album"}, {"text": " • "}, {"text": "2020"}]},
                "straplineTextOne": {"runs": [{"text": "The Weeknd", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCw"}}}]},
                "buttons": [{"musicPlayButtonRenderer": {"playNavigationEndpoint": {"watchPlaylistEndpoint": {"playlistId": "OLAK5uy_x"}}}}]
            }}]}}}}],
            "secondaryContents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": [serde_json::from_str::<Value>(ALBUM_ROW).unwrap()]}}]}}
        }}});
        let (a, videos) = parse_album(&v, "MPREb_x").unwrap();
        assert!(videos.is_empty());
        assert_eq!(a.subtitle, "Album • The Weeknd • 2020");
        assert_eq!(a.save_id.as_deref(), Some("OLAK5uy_x"));
        let t = &a.tracks[0];
        assert_eq!(t.artists[0].name, "The Weeknd");
        assert_eq!(t.album.as_ref().and_then(|l| l.id.as_deref()), Some("MPREb_x"));
    }

    fn album_row(id: &str, title: &str, kind: &str) -> Value {
        let watch = json!({"watchEndpoint": {"videoId": id, "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": kind}}}});
        json!({"musicResponsiveListItemRenderer": {
            "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": title, "navigationEndpoint": watch}]}}}],
            "playlistItemData": {"videoId": id},
        }})
    }

    fn album_page(rows: &[(&str, &str, &str)]) -> Value {
        let rows: Vec<Value> = rows.iter().map(|(id, title, kind)| album_row(id, title, kind)).collect();
        json!({"contents": {"twoColumnBrowseResultsRenderer": {
            "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicResponsiveHeaderRenderer": {
                "title": {"runs": [{"text": "Whenever You Need Somebody"}]},
                "buttons": [{"musicPlayButtonRenderer": {"playNavigationEndpoint": {"watchPlaylistEndpoint": {"playlistId": "OLAK5uy_x"}}}}]
            }}]}}}}],
            "secondaryContents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": rows}}]}}
        }}})
    }

    fn vr_album(title: &str, songs: &[(&str, &str, &str)]) -> Value {
        let contents: Vec<Value> = songs
            .iter()
            .map(|(id, t, len)| json!({"playlistPanelVideoRenderer": {"videoId": id, "title": {"runs": [{"text": t}]}, "lengthText": {"runs": [{"text": len}]}}}))
            .collect();
        json!({"contents": {"singleColumnWatchNextResults": {"playlist": {"playlist": {"title": title, "contents": contents}}}}})
    }

    const RICK_ROWS: [(&str, &str, &str); 3] = [
        ("dQw4w9WgXcQ", "Never Gonna Give You Up", "MUSIC_VIDEO_TYPE_OMV"),
        ("BeyEGebJ1l4", "Whenever You Need Somebody", "MUSIC_VIDEO_TYPE_OMV"),
        ("d0t04zd918U", "The Love Has Gone", "MUSIC_VIDEO_TYPE_ATV"),
    ];
    const RICK_SONGS: [(&str, &str, &str); 3] = [("lYBUbBu4W08", "Never Gonna Give You Up", "3:34"), ("raBobo3GZYA", "Whenever You Need Somebody", "3:54"), ("d0t04zd918U", "The Love Has Gone", "4:21")];

    fn swapped(rows: &[(&str, &str, &str)], vr: &Value) -> Vec<String> {
        let (mut a, videos) = parse_album(&album_page(rows), "MPREb_x").unwrap();
        if let Some(songs) = album_songs(vr).filter(|s| s.len() == a.tracks.len()) {
            swap_songs(&mut a, &videos, &songs);
        }
        a.tracks.into_iter().map(|t| t.id).collect()
    }

    #[test]
    fn album_music_videos_become_the_albums_songs() {
        let (mut a, videos) = parse_album(&album_page(&RICK_ROWS), "MPREb_x").unwrap();
        assert_eq!(videos, [0, 1]);
        let songs = album_songs(&vr_album("Album - Whenever You Need Somebody", &RICK_SONGS)).unwrap();
        assert_eq!(swap_songs(&mut a, &videos, &songs), 2);
        assert_eq!(a.tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["lYBUbBu4W08", "raBobo3GZYA", "d0t04zd918U"]);
        assert_eq!((a.tracks[1].title.as_str(), a.tracks[1].duration), ("Whenever You Need Somebody", Some(234)));
    }

    #[test]
    fn album_songs_change_nothing_unless_they_line_up() {
        let vr = vr_album("Album - Whenever You Need Somebody", &RICK_SONGS);
        let listed = ["dQw4w9WgXcQ", "BeyEGebJ1l4", "d0t04zd918U"];
        assert_eq!(swapped(&RICK_ROWS[..2], &vr), listed[..2]);
        let mut renamed = RICK_ROWS;
        renamed[1].1 = "Whenever You Need Somebody (Radio Edit)";
        assert_eq!(swapped(&renamed, &vr), ["lYBUbBu4W08", "BeyEGebJ1l4", "d0t04zd918U"]);
        let mut other = RICK_SONGS;
        other[2].0 = "elsewhere";
        assert_eq!(swapped(&RICK_ROWS, &vr_album("Album - Whenever You Need Somebody", &other))[2], "d0t04zd918U");
        let top = vr_album("Song - Rick Astley", &RICK_SONGS);
        assert_eq!(album_songs(&top), None);
        assert_eq!(swapped(&RICK_ROWS, &top), listed);
        let mut broken = vr.clone();
        broken["contents"]["singleColumnWatchNextResults"]["playlist"]["playlist"]["contents"][1] = json!({"automixPreviewVideoRenderer": {}});
        assert_eq!(album_songs(&broken), None);
    }

    #[test]
    fn titles_match_but_for_case_and_punctuation() {
        assert!(same_title("The Less I Know The Better", "The Less I Know the Better"));
        assert!(same_title("Don't Say Goodbye", "dont say goodbye"));
        assert!(same_title("Für Elise", "für  elise"));
        assert!(same_title("Levitating (feat. DaBaby)", "Levitating"));
        assert!(same_title("Stan [Featuring Dido]", "stan"));
        assert!(same_title("LOYALTY. (ft. Rihanna)", "LOYALTY."));
        assert!(!same_title("Levitating (Remix)", "Levitating"));
        assert!(!same_title("Mitwa", "Tere Mere Honthon Pe - Mitwa"));
        assert!(!same_title("", ""));
    }

    #[tokio::test]
    #[ignore]
    async fn podcasts_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-album-songs-test"));
        let page = podcasts(&api, false).await.unwrap();
        for s in &page.shelves {
            if let ShelfKind::Songs { tracks, .. } = &s.kind {
                println!("{}: {:?}", s.title, tracks.iter().take(2).map(|t| (&t.id, &t.title, t.duration)).collect::<Vec<_>>());
            }
        }
        assert!(page.shelves.iter().any(|s| matches!(&s.kind, ShelfKind::Songs { tracks, .. } if !tracks.is_empty())));
    }

    #[tokio::test]
    #[ignore]
    async fn youtube_search_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-album-songs-test"));
        let found = youtube_search(&api, "rust programming tutorial").await.unwrap();
        println!("{:?}", found.iter().take(3).map(|t| (&t.id, &t.title, t.duration, t.artists.first().map(|a| &a.name))).collect::<Vec<_>>());
        assert!(found.len() >= 5);
    }

    #[test]
    fn youtube_rows_become_tracks_and_live_streams_drop() {
        let v = json!({"contents": {"twoColumnSearchResultsRenderer": {"primaryContents": {"sectionListRenderer": {"contents": [
            {"itemSectionRenderer": {"contents": [
                {"videoRenderer": {"videoId": "abc", "title": {"runs": [{"text": "A talk"}]}, "lengthText": {"simpleText": "1:02:03"}, "ownerText": {"runs": [{"text": "Chan"}]}}},
                {"videoRenderer": {"videoId": "live", "title": {"runs": [{"text": "Live now"}]}}},
                {"shelfRenderer": {}}
            ]}}
        ]}}}}});
        let t = youtube_videos(&v);
        assert_eq!(t.len(), 1);
        assert_eq!((t[0].id.as_str(), t[0].duration, t[0].artists[0].name.as_str()), ("abc", Some(3723), "Chan"));
    }

    #[test]
    fn spoken_lengths_become_seconds() {
        assert_eq!(spoken_secs(" • 42 min"), Some(2520));
        assert_eq!(spoken_secs("1 hr 5 min"), Some(3900));
        assert_eq!(spoken_secs("Played"), None);
    }

    #[tokio::test]
    #[ignore]
    async fn moods_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-album-songs-test"));
        let mut sections = moods(&api, false).await.unwrap();
        println!("{:?}", sections.iter().map(|s| (&s.title, s.items.len())).collect::<Vec<_>>());
        mood_art(&api, &mut sections, false).await;
        let (n, with) = sections.iter().flat_map(|s| &s.items).fold((0, 0), |(n, w), m| (n + 1, w + usize::from(m.thumb.is_some())));
        println!("art for {with}/{n}: {:?}", sections[0].items[0].thumb);
        assert!(with * 10 >= n * 9, "most tiles should find a cover");
        let m = &sections[0].items[0];
        let page = mood_page(&api, &m.browse_id, m.params.as_deref(), false).await.unwrap();
        println!("{} {:?}: {:?}", m.title, page.title, page.shelves.iter().map(|s| &s.title).collect::<Vec<_>>());
        assert!(!page.shelves.is_empty());
    }

    #[tokio::test]
    #[ignore]
    async fn album_songs_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-album-songs-test"));
        let a = album(&api, "MPREb_dcYZhAh5urI").await.unwrap();
        println!("{:?}", a.tracks.iter().map(|t| (&t.id, &t.title, t.duration)).collect::<Vec<_>>());
        assert_eq!(a.tracks[0].id, "lYBUbBu4W08", "Never Gonna Give You Up should be the song, not the music video");
    }

    #[test]
    fn playlist_header_owner_count_and_next_page() {
        let v = json!({"contents": {"twoColumnBrowseResultsRenderer": {
            "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicResponsiveHeaderRenderer": {
                "title": {"runs": [{"text": "Official Videos"}]},
                "secondSubtitle": {"runs": [{"text": "22M views"}, {"text": " • "}, {"text": "1,046 tracks"}]},
                "facepile": {"avatarStackViewModel": {"text": {"content": "The Weeknd"}, "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": "UC0"}}}}}}}
            }}]}}}}],
            "secondaryContents": {"sectionListRenderer": {"contents": [{"musicPlaylistShelfRenderer": {"contents": [
                serde_json::from_str::<Value>(PLAYLIST_ROW).unwrap(),
                {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT"}}}}
            ]}}]}}
        }}});
        let (p, token, count) = parse_playlist(&v, "PLx").unwrap();
        assert_eq!(count, Some(1046));
        assert_eq!(p.byline(), "The Weeknd • 1046 songs");
        assert_eq!(p.artists[0].id.as_deref(), Some("UC0"));
        assert_eq!(p.tracks.len(), 1);
        assert_eq!(token.as_deref(), Some("NEXT"));
    }

    #[test]
    fn library_grid_pages() {
        let card = |id: &str| json!({"musicTwoRowItemRenderer": {"title": {"runs": [{"text": id}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": id,
            "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_PLAYLIST"}}}}}});
        let first = json!({"gridRenderer": {"items": [card("VLa")], "continuations": [{"nextContinuationData": {"continuation": "P2"}}]}});
        assert_eq!(grid_next(&first["gridRenderer"]).as_deref(), Some("P2"));

        let old = json!({"continuationContents": {"gridContinuation": {"items": [card("VLb"), card("VLc")], "continuations": [{"nextContinuationData": {"continuation": "P3"}}]}}});
        let (cards, next) = grid_continuation(&old);
        assert_eq!(cards.len(), 2);
        assert_eq!(next.as_deref(), Some("P3"));

        let new = json!({"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": [
            card("VLd"),
            {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "P4"}}}}
        ]}}]});
        let (cards, next) = grid_continuation(&new);
        assert_eq!(cards.len(), 1);
        assert_eq!(next.as_deref(), Some("P4"));

        let last = json!({"continuationContents": {"gridContinuation": {"items": [card("VLe")]}}});
        assert_eq!(grid_continuation(&last).1, None);
    }

    #[test]
    fn counts_and_lengths() {
        assert_eq!(count("3.6B plays"), Some(3_600_000_000));
        assert_eq!(count("40.1M"), Some(40_100_000));
        assert_eq!(count("1,234 views"), Some(1_234));
        assert_eq!(parse_duration("1:02:03"), Some(3723));
        assert!(!looks_like_duration("Blinding Lights"));
    }

    #[test]
    fn server_queue_picks_the_selected_song() {
        let v: Value = serde_json::from_str(r#"{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{
            "content":{"playlistPanelRenderer":{"contents":[
                {"playlistPanelVideoRenderer":{"title":{"runs":[{"text":"Before"}]},"videoId":"aaaaaaaaaaa","lengthText":{"runs":[{"text":"3:00"}]}}},
                {"playlistPanelVideoRenderer":{"title":{"runs":[{"text":"Spring Lovers"}]},"videoId":"Q-IaNDsZXDE","selected":true,"lengthText":{"runs":[{"text":"2:50"}]}}},
                {"automixPreviewVideoRenderer":{"content":{}}}]}},
            "musicQueueConfig":{"musicQueueRestorationInfo":{"lastWatchTimestamp":{"nanos":546492000,"seconds":"1793133616"}}}}}}}]}}}}}"#).unwrap();
        let q = parse_server_queue(&v).unwrap();
        assert_eq!(q.tracks.len(), 2);
        assert_eq!(q.index, 1);
        assert_eq!(q.tracks[1].id, "Q-IaNDsZXDE");
        assert_eq!(q.tracks[1].duration, Some(170));
        assert_eq!(q.watched_at, 1_793_133_616);
        assert!(parse_server_queue(&json!({})).is_none());
    }

    #[test]
    fn collaboration_panel() {
        let edit = |action: &str| json!({"innertubeCommand": {"playlistEditEndpoint": {"actions": [{"action": action}], "playlistId": "PLx"}}});
        let v = json!({"content": {"engagementPanelSectionListRenderer": {"content": {"playlistCollaborationViewModel": {
            "allowNewCollaboratorsPlaylistCollaborationSetting": {"switchListItemViewModel": {"switchOffCommand": edit("ACTION_REVOKE_COLLABORATION_TOKENS"), "switchOnCommand": edit("ACTION_CREATE_COLLABORATION_INVITE_LINK"), "switchedOnByDefault": false}},
            "collaboratePlaylistCollaborationSetting": {"switchListItemViewModel": {
                "switchOffCommand": {"parallelCommand": {"commands": [{"dismissDialogCommand": {"hack": true}}, {"innertubeCommand": {"playlistEditEndpoint": {"actions": [{"action": "ACTION_SET_CLOSED_TO_CONTRIBUTIONS", "closedToContributions": true}], "playlistId": "PLx"}}}]}},
                "switchOnCommand": edit("ACTION_CREATE_COLLABORATION_INVITE_LINK"), "switchedOnByDefault": false}},
            "copyLinkButton": {"buttonViewModel": {"onTap": {"innertubeCommand": {"copyLinkCommand": {"id": "PLx", "shortUrl": ""}}}}},
            "playlistCollaborationFormSchema": {"initialValues": {"isAllowNewCollaboratorsEnabled": false, "isCollaborationEnabled": false}}}}}}});
        let c = parse_collaboration("PLx", false, &v).unwrap();
        assert!(!c.on && !c.allow_new);
        assert_eq!(c.invite_link, None);
        assert_eq!(c.turn_off.unwrap()["actions"][0]["closedToContributions"], true);
        assert_eq!(c.revoke.unwrap()["actions"][0]["action"], "ACTION_REVOKE_COLLABORATION_TOKENS");
        assert!(c.turn_on.is_some() && c.allow.is_some());
    }
    fn song(title: &str, artists: &[&str], secs: u32) -> Track {
        Track {
            id: title.into(),
            title: title.into(),
            artists: artists.iter().map(|a| Link { name: a.to_string(), id: None }).collect(),
            album: None,
            duration: Some(secs),
            thumbs: vec![],
            track_nr: None,
            plays: None,
            music_video: false,
        }
    }

    fn video(title: &str, artists: &[&str], secs: u32) -> Track {
        Track { music_video: true, ..song(title, artists, secs) }
    }

    #[test]
    fn a_music_video_title_loses_its_video_markings() {
        assert_eq!(song_title("Ghostbusters (Official HD Video)"), "Ghostbusters");
        assert_eq!(song_title("Take On Me (Official Video) [4K]"), "Take On Me");
        assert_eq!(song_title("Bad Guy | Official Lyric Video"), "Bad Guy");
        assert_eq!(song_title("\"Jaan-E-Zigar Jaaneman\" Lyrical Video"), "\"Jaan-E-Zigar Jaaneman");
        assert_eq!(song_title("Hothon Se Chhu Lo Tum (From \"Prem Geet\")"), "Hothon Se Chhu Lo Tum (From \"Prem Geet\")");
        assert_eq!(song_title("Together Forever (2022 Remaster)"), "Together Forever (2022 Remaster)");
        assert_eq!(song_title("Official Video"), "Official Video");
        assert_eq!(song_title("Ça Plane Pour Moi (Clip Officiel)"), "Ça Plane Pour Moi");
    }

    #[test]
    fn the_song_of_a_music_video_is_found_by_a_sure_match_only() {
        assert!(same_song(&video("Never Gonna Give You Up", &["Rick Astley"], 214), &song("Never Gonna Give You Up", &["Rick Astley"], 214)));
        assert!(same_song(&video("Kisi Nazar Ko Tera Intezar", &["Asha Bhosle"], 452), &song("Kisi Nazar Ko Tera Intezar", &["Asha Bhosle", "Bhupinder Singh"], 404)));
        assert!(same_song(&video("Saagar Jaisi Aankhonwali (From \"Saagar\")", &["Kishore Kumar"], 270), &song("Saagar Jaisi Aankhonwali (From \"Saagar\")", &["Kishore Kumar", "R. D. Burman"], 304)));
        assert!(same_song(&video("Ghostbusters (Official HD Video)", &["Ray Parker Jr."], 246), &song("Ghostbusters", &["Ray Parker Jr."], 240)));
        assert!(same_song(&video("Together Forever", &["Rick Astley"], 204), &song("Together Forever (2022 Remaster)", &["Rick Astley"], 206)));
        assert!(!same_song(&video("Mitwa", &["Lata Mangeshkar"], 289), &song("Tere Mere Honthon Pe - Mitwa", &["Lata Mangeshkar"], 334)));

        let take_on_me = video("Take On Me (Official Video)", &["a-ha"], 244);
        for (hit, why) in [
            (song("Take On Me (Live)", &["a-ha"], 240), "a live recording"),
            (song("Take On Me - Live from MTV Unplugged", &["a-ha"], 240), "an unplugged one"),
            (song("Take On Me (Remix)", &["a-ha"], 244), "a remix"),
            (song("Take On Me", &["Weezer"], 244), "a cover"),
            (song("Take On Me (Acoustic Version)", &["a-ha"], 244), "an acoustic version"),
            (song("Take On Me", &["a-ha"], 500), "a much longer recording"),
            (song("Take On Me", &["a-ha"], 190), "a much shorter one"),
            (Track { duration: None, ..song("Take On Me", &["a-ha"], 0) }, "an unknown length"),
            (video("Take On Me", &["a-ha"], 244), "another music video"),
            (song("Take", &["a-ha"], 244), "a shorter title"),
        ] {
            assert!(!same_song(&take_on_me, &hit), "{why} isn't the song");
        }
        assert!(!same_song(&video("Love Story", &["Taylor Swift"], 236), &song("Love Story (Taylor's Version)", &["Taylor Swift"], 235)), "a re-recording isn't the song");
        assert!(!same_song(&video("Take On Me | Radio Edit", &["a-ha"], 225), &song("Take On Me", &["a-ha"], 226)), "a radio edit isn't the song");
        assert!(!same_song(&video("Take On Me", &[], 244), &song("Take On Me", &["a-ha"], 244)), "no artist to compare");
        assert!(same_song(&video("Funky Town", &["Lipps Inc."], 237), &song("Funky Town", &["Lipps Inc."], 471)));
        assert!(!same_song(&video("Ae Mere Humsafar", &["Alka Yagnik", "Udit Narayan"], 314), &song("Ae Mere Humsafar", &["Vinod Rathod", "Alka Yagnik"], 454)));
        assert!(same_song(&video("Michael Sembello - Maniac (From \"Flashdance\") (Official Audio)", &["Michael Sembello"], 245), &song("Maniac", &["Michael Sembello"], 245)));
        assert!(!same_song(&video("Daft Punk - Maniac", &["Michael Sembello"], 245), &song("Maniac", &["Michael Sembello"], 245)), "a lead that isn't the artist stays");
        assert!(!same_song(&video("Song | Acoustic Version", &["A"], 200), &song("Song", &["A"], 200)));
        assert!(!same_song(&video("Song (Official Acoustic Video)", &["A"], 200), &song("Song", &["A"], 200)));
        assert!(!same_song(&video("Song (Foo Remix)", &["A"], 200), &song("Song (Bar Remix)", &["A"], 200)));
        assert!(!same_song(&video("Bad Guy (Remix Official Video)", &["Billie Eilish"], 207), &song("Bad Guy", &["Billie Eilish"], 195)));
        assert!(!same_song(&video("WAP (Clean)", &["Cardi B"], 187), &song("WAP (Explicit)", &["Cardi B"], 187)));
        assert!(!same_song(&video("Running Up That Hill", &["Kate Bush"], 296), &song("Running Up That Hill (A Deal With God)", &["Kate Bush"], 299)));
        assert!(!same_song(&video("Bohemian Rhapsody", &["Queen"], 355), &song("Bohemian Rhapsody", &["Queen Tribute Band"], 355)));
        assert!(!same_song(&video("Duet", &["Singer A", "Singer B"], 200), &song("Duet", &["Singer A", "Singer C"], 200)));
        let linked = |name: &str| Link { name: name.into(), id: Some("UCx".into()) };
        assert!(same_song(&Track { artists: vec![linked("Ray Parker Jr.")], ..video("Ghostbusters", &[], 246) }, &Track { artists: vec![linked("Ray Parker, Jr.")], ..song("Ghostbusters", &[], 240) }));
        assert!(same_song(&video("Kisi Nazar Ko Tera Intezar", &["Asha Bhosle"], 452), &song("Kisi Nazar Ko Tera Intezar", &["Asha Bhosle | Bhupinder"], 405)));
        assert!(!same_song(&video("Take On Me", &["a"], 244), &song("Take On Me", &["a-ha"], 244)), "only whole names count");
    }

    #[test]
    fn premium_queues_pair_a_music_video_with_its_song() {
        let item = |id: &str, title: &str, kind: &str| {
            json!({"playlistPanelVideoRenderer": {"videoId": id, "title": {"runs": [{"text": title}]}, "lengthText": {"runs": [{"text": "3:34"}]},
                "longBylineText": {"runs": [{"text": "Rick Astley"}]},
                "navigationEndpoint": {"watchEndpoint": {"videoId": id, "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": kind}}}}}})
        };
        let wrap = |primary: Value, counterpart: Value| {
            json!({"playlistPanelVideoWrapperRenderer": {"primaryRenderer": primary, "counterpart": [{"counterpartRenderer": counterpart,
                "segmentMap": {"segment": [{"primaryVideoStartTimeMilliseconds": "0", "counterpartVideoStartTimeMilliseconds": "927", "durationMilliseconds": "212500"}]}}]}})
        };
        let v = json!({"contents": {"singleColumnMusicWatchNextResultsRenderer": {"tabbedRenderer": {"watchNextTabbedResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"musicQueueRenderer": {"content": {"playlistPanelRenderer": {"contents": [
            wrap(item("raBobo3GZYA", "Whenever You Need Somebody", "MUSIC_VIDEO_TYPE_ATV"), item("BeyEGebJ1l4", "Whenever You Need Somebody", "MUSIC_VIDEO_TYPE_OMV")),
            wrap(item("dQw4w9WgXcQ", "Never Gonna Give You Up", "MUSIC_VIDEO_TYPE_OMV"), item("lYBUbBu4W08", "Never Gonna Give You Up", "MUSIC_VIDEO_TYPE_ATV")),
        ]}}}}}}]}}}}});
        let found = parse_paired_song(&v, "dQw4w9WgXcQ").unwrap();
        assert_eq!((found.id.as_str(), found.duration, found.music_video), ("lYBUbBu4W08", Some(214), false));
        assert_eq!(parse_paired_song(&v, "raBobo3GZYA"), None);
        assert_eq!(parse_paired_song(&v, "BeyEGebJ1l4"), None);
    }

    #[test]
    fn a_radio_is_its_songs_or_its_videos_when_it_has_only_those() {
        let t = |id: &str| Track { id: id.into(), title: id.into(), artists: Vec::new(), album: None, duration: None, thumbs: Vec::new(), track_nr: None, plays: None, music_video: false };
        let ids = |items: &[(Track, bool)]| radio_tracks(items).map(|t| t.id.clone()).collect::<Vec<_>>();
        assert_eq!(ids(&[(t("a"), false), (t("v"), true), (t("b"), false)]), ["a", "b"]);
        assert_eq!(ids(&[(t("v1"), true), (t("v2"), true)]), ["v1", "v2"]);
        assert!(ids(&[]).is_empty());
    }

    #[test]
    fn list_rows_say_whether_they_are_music_videos() {
        let row = |kind: &str| list_item(&album_row("x", "Song", kind)["musicResponsiveListItemRenderer"]).unwrap().music_video;
        assert!(row("MUSIC_VIDEO_TYPE_OMV"));
        assert!(!row("MUSIC_VIDEO_TYPE_ATV"));
        assert!(!row("MUSIC_VIDEO_TYPE_UGC"));
    }

    #[tokio::test]
    #[ignore]
    async fn searched_song_live() {
        let api = crate::ytmapi::Api::new(&std::env::temp_dir().join("nuvx-album-songs-test"));
        for v in [
            video("Never Gonna Give You Up", &["Rick Astley"], 214),
            video("Take On Me (Official Video)", &["a-ha"], 244),
            video("Kisi Nazar Ko Tera Intezar", &["Asha Bhosle"], 452),
            video("Bad Guy", &["Billie Eilish"], 207),
        ] {
            let found = searched_song(&api, &v).await.unwrap();
            println!("{} -> {:?}", v.title, found.as_ref().map(|s| (&s.id, &s.title, s.artist_line(), s.duration)));
        }
    }
}
