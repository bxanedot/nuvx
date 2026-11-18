//! Copying downloaded songs between your own paired devices over the LAN Sync channel. Only songs this computer
//! downloaded itself are offered, by video id; a phone can never name a file path.

use serde_json::{Value, json};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// One piece of a file per sealed request: big enough to be quick on Wi-Fi, small enough for the phone's reply cap.
pub const CHUNK: u64 = 1024 * 1024;

pub fn is_video_id(id: &str) -> bool {
    id.len() == 11 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// `{ "files": [ { id, title, artist, art, durationMs, size, ext } ] }` for every download that is on disk.
pub fn list() -> Value {
    let files: Vec<Value> = crate::downloads::list()
        .into_iter()
        .filter_map(|t| {
            let path = crate::downloads::path(&t.id)?;
            let size = std::fs::metadata(&path).ok()?.len();
            Some(json!({
                "id": t.id,
                "title": t.title,
                "artist": t.artist_line(),
                "art": crate::model::pick(&t.thumbs, 544),
                "durationMs": crate::downloads::duration_ms(&t.id),
                "size": size,
                "ext": path.extension().and_then(|e| e.to_str()).unwrap_or("m4a"),
            }))
        })
        .collect();
    json!({ "files": files })
}

/// `{ "data": base64, "offset", "size" }` for the piece of `id` starting at `offset`.
pub fn chunk(id: &str, query: &str) -> Result<Value, (u16, &'static str)> {
    if !is_video_id(id) {
        return Err((404, "not_found"));
    }
    let offset: u64 = query.split('&').find_map(|kv| kv.strip_prefix("offset=")).and_then(|v| v.parse().ok()).unwrap_or(0);
    let path = crate::downloads::path(id).ok_or((404, "not_found"))?;
    read_chunk(&path, offset).map_err(|_| (500, "unreadable"))?.ok_or((416, "past_end"))
}

fn read_chunk(path: &Path, offset: u64) -> std::io::Result<Option<Value>> {
    let mut file = std::fs::File::open(path)?;
    let size = file.metadata()?.len();
    if offset > size {
        return Ok(None);
    }
    file.seek(SeekFrom::Start(offset))?;
    let mut buf = Vec::with_capacity(CHUNK.min(size - offset) as usize);
    file.take(CHUNK).read_to_end(&mut buf)?;
    Ok(Some(json!({ "data": super::crypto::b64(&buf), "offset": offset, "size": size })))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pieces_cover_the_file_exactly_and_bad_ids_never_reach_the_disk() {
        let dir = std::env::temp_dir().join(format!("nuvx-files-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("song.m4a");
        let bytes: Vec<u8> = (0..(CHUNK as usize + 1234)).map(|i| (i % 251) as u8).collect();
        std::fs::write(&path, &bytes).unwrap();

        let first = read_chunk(&path, 0).unwrap().unwrap();
        let second = read_chunk(&path, CHUNK).unwrap().unwrap();
        let mut joined = super::super::crypto::unb64(first["data"].as_str().unwrap()).unwrap();
        joined.extend(super::super::crypto::unb64(second["data"].as_str().unwrap()).unwrap());
        assert_eq!(joined, bytes);
        assert_eq!(second["size"], bytes.len() as u64);
        assert!(read_chunk(&path, bytes.len() as u64 + 1).unwrap().is_none(), "past the end");
        std::fs::remove_dir_all(&dir).unwrap();

        assert_eq!(chunk("../../windows", "offset=0").unwrap_err().0, 404);
        assert_eq!(chunk("C:\\boot.ini", "").unwrap_err().0, 404);
        assert!(!is_video_id("dQw4w9WgXc/"));
    }
}
