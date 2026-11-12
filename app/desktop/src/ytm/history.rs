use super::*;

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
