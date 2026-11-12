use serde::{Deserialize, Serialize};

use crate::model::{Link, Thumb, Track};

use super::Member;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WireTrack {
    pub(super) video_id: String,
    #[serde(default)]
    pub(super) title: String,
    #[serde(default)]
    pub(super) artist: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(super) thumbnail_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(super) duration_ms: Option<u64>,
    #[serde(default)]
    pub(super) from_autoplay: bool,
}

pub(super) fn to_wire(t: &Track) -> WireTrack {
    WireTrack {
        video_id: t.id.clone(),
        title: t.title.clone(),
        artist: t
            .artists
            .iter()
            .map(|a| a.name.as_str())
            .collect::<Vec<_>>()
            .join(", "),
        thumbnail_url: t
            .thumbs
            .iter()
            .max_by_key(|th| th.width)
            .map(|th| th.url.clone()),
        duration_ms: t.duration.map(|d| d as u64 * 1000),
        from_autoplay: false,
    }
}

pub(super) fn from_wire(w: &WireTrack) -> Track {
    Track {
        id: w.video_id.clone(),
        title: w.title.clone(),
        artists: if w.artist.is_empty() {
            vec![]
        } else {
            vec![Link {
                name: w.artist.clone(),
                id: None,
            }]
        },
        album: None,
        duration: w.duration_ms.map(|d| (d / 1000) as u32),
        thumbs: w
            .thumbnail_url
            .iter()
            .map(|u| Thumb {
                url: u.clone(),
                width: 0,
                height: 0,
            })
            .collect(),
        track_nr: None,
        plays: None,
        music_video: false,
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WireMember {
    pub(super) member_id: String,
    pub(super) display_name: String,
    #[serde(default)]
    pub(super) is_host: bool,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WirePlayback {
    pub(super) seq: i64,
    pub(super) track: Option<WireTrack>,
    pub(super) queue_seq: i64,
    pub(super) queue_index: i64,
    pub(super) is_playing: bool,
    pub(super) position_ms: i64,
    pub(super) anchor_ms: i64,
    pub(super) updated_by: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
pub(super) struct WireQueue {
    pub(super) seq: i64,
    pub(super) index: i64,
    pub(super) items: Vec<WireTrack>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WireParty {
    pub(super) code: String,
    pub(super) host_only_control: bool,
    pub(super) members: Vec<WireMember>,
    pub(super) playback: WirePlayback,
    pub(super) queue: WireQueue,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub(super) enum In {
    #[serde(rename_all = "camelCase")]
    Welcome {
        you: WireMember,
        party: WireParty,
        server_ms: i64,
    },
    #[serde(rename_all = "camelCase")]
    Members {
        members: Vec<WireMember>,
        host_only_control: bool,
    },
    State {
        playback: WirePlayback,
    },
    Queue {
        queue: WireQueue,
    },
    #[serde(rename_all = "camelCase")]
    Pong {
        client_ms: Option<i64>,
        server_ms: i64,
    },
    Error {
        error: String,
        message: Option<String>,
    },
    Bye {
        reason: Option<String>,
        message: Option<String>,
    },
    Activity {
        by: String,
        detail: String,
    },
}

pub(super) fn members_of(m: &[WireMember]) -> (String, Vec<Member>) {
    let host = m
        .iter()
        .find(|m| m.is_host)
        .map(|m| m.member_id.clone())
        .unwrap_or_default();
    (
        host,
        m.iter()
            .map(|m| Member {
                id: m.member_id.clone(),
                name: m.display_name.clone(),
            })
            .collect(),
    )
}
