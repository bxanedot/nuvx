use super::*;

pub async fn album(api: &Api, id: &str) -> anyhow::Result<Collection> {
    let v = api.call("browse", json!({ "browseId": id }), false).await?;
    let (mut a, videos) = parse_album(&v, id).ok_or_else(|| anyhow::anyhow!("YouTube Music returned an empty album"))?;
    if !videos.is_empty() {
        songs_for_videos(&mut a, &videos).await;
    }
    Ok(a)
}

pub async fn paired_song(api: &Api, video_id: &str) -> anyhow::Result<Option<Track>> {
    let body = json!({"videoId": video_id, "isAudioOnly": true, "enablePersistentPlaylistPanel": true});
    let v = api.call_signed_in("next", body).await?;
    Ok(parse_paired_song(&v, video_id))
}

pub(super) fn parse_paired_song(v: &Value, video_id: &str) -> Option<Track> {
    let panel = &v["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]
        ["tabRenderer"]["content"]["musicQueueRenderer"]["content"]["playlistPanelRenderer"];
    arr(&panel["contents"])
        .iter()
        .map(|i| &i["playlistPanelVideoWrapperRenderer"])
        .filter(|w| w["primaryRenderer"]["playlistPanelVideoRenderer"]["videoId"] == video_id)
        .flat_map(|w| arr(&w["counterpart"]))
        .map(|c| &c["counterpartRenderer"]["playlistPanelVideoRenderer"])
        .find(|r| video_type(&[&r["navigationEndpoint"]]) == Some("MUSIC_VIDEO_TYPE_ATV"))
        .and_then(panel_item)
}

pub async fn searched_song(api: &Api, video: &Track) -> anyhow::Result<Option<Track>> {
    let title = without_artist(&song_title(&video.title), video);
    let query = match video.artists.first() {
        Some(a) => format!("{title} {}", a.name),
        None => return Ok(None),
    };
    let hits = search_songs(api, &query, false).await?;
    Ok(hits.into_iter().take(5).find(|h| same_song(video, h)))
}

async fn songs_for_videos(a: &mut Collection, videos: &[usize]) {
    let Some(list) = a.save_id.clone().filter(|l| l.starts_with("OLAK")) else { return };
    let songs = match crate::innertube::album_queue(&crate::proxy::http(), &list).await {
        Ok(q) => album_songs(&q),
        Err(e) => {
            log::debug!("album {}: Android VR song list failed: {e:#}", a.id);
            None
        }
    };
    let Some(songs) = songs.filter(|s| s.len() == a.tracks.len()) else {
        log::info!("album {}: no song list that lines up with its {} rows; playing as listed", a.id, a.tracks.len());
        return;
    };
    let swapped = swap_songs(a, videos, &songs);
    log::info!("album {}: {swapped} of {} music videos swapped for the songs", a.id, videos.len());
}

#[derive(Debug, PartialEq)]
pub(super) struct AlbumSong {
    id: String,
    title: String,
    duration: Option<u32>,
}

pub(super) fn album_songs(v: &Value) -> Option<Vec<AlbumSong>> {
    let list = &v["contents"]["singleColumnWatchNextResults"]["playlist"]["playlist"];
    if !list["title"].as_str()?.starts_with("Album") {
        return None;
    }
    arr(&list["contents"])
        .iter()
        .map(|e| {
            let r = &e["playlistPanelVideoRenderer"];
            Some(AlbumSong {
                id: r["videoId"].as_str()?.to_owned(),
                title: text(&r["title"])?,
                duration: text(&r["lengthText"]).and_then(|l| parse_duration(&l)),
            })
        })
        .collect()
}

pub(super) fn swap_songs(a: &mut Collection, videos: &[usize], songs: &[AlbumSong]) -> usize {
    let mut swapped = 0;
    for &i in videos {
        let (Some(t), Some(s)) = (a.tracks.get_mut(i), songs.get(i)) else { continue };
        if t.id != s.id && same_title(&t.title, &s.title) {
            (t.id, t.duration) = (s.id.clone(), s.duration.or(t.duration));
            t.music_video = false;
            swapped += 1;
        }
    }
    swapped
}

pub(super) fn parse_album(v: &Value, id: &str) -> Option<(Collection, Vec<usize>)> {
    let two = &v["contents"]["twoColumnBrowseResultsRenderer"];
    let h = header(two)?;
    let title = text(&h["title"])?;
    let words: Vec<String> = runs(&h["subtitle"]).filter_map(|r| r["text"].as_str()).filter(|t| t.trim() != "•").map(str::to_owned).collect();
    let kind = words.first().cloned().unwrap_or_else(|| "Album".into());
    let year = words.iter().rev().find(|w| w.len() == 4 && w.bytes().all(|b| b.is_ascii_digit())).cloned();
    let artists = links(&h["straplineTextOne"]);
    let names = artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ");
    let subtitle = [Some(kind), (!names.is_empty()).then_some(names), year].into_iter().flatten().collect::<Vec<_>>().join(" • ");
    let thumbs = thumbs(&h["thumbnail"]["musicThumbnailRenderer"]);
    let link = Link { name: title.clone(), id: Some(id.to_owned()) };
    let rows = arr(&two["secondaryContents"]["sectionListRenderer"]["contents"]).iter().flat_map(|s| arr(&s["musicShelfRenderer"]["contents"]));
    let rows: Vec<(Track, bool)> = rows.filter_map(|i| Some((list_item(&i["musicResponsiveListItemRenderer"])?, is_video(i)))).collect();
    let videos = rows.iter().enumerate().filter(|(_, (_, video))| *video).map(|(i, _)| i).collect();
    let tracks = rows
        .into_iter()
        .map(|(mut t, _)| {
            if t.thumbs.is_empty() {
                t.thumbs = thumbs.clone();
            }
            if t.artists.is_empty() {
                t.artists = artists.clone();
            }
            t.album.get_or_insert_with(|| link.clone());
            t
        })
        .collect();
    let save_id = arr(&h["buttons"]).iter().find_map(|b| {
        let play = &b["musicPlayButtonRenderer"]["playNavigationEndpoint"];
        [&play["watchPlaylistEndpoint"], &play["watchEndpoint"], &b["downloadButtonRenderer"]["command"]["offlinePlaylistEndpoint"]]
            .iter()
            .find_map(|e| e["playlistId"].as_str())
            .map(str::to_owned)
    });
    let a = Collection {
        id: id.to_owned(),
        is_album: true,
        save_id,
        title,
        subtitle,
        artists,
        description: text(&h["description"]["musicDescriptionShelfRenderer"]["description"]),
        thumbs,
        tracks,
        set_ids: Vec::new(),
        count: None,
        owned: false,
    };
    Some((a, videos))
}
