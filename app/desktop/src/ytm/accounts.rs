use super::*;

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

pub(super) fn parse_channels(v: &Value) -> Vec<Channel> {
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
