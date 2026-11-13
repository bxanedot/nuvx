mod matching;
mod parsing;
mod search;
mod album;
mod playlist;
mod home;
mod artist;
pub use artist::artist;
mod radio;
pub use radio::{radio, server_queue, ServerQueue};
#[cfg(test)]
use radio::{parse_server_queue, radio_tracks};
mod library;
pub use library::{charts, new_albums, saved_albums, saved_playlists};
#[allow(unused_imports)]
pub use library::Charts;
use library::item_token;
mod accounts;
pub use accounts::{account, channels, Channel};
mod history;
pub use history::{history, uploads};
mod actions;
pub use actions::{add_videos, collaboration, create_playlist, create_playlist_with, delete_playlist, dislike, edit_playlist, remove_videos, report_play, report_watchtime, save_collection, set_collaboration, set_like, CollabChange, Collaboration, Watchtime};
#[cfg(test)]
use actions::parse_collaboration;
#[cfg(test)]
use accounts::parse_channels;
#[cfg(test)]
use library::{grid_continuation, grid_next};
pub use home::{home, home_more, mood_art, mood_page, moods, podcasts, HomePage, PODCASTS};
#[cfg(test)]
use home::{parse_moods, podcast_search_shelf};
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

#[cfg(test)]
mod tests;
