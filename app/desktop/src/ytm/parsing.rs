use super::*;

pub(super) fn find_key<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    match v {
        Value::Object(m) => m.get(key).or_else(|| m.values().find_map(|x| find_key(x, key))),
        Value::Array(a) => a.iter().find_map(|x| find_key(x, key)),
        _ => None,
    }
}

pub(super) fn continuation(sec: &Value) -> Option<String> {
    sec["continuations"][0]["nextContinuationData"]["continuation"].as_str().map(str::to_owned)
}

pub(super) fn shelves(contents: &Value) -> Vec<Shelf> {
    arr(contents).iter().filter_map(shelf).collect()
}

pub(super) fn shelf(s: &Value) -> Option<Shelf> {
    let r = &s["musicCarouselShelfRenderer"];
    if r.is_null() {
        return None;
    }
    let h = &r["header"]["musicCarouselShelfBasicHeaderRenderer"];
    let title = text(&h["title"])?;
    let strapline = text(&h["strapline"]);
    let strap_thumb = thumbs(&h["thumbnail"]["musicThumbnailRenderer"]).into_iter().next().map(|t| t.url);
    let items: Vec<&Value> = arr(&r["contents"]).iter().filter(|i| !is_video(i)).collect();
    let kind = if items.iter().any(|i| !i["musicMultiRowListItemRenderer"].is_null()) {
        let tracks: Vec<Track> = items.iter().filter_map(|i| episode(&i["musicMultiRowListItemRenderer"])).collect();
        ShelfKind::Songs { tracks, rows: 4 }
    } else if items.iter().any(|i| !i["musicResponsiveListItemRenderer"].is_null()) {
        let tracks: Vec<Track> = items.iter().filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"])).collect();
        let rows = r["numItemsPerColumn"].as_str().and_then(|n| n.parse().ok()).unwrap_or(4);
        ShelfKind::Songs { tracks, rows }
    } else {
        ShelfKind::Cards(items.iter().filter_map(|i| two_row(&i["musicTwoRowItemRenderer"])).collect())
    };
    let empty = match &kind {
        ShelfKind::Cards(c) => c.is_empty(),
        ShelfKind::Songs { tracks, .. } => tracks.is_empty(),
    };
    (!empty).then_some(Shelf { title, strapline, strap_thumb, kind })
}

pub(super) fn episode(r: &Value) -> Option<Track> {
    let id = r["onTap"]["watchEndpoint"]["videoId"].as_str()?.to_owned();
    let title = text(&r["title"])?;
    let artists = text(&r["secondTitle"]).map(|name| vec![Link { name, id: None }]).unwrap_or_default();
    let duration = text(&r["playbackProgress"]["musicPlaybackProgressRenderer"]["durationText"]).and_then(|d| spoken_secs(&d));
    Some(Track { id, title, artists, album: None, duration, thumbs: thumbs(&r["thumbnail"]["musicThumbnailRenderer"]), track_nr: None, plays: None, music_video: false })
}

pub(super) fn spoken_secs(s: &str) -> Option<u32> {
    let mut total = 0;
    let mut n = None;
    for w in s.split_whitespace() {
        match (w.parse::<u32>(), n) {
            (Ok(v), _) => n = Some(v),
            (_, Some(v)) if w.starts_with("hr") || w.starts_with("hour") => total += v * 3600,
            (_, Some(v)) if w.starts_with("min") => total += v * 60,
            (_, Some(v)) if w.starts_with("sec") => total += v,
            _ => continue,
        }
        if w.parse::<u32>().is_err() {
            n = None;
        }
    }
    (total > 0).then_some(total)
}

pub(super) fn two_row(r: &Value) -> Option<Card> {
    let title = text(&r["title"])?;
    let subtitle = text(&r["subtitle"]).unwrap_or_default();
    let thumbs = thumbs(&r["thumbnailRenderer"]["musicThumbnailRenderer"]);
    let nav = &r["navigationEndpoint"];
    let target = if let Some(video_id) = nav["watchEndpoint"]["videoId"].as_str() {
        Target::Song(Track {
            id: video_id.to_owned(),
            title: title.clone(),
            artists: artist_links(&r["subtitle"]),
            album: None,
            duration: None,
            thumbs: thumbs.clone(),
            track_nr: None,
            plays: None,
            music_video: music_video(&[nav]),
        })
    } else if let Some(playlist) = nav["watchPlaylistEndpoint"]["playlistId"].as_str() {
        Target::Playlist(playlist.to_owned())
    } else {
        target(nav)?
    };
    let round = matches!(target, Target::Artist(_));
    Some(Card { title, subtitle, thumbs, target, round, links: artist_links(&r["subtitle"]) })
}

