//! The Nuvx Sync replay rules and local-change diff, ported from `SyncReplay.kt` and `SyncEngine.diff`.
//! Every device replays the same log in `seq` order, so every device ends with the same playlists.
//! `core/sync/fixtures/replay-v1.json` pins the rules for every client.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Op {
    pub seq: u64,
    #[serde(rename = "id")]
    pub op_id: String,
    #[serde(rename = "deviceId")]
    pub device_id: String,
    pub kind: String,
    pub entity: String,
    pub payload: Value,
    #[serde(rename = "clientMs")]
    pub client_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Playlist {
    pub id: String,
    pub title: String,
    pub tracks: Vec<String>,
}

/// A change not yet in the log: what `diff` produces.
#[derive(Clone, Debug, PartialEq)]
pub struct NewOp {
    pub kind: &'static str,
    pub entity: String,
    pub payload: Value,
}

/// The track facts that travel in an add-track payload.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Meta {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(
        default,
        rename = "thumbnailUrl",
        skip_serializing_if = "Option::is_none"
    )]
    pub thumbnail: Option<String>,
}

fn s<'a>(p: &'a Value, key: &str) -> Option<&'a str> {
    p.get(key).and_then(Value::as_str)
}

/// Playlists in first-created order, as `SyncReplay.replay` builds them.
pub fn replay(ops: &[Op]) -> Vec<Playlist> {
    let mut sorted: Vec<&Op> = ops.iter().collect();
    sorted.sort_by_key(|o| o.seq);
    let mut order: Vec<String> = Vec::new();
    let mut lists: HashMap<String, Playlist> = HashMap::new();
    let mut deleted: HashSet<String> = HashSet::new();
    for op in sorted {
        let target = op
            .entity
            .split_once(':')
            .map_or(op.entity.as_str(), |(_, t)| t)
            .to_owned();
        let p = &op.payload;
        match op.kind.as_str() {
            "playlist.create" => {
                if !deleted.contains(&target) && !lists.contains_key(&target) {
                    order.push(target.clone());
                    lists.insert(
                        target.clone(),
                        Playlist {
                            id: target,
                            title: s(p, "title").unwrap_or("").to_owned(),
                            tracks: vec![],
                        },
                    );
                }
            }
            "playlist.rename" => {
                if let (Some(list), Some(t)) = (lists.get_mut(&target), s(p, "title")) {
                    list.title = t.to_owned();
                }
            }
            "playlist.delete" => {
                lists.remove(&target);
                order.retain(|id| id != &target);
                // A stale or delayed create cannot resurrect a deleted ID.
                deleted.insert(target);
            }
            "playlist.addTrack" | "playlist.moveTrack" => {
                let (Some(list), Some(track)) = (lists.get_mut(&target), s(p, "videoId")) else {
                    continue;
                };
                let present = list.tracks.iter().any(|t| t == track);
                if (op.kind == "playlist.addTrack" && present)
                    || (op.kind == "playlist.moveTrack" && !present)
                {
                    continue;
                }
                let mut rest: Vec<String> = list
                    .tracks
                    .iter()
                    .filter(|t| *t != track)
                    .cloned()
                    .collect();
                let at = match p.get("after") {
                    None => rest.len(),
                    Some(Value::Null) => 0,
                    Some(a) => a
                        .as_str()
                        .and_then(|a| rest.iter().position(|t| t == a))
                        .map_or(rest.len(), |i| i + 1),
                };
                rest.insert(at, track.to_owned());
                list.tracks = rest;
            }
            "playlist.removeTrack" => {
                if let (Some(list), Some(track)) = (lists.get_mut(&target), s(p, "videoId")) {
                    list.tracks.retain(|t| t != track);
                }
            }
            _ => {} // unknown kinds are skipped, so an older client never corrupts a newer one's state
        }
    }
    order
        .into_iter()
        .filter_map(|id| lists.remove(&id))
        .collect()
}

