//! Copying downloaded songs between your own paired devices over the LAN Sync channel. Only songs this computer
//! downloaded itself are offered, by video id; a phone can never name a file path.

use serde_json::{Value, json};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// One piece of a file per sealed request: big enough to be quick on Wi-Fi, small enough for the phone's reply cap.
pub const CHUNK: u64 = 1024 * 1024;

/// Uploads from a phone come in smaller pieces: a sealed request body is capped at 512 KB after encoding.
pub const UPLOAD_CHUNK: u64 = 192 * 1024;
const MAX_UPLOAD: u64 = 500 * 1024 * 1024;
const MAX_OPEN_UPLOADS: usize = 4;

/// A song arriving from a phone, written to a temporary file until every byte is there.
pub struct Upload {
    file: std::path::PathBuf,
    size: u64,
    written: u64,
    track: crate::model::Track,
    duration_ms: u64,
}

/// One piece of an upload: `{ offset, size, data, title, artist, art, durationMs }`. The song joins this computer's
/// downloads only when the last byte has arrived; a gap, an oversized file or a bad id is refused.
pub fn receive(
    incoming: &Path,
    uploads: &mut std::collections::HashMap<String, Upload>,
    device: &str,
    id: &str,
    body: &Value,
) -> Result<Value, (u16, &'static str)> {
    if !is_video_id(id) {
        return Err((404, "not_found"));
    }
    let key = format!("{device}/{id}");
    let offset = body.get("offset").and_then(Value::as_u64).ok_or((422, "bad_piece"))?;
    let size = body.get("size").and_then(Value::as_u64).filter(|s| (1..=MAX_UPLOAD).contains(s)).ok_or((413, "too_large"))?;
    let data = body.get("data").and_then(Value::as_str).and_then(super::crypto::unb64).ok_or((422, "bad_piece"))?;
    if data.is_empty() || data.len() as u64 > UPLOAD_CHUNK {
        return Err((422, "bad_piece"));
    }
    if offset == 0 {
        if crate::downloads::path(id).is_some() {
            uploads.remove(&key);
            return Ok(json!({ "done": true, "already": true }));
        }
        if !uploads.contains_key(&key) && uploads.len() >= MAX_OPEN_UPLOADS {
            return Err((429, "busy"));
        }
        let text = |k: &str| body.get(k).and_then(Value::as_str).map(str::trim).filter(|t| t.len() <= 300).unwrap_or("").to_owned();
        let art = text("art");
        let art = if super::connect::art_host_ok(&art) { art } else { format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg") };
        let title = text("title");
        std::fs::create_dir_all(incoming).map_err(|_| (500, "unwritable"))?;
        let file = incoming.join(format!("{}-{id}.part", super::crypto::b64(device.as_bytes()).replace(['/', '+', '='], "_")));
        std::fs::write(&file, b"").map_err(|_| (500, "unwritable"))?;
        let track = crate::model::Track {
            id: id.to_owned(),
            title: if title.is_empty() { id.to_owned() } else { title },
            artists: vec![crate::model::Link { name: text("artist"), id: None }],
            album: None,
            duration: body.get("durationMs").and_then(Value::as_u64).map(|ms| (ms / 1000) as u32),
            thumbs: vec![crate::model::Thumb { url: art, width: 544, height: 544 }],
            track_nr: None,
            plays: None,
            music_video: false,
        };
        let duration_ms = body.get("durationMs").and_then(Value::as_u64).unwrap_or(0);
        uploads.insert(key.clone(), Upload { file, size, written: 0, track, duration_ms });
    }
    let up = uploads.get_mut(&key).ok_or((409, "start_over"))?;
    if offset != up.written || size != up.size || up.written + data.len() as u64 > up.size {
        return Err((409, "start_over"));
    }
    use std::io::Write;
    std::fs::OpenOptions::new().append(true).open(&up.file).and_then(|mut f| f.write_all(&data)).map_err(|_| (500, "unwritable"))?;
    up.written += data.len() as u64;
    if up.written < up.size {
        return Ok(json!({ "received": up.written }));
    }
    let up = uploads.remove(&key).expect("present");
    let bytes = std::fs::read(&up.file).map_err(|_| (500, "unreadable"))?;
    let _ = std::fs::remove_file(&up.file);
    crate::downloads::save(&up.track, &bytes, up.duration_ms, None, None, None).map_err(|_| (500, "unwritable"))?;
    log::info!("lan files: {id} arrived from {device} ({} bytes)", bytes.len());
    Ok(json!({ "done": true }))
}

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

    #[test]
    fn uploads_refuse_gaps_oversize_and_bad_ids() {
        let dir = std::env::temp_dir().join(format!("nuvx-upload-{}", std::process::id()));
        let mut uploads = std::collections::HashMap::new();
        let piece = |offset: u64, size: u64, bytes: &[u8]| json!({ "offset": offset, "size": size, "data": super::super::crypto::b64(bytes), "title": "T" });
        assert_eq!(receive(&dir, &mut uploads, "dev", "../x", &piece(0, 10, b"abc")).unwrap_err().0, 404);
        assert_eq!(receive(&dir, &mut uploads, "dev", "dQw4w9WgXcQ", &piece(0, MAX_UPLOAD + 1, b"abc")).unwrap_err().0, 413);
        assert_eq!(receive(&dir, &mut uploads, "dev", "dQw4w9WgXcQ", &piece(0, 10, b"abc")).unwrap()["received"], 3);
        assert_eq!(receive(&dir, &mut uploads, "dev", "dQw4w9WgXcQ", &piece(5, 10, b"abc")).unwrap_err().0, 409, "a gap");
        assert_eq!(receive(&dir, &mut uploads, "other", "dQw4w9WgXcQ", &piece(3, 10, b"abc")).unwrap_err().0, 409, "another phone cannot continue it");
        assert_eq!(receive(&dir, &mut uploads, "dev", "dQw4w9WgXcQ", &piece(3, 10, b"abcdefgh")).unwrap_err().0, 409, "past the size");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