pub(super) fn target(nav: &Value) -> Option<Target> {
    let id = nav["browseEndpoint"]["browseId"].as_str()?.to_owned();
    Some(match page_type(nav)? {
        "MUSIC_PAGE_TYPE_ALBUM" | "MUSIC_PAGE_TYPE_AUDIOBOOK" => Target::Album(id),
        "MUSIC_PAGE_TYPE_ARTIST" | "MUSIC_PAGE_TYPE_USER_CHANNEL" => Target::Artist(id),
        "MUSIC_PAGE_TYPE_PLAYLIST" => Target::Playlist(id.strip_prefix("VL").unwrap_or(&id).to_owned()),
        _ => return None,
    })
}

pub(super) fn list_card(r: &Value) -> Option<Card> {
    let target = target(&r["navigationEndpoint"])?;
    let col = |i: usize| &r["flexColumns"][i]["musicResponsiveListItemFlexColumnRenderer"]["text"];
    let title = text(col(0))?;
    let detail = col(1);
    let line: String = runs(detail).filter_map(|r| r["text"].as_str()).collect();
    let mut parts: Vec<&str> = line.split(" • ").filter(|p| !p.is_empty()).collect();
    if matches!(target, Target::Artist(_)) && parts.first() == Some(&"Artist") {
        parts.remove(0);
    }
    let subtitle = match &target {
        Target::Playlist(_) => std::iter::once("Playlist").chain(parts.iter().copied().filter(|p| *p != "Playlist")).collect::<Vec<_>>().join(" • "),
        Target::Artist(_) if parts.is_empty() => "Artist".into(),
        _ => parts.join(" • "),
    };
    let round = matches!(target, Target::Artist(_));
    Some(Card { title, subtitle, thumbs: thumbs(&r["thumbnail"]["musicThumbnailRenderer"]), target, round, links: artist_links(detail) })
}

pub(super) fn is_video(item: &Value) -> bool {
    let (two, row) = (&item["musicTwoRowItemRenderer"], &item["musicResponsiveListItemRenderer"]);
    let own = [&two["navigationEndpoint"], &row["overlay"], &row["flexColumns"][0], &item["navigationEndpoint"]];
    matches!(video_type(&own), Some("MUSIC_VIDEO_TYPE_OMV" | "MUSIC_VIDEO_TYPE_UGC" | "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"))
}

pub(super) fn video_type<'a>(parts: &[&'a Value]) -> Option<&'a str> {
    fn find(v: &Value) -> Option<&str> {
        match v {
            Value::Object(m) => m.get("musicVideoType").and_then(Value::as_str).or_else(|| m.values().find_map(find)),
            Value::Array(a) => a.iter().find_map(find),
            _ => None,
        }
    }
    parts.iter().find_map(|p| find(p))
}

pub(super) fn music_video(parts: &[&Value]) -> bool {
    video_type(parts) == Some("MUSIC_VIDEO_TYPE_OMV")
}

pub(super) fn list_item(r: &Value) -> Option<Track> {
    let cols = arr(&r["flexColumns"]);
    let col = |i: usize| cols.get(i).map(|c| &c["musicResponsiveListItemFlexColumnRenderer"]["text"]);
    let title = text(col(0)?)?;
    let id = r["playlistItemData"]["videoId"]
        .as_str()
        .or_else(|| runs(col(0)?).find_map(|run| run["navigationEndpoint"]["watchEndpoint"]["videoId"].as_str()))?
        .to_owned();
    let mut artists = Vec::new();
    let mut album = None;
    let mut duration = None;
    let mut plays = None;
    let mut in_artists = true;
    for c in 1..cols.len() {
        for (i, run) in runs(col(c)?).enumerate() {
            let name = run["text"].as_str().unwrap_or_default();
            let kind = page_type(run);
            if c == 1 && in_artists {
                match name.trim() {
                    "•" => in_artists = false,
                    "" | "," | "&" => {}
                    "Song" | "Video" | "Episode" if i == 0 => {}
                    n if kind.is_none_or(|k| k == "MUSIC_PAGE_TYPE_ARTIST" || k == "MUSIC_PAGE_TYPE_USER_CHANNEL") && !looks_like_duration(n) && !n.ends_with(" plays") => {
                        artists.push(Link { name: n.to_owned(), id: browse_id(run) })
                    }
                    _ => {}
                }
                if in_artists {
                    continue;
                }
            }
            match kind {
                Some("MUSIC_PAGE_TYPE_ARTIST" | "MUSIC_PAGE_TYPE_USER_CHANNEL") if !artists.iter().any(|a| a.name == name) => {
                    artists.push(Link { name: name.to_owned(), id: browse_id(run) })
                }
                Some("MUSIC_PAGE_TYPE_ALBUM") => album = Some(Link { name: name.to_owned(), id: browse_id(run) }),
                _ if duration.is_none() && looks_like_duration(name) => duration = parse_duration(name),
                _ if name.ends_with(" plays") => plays = count(name),
                _ => {}
            }
        }
        in_artists = false;
    }
    if let Some(d) = r["fixedColumns"][0]["musicResponsiveListItemFixedColumnRenderer"]["text"]["runs"][0]["text"].as_str().and_then(parse_duration) {
        duration = Some(d);
    }
    let track_nr = text(&r["index"]).and_then(|n| n.parse().ok());
    let music_video = music_video(&[&r["overlay"], &r["flexColumns"][0]]);
    Some(Track { id, title, artists, album, duration, thumbs: thumbs(&r["thumbnail"]["musicThumbnailRenderer"]), track_nr, plays, music_video })
}

