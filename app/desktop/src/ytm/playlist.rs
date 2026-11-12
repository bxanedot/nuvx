use super::*;

pub async fn playlist(api: &Api, id: &str, auth: bool, limit: Option<usize>) -> anyhow::Result<Collection> {
    let (mut pl, mut token, limit) = playlist_start(api, id, auth, limit).await?;
    while pl.tracks.len() < limit {
        let Some(t) = token.take() else { break };
        let (more, ids, next) = playlist_more(api, &t, auth).await?;
        if more.is_empty() {
            break;
        }
        pl.tracks.extend(more);
        pl.set_ids.extend(ids);
        token = next;
    }
    pl.tracks.truncate(limit);
    pl.set_ids.truncate(limit);
    Ok(pl)
}

pub async fn playlist_start(api: &Api, id: &str, auth: bool, limit: Option<usize>) -> anyhow::Result<(Collection, Option<String>, usize)> {
    let v = api.call("browse", json!({ "browseId": format!("VL{id}") }), auth).await?;
    let Some((mut pl, token, count)) = parse_playlist(&v, id) else {
        anyhow::ensure!(!(auth && api.account().is_some() && crate::ytmapi::logged_out(&v)), "Your YouTube sign-in has expired");
        anyhow::bail!("YouTube Music returned an empty playlist");
    };
    let all = count.map_or(if id.starts_with("RD") { 500 } else { usize::MAX }, |c| c as usize);
    let limit = limit.map_or(all, |l| l.min(all));
    pl.tracks.truncate(limit);
    pl.set_ids.truncate(limit);
    Ok((pl, token, limit))
}

pub async fn playlist_more(api: &Api, token: &str, auth: bool) -> anyhow::Result<(Vec<Track>, Vec<Option<String>>, Option<String>)> {
    let v = api.call("browse", json!({ "continuation": token }), auth).await?;
    Ok(playlist_continuation(&v))
}

pub(super) fn parse_playlist(v: &Value, id: &str) -> Option<(Collection, Option<String>, Option<u64>)> {
    let two = &v["contents"]["twoColumnBrowseResultsRenderer"];
    let h = header(two)?;
    let shelf = &two["secondaryContents"]["sectionListRenderer"]["contents"][0];
    let shelf = if shelf["musicPlaylistShelfRenderer"].is_null() { &shelf["musicShelfRenderer"] } else { &shelf["musicPlaylistShelfRenderer"] };
    let (tracks, set_ids, token) = list_page(arr(&shelf["contents"]));
    let token = token.or_else(|| continuation(shelf));
    let face = &h["facepile"]["avatarStackViewModel"];
    let owner = face["text"]["content"].as_str().map(|name| Link {
        name: name.to_owned(),
        id: face["rendererContext"]["commandContext"]["onTap"]["innertubeCommand"]["browseEndpoint"]["browseId"].as_str().map(str::to_owned),
    });
    let owners: Vec<Link> = owner.into_iter().chain(links(&h["straplineTextOne"])).collect();
    let count = runs(&h["secondSubtitle"])
        .filter_map(|r| r["text"].as_str())
        .find(|t| ["song", "track", "episode", "video"].iter().any(|w| t.contains(w)))
        .and_then(|t| t.split_whitespace().next()?.replace(',', "").parse::<u64>().ok());
    let sub: Vec<String> = owners.first().map(|o| o.name.clone()).into_iter().collect();
    let count = count.or(Some(tracks.len() as u64));
    let thumb = &h["thumbnail"];
    let thumb = if thumb["musicThumbnailRenderer"].is_null() { &thumb["croppedSquareThumbnailRenderer"] } else { &thumb["musicThumbnailRenderer"] };
    Some((
        Collection {
            id: id.to_owned(),
            is_album: false,
            save_id: None,
            title: text(&h["title"])?,
            subtitle: sub.join(" • "),
            count,
            artists: owners.into_iter().filter(|o| o.id.is_some()).collect(),
            description: text(&h["description"]["musicDescriptionShelfRenderer"]["description"]),
            thumbs: thumbs(thumb),
            tracks,
            set_ids,
            owned: owned(two),
        },
        token,
        count,
    ))
}

fn owned(two: &Value) -> bool {
    !two["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0]["musicEditablePlaylistDetailHeaderRenderer"].is_null()
}

fn playlist_continuation(v: &Value) -> (Vec<Track>, Vec<Option<String>>, Option<String>) {
    let items = &v["onResponseReceivedActions"][0]["appendContinuationItemsAction"]["continuationItems"];
    if !items.is_null() {
        return list_page(arr(items));
    }
    let shelf = &v["continuationContents"]["musicPlaylistShelfContinuation"];
    let (tracks, ids, token) = list_page(arr(&shelf["contents"]));
    (tracks, ids, token.or_else(|| continuation(shelf)))
}

fn list_page(items: &[Value]) -> (Vec<Track>, Vec<Option<String>>, Option<String>) {
    let (tracks, ids) = items
        .iter()
        .filter_map(|i| {
            let r = &i["musicResponsiveListItemRenderer"];
            Some((list_item(r)?, r["playlistItemData"]["playlistSetVideoId"].as_str().map(str::to_owned)))
        })
        .unzip();
    (tracks, ids, item_token(items))
}
