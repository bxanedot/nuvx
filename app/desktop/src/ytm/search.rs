use super::*;

pub enum Filter {
    Songs,
    Albums,
    Artists,
    Playlists,
    Videos,
}

impl Filter {
    fn params(&self) -> &'static str {
        match self {
            Filter::Songs => "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D",
            Filter::Albums => "EgWKAQIYAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D",
            Filter::Artists => "EgWKAQIgAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D",
            Filter::Playlists => "EgeKAQQoADgBahIQAxAEEAkQDhAKEAUQERAQEBU%3D",
            Filter::Videos => "EgWKAQIQAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D",
        }
    }
}

pub async fn search_as(api: &Api, query: &str, filter: Filter, auth: bool) -> anyhow::Result<Vec<Value>> {
    let v = api.call("search", json!({ "query": query, "params": filter.params() }), auth).await?;
    let sections = &v["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"];
    Ok(arr(sections).iter().flat_map(|s| arr(&s["musicShelfRenderer"]["contents"]).iter().cloned()).collect())
}

pub async fn search_songs(api: &Api, query: &str, auth: bool) -> anyhow::Result<Vec<Track>> {
    Ok(search_as(api, query, Filter::Songs, auth).await?.iter().filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"])).collect())
}

pub async fn search_tracks_signed_in(api: &Api, query: &str, filter: Filter) -> anyhow::Result<Vec<Track>> {
    Ok(search_as(api, query, filter, true).await?.iter().filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"])).collect())
}

pub async fn search_cards(api: &Api, query: &str, filter: Filter, auth: bool) -> anyhow::Result<Vec<Card>> {
    Ok(search_as(api, query, filter, auth).await?.iter().filter_map(|i| list_card(&i["musicResponsiveListItemRenderer"])).collect())
}

pub async fn youtube_search(api: &Api, query: &str) -> anyhow::Result<Vec<Track>> {
    Ok(youtube_videos(&api.youtube_search(query).await?))
}

pub fn youtube_videos(v: &Value) -> Vec<Track> {
    let sections = &v["contents"]["twoColumnSearchResultsRenderer"]["primaryContents"]["sectionListRenderer"]["contents"];
    arr(sections)
        .iter()
        .flat_map(|s| arr(&s["itemSectionRenderer"]["contents"]))
        .filter_map(|i| {
            let r = &i["videoRenderer"];
            let id = r["videoId"].as_str()?.to_owned();
            let duration = r["lengthText"]["simpleText"].as_str().and_then(clock_secs)?;
            let owner = &r["ownerText"]["runs"][0];
            let artists = owner["text"].as_str().map(|name| vec![Link { name: name.to_owned(), id: None }]).unwrap_or_default();
            let thumbs = arr(&r["thumbnail"]["thumbnails"])
                .iter()
                .filter_map(|t| Some(Thumb { url: t["url"].as_str()?.to_owned(), width: t["width"].as_u64()? as u32, height: t["height"].as_u64()? as u32 }))
                .collect();
            Some(Track { id, title: text(&r["title"])?, artists, album: None, duration: Some(duration), thumbs, track_nr: None, plays: None, music_video: false })
        })
        .collect()
}

fn clock_secs(s: &str) -> Option<u32> {
    s.split(':').try_fold(0u32, |acc, p| Some(acc * 60 + p.trim().parse::<u32>().ok()?))
}

#[derive(Clone, Debug, PartialEq)]
pub struct Suggestion {
    pub text: String,
    pub forget: Option<String>,
}

pub async fn suggestions(api: &Api, input: &str, auth: bool) -> anyhow::Result<Vec<Suggestion>> {
    let v = api.call("music/get_search_suggestions", json!({ "input": input }), auth).await?;
    Ok(parse_suggestions(&v))
}

pub fn parse_suggestions(v: &Value) -> Vec<Suggestion> {
    let items = &v["contents"][0]["searchSuggestionsSectionRenderer"]["contents"];
    arr(items)
        .iter()
        .filter_map(|i| {
            let past = &i["historySuggestionRenderer"];
            let r = if past.is_null() { &i["searchSuggestionRenderer"] } else { past };
            Some(Suggestion {
                text: text(&r["suggestion"])?,
                forget: past["serviceEndpoint"]["feedbackEndpoint"]["feedbackToken"].as_str().map(str::to_owned),
            })
        })
        .collect()
}

pub async fn forget_search(api: &Api, token: &str) -> anyhow::Result<()> {
    let v = api.call_signed_in("feedback", json!({ "feedbackTokens": [token] })).await?;
    anyhow::ensure!(v["feedbackResponses"][0]["isProcessed"] == true, "YouTube Music didn't remove it");
    Ok(())
}
