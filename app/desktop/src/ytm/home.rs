use super::*;

pub struct HomePage {
    pub chips: Vec<Chip>,
    pub shelves: Vec<Shelf>,
    pub continuation: Option<String>,
}

pub async fn home(api: &Api, params: Option<&str>, auth: bool) -> anyhow::Result<HomePage> {
    let mut body = json!({ "browseId": "FEmusic_home" });
    if let Some(p) = params {
        body["params"] = json!(p);
    }
    Ok(parse_home(&api.call("browse", body, auth).await?))
}

fn parse_home(v: &Value) -> HomePage {
    let sec = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"];
    let chips = arr(&sec["header"]["chipCloudRenderer"]["chips"])
        .iter()
        .filter_map(|c| {
            let c = &c["chipCloudChipRenderer"];
            let selected = c["isSelected"].as_bool().unwrap_or(false);
            let ep = if selected { &c["onDeselectedCommand"] } else { &c["navigationEndpoint"] };
            Some(Chip { title: text(&c["text"])?, params: ep["browseEndpoint"]["params"].as_str().map(str::to_owned), selected })
        })
        .collect();
    HomePage { chips, shelves: shelves(&sec["contents"]), continuation: continuation(sec) }
}

pub async fn moods(api: &Api, auth: bool) -> anyhow::Result<Vec<MoodSection>> {
    Ok(parse_moods(&api.call("browse", json!({ "browseId": "FEmusic_moods_and_genres" }), auth).await?))
}

pub(super) fn parse_moods(v: &Value) -> Vec<MoodSection> {
    let sec = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    let parsed: Vec<MoodSection> = arr(sec)
        .iter()
        .filter_map(|s| {
            let g = &s["gridRenderer"];
            let title = text(&g["header"]["gridHeaderRenderer"]["title"])?;
            let items: Vec<Mood> = arr(&g["items"])
                .iter()
                .filter_map(|i| {
                    let b = &i["musicNavigationButtonRenderer"];
                    let click = &b["clickCommand"]["browseEndpoint"];
                    let ep = if click.is_null() { &b["navigationEndpoint"]["browseEndpoint"] } else { click };
                    let stripe = &b["solid"]["leftStripeColor"];
                    Some(Mood {
                        title: text(&b["buttonText"])?,
                        browse_id: ep["browseId"].as_str()?.to_owned(),
                        params: ep["params"].as_str().map(str::to_owned),
                        color: stripe.as_u64().or_else(|| stripe.as_str()?.parse().ok()).map(|c| (c & 0xFF_FFFF) as u32),
                        thumb: None,
                    })
                })
                .collect();
            (!items.is_empty()).then_some(MoodSection { title, items })
        })
        .collect();
    let later = |i: usize, m: &Mood| parsed[i + 1..].iter().any(|s| s.items.iter().any(|x| x.browse_id == m.browse_id && x.params == m.params));
    parsed
        .iter()
        .enumerate()
        .filter(|(i, s)| !s.title.eq_ignore_ascii_case("For you") && !s.items.iter().any(|m| later(*i, m)))
        .map(|(_, s)| s.clone())
        .collect()
}

pub async fn mood_art(api: &Api, sections: &mut [MoodSection], auth: bool) {
    use futures_util::{StreamExt, stream};
    let keys: Vec<(String, Option<String>)> = sections.iter().flat_map(|s| &s.items).map(|m| (m.browse_id.clone(), m.params.clone())).collect();
    let found: Vec<_> = stream::iter(keys)
        .map(|(id, params)| async move {
            let page = mood_page(api, &id, params.as_deref(), auth).await.ok()?;
            let url = page.shelves.iter().find_map(|s| match &s.kind {
                ShelfKind::Cards(cards) => cards.iter().find_map(|c| crate::model::pick_thumb(&c.thumbs, 300)),
                ShelfKind::Songs { tracks, .. } => tracks.iter().find_map(|t| crate::model::pick_thumb(&t.thumbs, 300)),
            })?;
            Some((id, params, url.url.clone()))
        })
        .buffer_unordered(4)
        .filter_map(std::future::ready)
        .collect()
        .await;
    for m in sections.iter_mut().flat_map(|s| &mut s.items) {
        m.thumb = found.iter().find(|(id, p, _)| *id == m.browse_id && *p == m.params).map(|f| f.2.clone());
    }
}

pub async fn podcasts(api: &Api, auth: bool) -> anyhow::Result<MoodPage> {
    let params = home(api, None, auth).await?.chips.into_iter().find(|c| c.title.eq_ignore_ascii_case("podcasts")).and_then(|c| c.params);
    let shelves = if let Some(params) = params {
        let shelves = home(api, Some(&params), auth).await?.shelves;
        if !shelves.is_empty() { shelves } else { podcast_search_shelf(youtube_search(api, "podcast full episodes").await?) }
    } else {
        podcast_search_shelf(youtube_search(api, "podcast full episodes").await?)
    };
    Ok(MoodPage { browse_id: PODCASTS.into(), params: None, title: Some("Podcasts".into()), shelves })
}

pub(super) fn podcast_search_shelf(tracks: Vec<Track>) -> Vec<Shelf> {
    if tracks.is_empty() { return vec![]; }
    vec![Shelf { title: "Podcasts on YouTube".into(), strapline: Some("Long-form episodes".into()), strap_thumb: None, kind: ShelfKind::Songs { rows: 1, tracks } }]
}

pub const PODCASTS: &str = "podcasts";

pub async fn mood_page(api: &Api, browse_id: &str, params: Option<&str>, auth: bool) -> anyhow::Result<MoodPage> {
    let mut body = json!({ "browseId": browse_id });
    if let Some(p) = params {
        body["params"] = json!(p);
    }
    let v = api.call("browse", body, auth).await?;
    let sec = &v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    let shelves = arr(sec)
        .iter()
        .filter_map(|s| {
            shelf(s).or_else(|| {
                let g = &s["gridRenderer"];
                let title = text(&g["header"]["gridHeaderRenderer"]["title"])?;
                let cards: Vec<Card> = arr(&g["items"]).iter().filter_map(|i| two_row(&i["musicTwoRowItemRenderer"])).collect();
                (!cards.is_empty()).then_some(Shelf { title, strapline: None, strap_thumb: None, kind: ShelfKind::Cards(cards) })
            })
        })
        .collect();
    Ok(MoodPage { browse_id: browse_id.to_owned(), params: params.map(str::to_owned), title: text(&v["header"]["musicHeaderRenderer"]["title"]), shelves })
}

pub async fn home_more(api: &Api, token: &str, auth: bool) -> anyhow::Result<HomePage> {
    let v = api.call("browse", json!({ "continuation": token }), auth).await?;
    let sec = &v["continuationContents"]["sectionListContinuation"];
    Ok(HomePage { chips: vec![], shelves: shelves(&sec["contents"]), continuation: continuation(sec) })
}