pub(super) fn panel_item(r: &Value) -> Option<Track> {
    let id = r["videoId"].as_str()?.to_owned();
    let title = text(&r["title"])?;
    let byline = &r["longBylineText"];
    let mut artists = artist_links(byline);
    if artists.is_empty()
        && let Some(name) = runs(byline).next().and_then(|r| r["text"].as_str())
    {
        artists.push(Link { name: name.to_owned(), id: None });
    }
    let album = runs(byline)
        .find(|run| page_type(run) == Some("MUSIC_PAGE_TYPE_ALBUM"))
        .map(|run| Link { name: run["text"].as_str().unwrap_or_default().to_owned(), id: browse_id(run) });
    Some(Track {
        id,
        title,
        artists,
        album,
        duration: text(&r["lengthText"]).as_deref().and_then(parse_duration),
        thumbs: thumbs(r),
        track_nr: None,
        plays: None,
        music_video: music_video(&[&r["navigationEndpoint"]]),
    })
}

pub(super) fn artist_links(v: &Value) -> Vec<Link> {
    runs(v)
        .filter(|run| matches!(page_type(run), Some("MUSIC_PAGE_TYPE_ARTIST" | "MUSIC_PAGE_TYPE_USER_CHANNEL")))
        .map(|run| Link { name: run["text"].as_str().unwrap_or_default().to_owned(), id: browse_id(run) })
        .collect()
}

pub(super) fn links(v: &Value) -> Vec<Link> {
    runs(v)
        .filter_map(|run| {
            let name = run["text"].as_str()?.trim();
            (!name.is_empty() && !matches!(name, "•" | "," | "&")).then(|| Link { name: name.to_owned(), id: browse_id(run) })
        })
        .collect()
}

pub(super) fn looks_like_duration(s: &str) -> bool {
    s.contains(':') && s.split(':').all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
}

pub(super) fn parse_duration(s: &str) -> Option<u32> {
    s.split(':').try_fold(0u32, |acc, p| Some(acc * 60 + p.trim().parse::<u32>().ok()?))
}

pub(super) fn count(s: &str) -> Option<u64> {
    let n = s.split_whitespace().next()?.replace(',', "");
    let (num, mult) = match n.chars().last()? {
        'K' => (&n[..n.len() - 1], 1e3),
        'M' => (&n[..n.len() - 1], 1e6),
        'B' => (&n[..n.len() - 1], 1e9),
        _ => (n.as_str(), 1.0),
    };
    Some((num.parse::<f64>().ok()? * mult).round() as u64)
}

pub(super) fn arr(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or_default()
}

pub(super) fn runs(v: &Value) -> impl Iterator<Item = &Value> {
    arr(&v["runs"]).iter()
}

pub(super) fn text(v: &Value) -> Option<String> {
    let s: String = runs(v).filter_map(|r| r["text"].as_str()).collect();
    (!s.is_empty()).then_some(s)
}

pub(super) fn thumbs(r: &Value) -> Vec<Thumb> {
    arr(&r["thumbnail"]["thumbnails"])
        .iter()
        .filter_map(|t| {
            Some(Thumb {
                url: t["url"].as_str()?.to_owned(),
                width: t["width"].as_u64().unwrap_or(0) as u32,
                height: t["height"].as_u64().unwrap_or(0) as u32,
            })
        })
        .collect()
}

pub(super) fn page_type(v: &Value) -> Option<&str> {
    let nav = if v["navigationEndpoint"].is_null() { v } else { &v["navigationEndpoint"] };
    nav["browseEndpoint"]["browseEndpointContextSupportedConfigs"]["browseEndpointContextMusicConfig"]["pageType"].as_str()
}

pub(super) fn browse_id(run: &Value) -> Option<String> {
    run["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str().map(str::to_owned)
}