/// Ops that turn `before` into `after`; replaying them on `before` gives `after` (same rules as `SyncEngine.diff`).
pub fn diff(before: &[Playlist], after: &[(Playlist, HashMap<String, Meta>)]) -> Vec<NewOp> {
    let mut ops = Vec::new();
    let mut op = |kind: &'static str, playlist: &str, payload: Value| {
        ops.push(NewOp {
            kind,
            entity: format!("playlist:{playlist}"),
            payload,
        })
    };
    let old: HashMap<&str, &Playlist> = before.iter().map(|p| (p.id.as_str(), p)).collect();
    let keep: HashSet<&str> = after.iter().map(|(p, _)| p.id.as_str()).collect();
    for p in before {
        if !keep.contains(p.id.as_str()) {
            op("playlist.delete", &p.id, json!({}));
        }
    }
    for (p, meta) in after {
        let was = old.get(p.id.as_str());
        match was {
            None => op("playlist.create", &p.id, json!({ "title": p.title })),
            Some(w) if w.title != p.title => {
                op("playlist.rename", &p.id, json!({ "title": p.title }))
            }
            _ => {}
        }
        let mut target: Vec<&str> = Vec::new();
        for t in &p.tracks {
            if !target.contains(&t.as_str()) {
                target.push(t);
            }
        }
        let mut work: Vec<String> = was.map(|w| w.tracks.clone()).unwrap_or_default();
        for gone in work
            .clone()
            .iter()
            .filter(|t| !target.contains(&t.as_str()))
        {
            work.retain(|t| t != gone);
            op("playlist.removeTrack", &p.id, json!({ "videoId": gone }));
        }
        for (i, id) in target.iter().enumerate() {
            let want_after = (i > 0).then(|| target[i - 1]);
            let at = work.iter().position(|t| t == id);
            let now_after = at.filter(|&a| a > 0).map(|a| work[a - 1].as_str());
            if at.is_some() && now_after == want_after {
                continue;
            }
            if let Some(a) = at {
                work.remove(a);
            }
            let to = match want_after {
                None => 0,
                Some(w) => work
                    .iter()
                    .position(|t| t == w)
                    .map_or(work.len(), |x| x + 1),
            };
            work.insert(to, (*id).to_owned());
            let mut payload = Map::new();
            payload.insert("videoId".into(), json!(id));
            payload.insert("after".into(), want_after.map_or(Value::Null, |w| json!(w)));
            if at.is_none() {
                let m = meta.get(*id).cloned().unwrap_or_default();
                payload.insert("title".into(), json!(m.title));
                payload.insert("artist".into(), json!(m.artist));
                if let Some(t) = m.thumbnail {
                    payload.insert("thumbnailUrl".into(), json!(t));
                }
            }
            op(
                if at.is_some() {
                    "playlist.moveTrack"
                } else {
                    "playlist.addTrack"
                },
                &p.id,
                Value::Object(payload),
            );
        }
    }
    ops
}

#[cfg(test)]
mod tests {
    use super::*;

    fn logged(ops: Vec<NewOp>, from: u64) -> Vec<Op> {
        ops.into_iter()
            .enumerate()
            .map(|(i, o)| Op {
                seq: from + i as u64,
                op_id: format!("o{}", from + i as u64),
                device_id: "d".into(),
                kind: o.kind.into(),
                entity: o.entity,
                payload: o.payload,
                client_ms: 0,
            })
            .collect()
    }

    #[test]
    fn deletion_before_delayed_create_keeps_tombstone() {
        let mk = |kind: &'static str, id: &str, payload: Value| NewOp {
            kind,
            entity: format!("playlist:{id}"),
            payload,
        };
        let log = logged(vec![
            mk("playlist.delete", "gone", json!({})),
            mk("playlist.create", "gone", json!({"title": "Stale"})),
            mk("playlist.create", "kept", json!({"title": "Alive"})),
        ], 1);
        let got = replay(&log);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].id, "kept");
    }

    #[test]
    fn replays_the_shared_fixture_exactly() {
        let fixture: Value =
            serde_json::from_str(include_str!("../../../../sync/fixtures/replay-v1.json")).unwrap();
        let ops: Vec<Op> = fixture["ops"]
            .as_array()
            .unwrap()
            .iter()
            .map(|o| Op {
                seq: o["seq"].as_u64().unwrap(),
                op_id: String::new(),
                device_id: String::new(),
                kind: o["kind"].as_str().unwrap().into(),
                entity: o["entity"].as_str().unwrap().into(),
                payload: o["payload"].clone(),
                client_ms: 0,
            })
            .collect();
        let got = replay(&ops);
        let want = fixture["expected"]["playlists"].as_object().unwrap();
        assert_eq!(got.len(), want.len());
        for p in &got {
            let w = &want[&p.id];
            assert_eq!(p.title, w["title"].as_str().unwrap(), "{}", p.id);
            let tracks: Vec<&str> = w["tracks"]
                .as_array()
                .unwrap()
                .iter()
                .map(|t| t.as_str().unwrap())
                .collect();
            assert_eq!(p.tracks, tracks, "{}", p.id);
        }
    }

    #[test]
    fn diff_then_replay_reaches_the_edited_library() {
        let pl = |id: &str, title: &str, t: &[&str]| Playlist {
            id: id.into(),
            title: title.into(),
            tracks: t.iter().map(|s| s.to_string()).collect(),
        };
        let no = HashMap::new;
        let start = vec![
            (pl("a", "Road", &["1", "2", "3"]), no()),
            (pl("b", "Gone", &["9"]), no()),
        ];
        let mut log = logged(diff(&[], &start), 1);
        let before = replay(&log);
        // Reorder, remove, add, rename, delete one list, create another, and a duplicate that must collapse.
        let edited = vec![
            (pl("a", "Road 2", &["3", "1", "4", "4"]), no()),
            (pl("c", "New", &["5"]), no()),
        ];
        let more = diff(&before, &edited);
        log.extend(logged(more, 100));
        let after = replay(&log);
        assert_eq!(
            after,
            vec![pl("a", "Road 2", &["3", "1", "4"]), pl("c", "New", &["5"])]
        );
        // Nothing changed: no ops, so a repeated sync never writes duplicates.
        assert!(
            diff(
                &after,
                &[(after[0].clone(), no()), (after[1].clone(), no())]
            )
            .is_empty()
        );
    }
}
