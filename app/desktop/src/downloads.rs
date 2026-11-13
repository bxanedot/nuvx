use std::collections::HashMap;
use std::io::Write;
use std::fs::OpenOptions;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, RwLock};

use serde::{Deserialize, Serialize};

use crate::model::Track;
use crate::stream::Shared;

#[derive(Clone, Serialize, Deserialize)]
struct Entry {
    path: PathBuf,
    duration_ms: u64,
    loudness_db: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    stands_for: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    song: Option<Track>,
}

struct State {
    index: HashMap<String, Entry>,
    file: PathBuf,
    folder: PathBuf,
}

static STATE: LazyLock<RwLock<State>> =
    LazyLock::new(|| RwLock::new(State { index: HashMap::new(), file: PathBuf::new(), folder: default_folder() }));

pub fn default_folder() -> PathBuf {
    let base = directories::UserDirs::new()
        .and_then(|u| u.audio_dir().map(Path::to_path_buf).or_else(|| Some(u.home_dir().join("Music"))))
        .unwrap_or_else(|| PathBuf::from("."));
    base.join("nuvx")
}

pub fn init(data_dir: &Path, folder: &str) {
    let file = data_dir.join("downloads.json");
    let index = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    let mut st = STATE.write().unwrap();
    st.index = index;
    st.file = file;
    st.folder = if folder.is_empty() { default_folder() } else { PathBuf::from(folder) };
}

pub fn folder() -> PathBuf {
    STATE.read().unwrap().folder.clone()
}

pub fn set_folder(folder: PathBuf) {
    STATE.write().unwrap().folder = folder;
}

pub fn pick_folder(ctx: &egui::Context) {
    let ctx = ctx.clone();
    std::thread::spawn(move || {
        if let Some(dir) = rfd::FileDialog::new().set_title("Download folder").set_directory(folder()).pick_folder() {
            set_folder(dir);
            ctx.request_repaint();
        }
    });
}

pub fn open_folder() {
    let dir = folder();
    let _ = std::fs::create_dir_all(&dir);
    #[cfg(windows)]
    let _ = std::process::Command::new("explorer").arg(dir).spawn();
    #[cfg(target_os = "linux")]
    let _ = std::process::Command::new("xdg-open").arg(dir).spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(dir).spawn();
}

pub fn show_file(path: &Path) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = std::process::Command::new("explorer").raw_arg(format!("/select,\"{}\"", path.display())).spawn();
    }
    #[cfg(target_os = "linux")]
    {
        let uri = url::Url::from_file_path(path).map(|u| u.to_string()).unwrap_or_default();
        let shown = std::process::Command::new("dbus-send")
            .args(["--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems"])
            .arg(format!("array:string:{uri}"))
            .arg("string:")
            .output()
            .is_ok_and(|o| o.status.success());
        if !shown && let Some(dir) = path.parent() {
            let _ = std::process::Command::new("xdg-open").arg(dir).spawn();
        }
    }
    #[cfg(target_os = "macos")]
    {
        let shown = std::process::Command::new("open").arg("-R").arg(path).status().is_ok_and(|s| s.success());
        if !shown && let Some(dir) = path.parent() {
            let _ = std::process::Command::new("open").arg(dir).spawn();
        }
    }
}

pub fn path(video_id: &str) -> Option<PathBuf> {
    STATE.read().unwrap().index.get(video_id).map(|e| e.path.clone()).filter(|p| p.is_file())
}

pub fn list() -> Vec<Track> {
    let st = STATE.read().unwrap();
    let mut out: Vec<(std::time::SystemTime, Track)> = st
        .index
        .values()
        .filter(|e| e.path.is_file())
        .filter_map(|e| {
            let modified = std::fs::metadata(&e.path).and_then(|m| m.modified()).unwrap_or(std::time::UNIX_EPOCH);
            e.song.clone().map(|t| (modified, t))
        })
        .collect();
    out.sort_by(|a, b| b.0.cmp(&a.0));
    out.into_iter().map(|(_, t)| t).collect()
}

pub fn song_for(video_id: &str) -> Option<Track> {
    let st = STATE.read().unwrap();
    st.index.values().find(|e| e.stands_for.as_deref() == Some(video_id) && e.path.is_file()).and_then(|e| e.song.clone())
}

pub fn open(video_id: &str) -> Option<Arc<Shared>> {
    let entry = STATE.read().unwrap().index.get(video_id).cloned()?;
    let data = std::fs::read(&entry.path).ok()?;
    log::info!("{video_id}: playing the downloaded file");
    Some(Shared::from_bytes(data, entry.duration_ms, entry.loudness_db))
}

pub fn save(t: &Track, audio: &[u8], duration_ms: u64, loudness_db: Option<f32>, cover: Option<Vec<u8>>, stands_for: Option<String>) -> anyhow::Result<PathBuf> {
    let folder = folder();
    std::fs::create_dir_all(&folder)?;
    let ext = if audio.get(4..8) == Some(b"ftyp") { "m4a" } else { "webm" };
    let mine = STATE.read().unwrap().index.get(&t.id).map(|e| e.path.clone());
    let stem = file_stem(t);
    let (path, part, mut part_file) = {
        let mut n = 1;
        loop {
            let name = if n == 1 { format!("{stem}.{ext}") } else { format!("{stem} ({n}).{ext}") };
            let path = folder.join(name);
            if path.exists() && Some(&path) != mine.as_ref() {
                n += 1;
                continue;
            }
            let part = path.with_extension(format!("{ext}.part"));
            match OpenOptions::new().write(true).create_new(true).open(&part) {
                Ok(file) => break (path, part, file),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => n += 1,
                Err(e) => return Err(e.into()),
            }
        }
    };
    let mut audio = audio.to_vec();
    if audio.get(8..12) == Some(b"dash") {
        audio[8..12].copy_from_slice(b"M4A ");
    }
    if let Err(e) = part_file.write_all(&audio) {
        let _ = std::fs::remove_file(&part);
        return Err(e.into());
    }
    drop(part_file);
    if ext == "m4a"
        && let Err(e) = tag(&part, t, cover)
    {
        log::warn!("couldn't tag {}: {e:#}", path.display());
    }
    if let Err(e) = std::fs::rename(&part, &path) {
        let _ = std::fs::remove_file(&part);
        return Err(e.into());
    }
    let mut st = STATE.write().unwrap();
    let song = Some(t.clone());
    let previous = st.index.insert(t.id.clone(), Entry { path: path.clone(), duration_ms, loudness_db, stands_for, song });
    let persisted = (|| -> anyhow::Result<()> {
        let json = serde_json::to_vec(&st.index)?;
        write_atomically(&st.file, &json)?;
        Ok(())
    })();
    if let Err(e) = persisted {
        match previous {
            Some(entry) => { st.index.insert(t.id.clone(), entry); }
            None => { st.index.remove(&t.id); }
        }
        if !st.index.get(&t.id).is_some_and(|entry| entry.path.as_path() == path.as_path()) {
            let _ = std::fs::remove_file(&path);
        }
        return Err(e);
    }
    Ok(path)
}

fn write_atomically(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let name = path.file_name().and_then(|name| name.to_str()).unwrap_or("downloads.json");
    let tmp = parent.join(format!(".{name}.{}.{}.tmp", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
    let result = (|| {
        let mut file = OpenOptions::new().write(true).create_new(true).open(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        std::fs::rename(&tmp, path)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(tmp);
    }
    result
}

fn file_stem(t: &Track) -> String {
    let artists = t.artist_line();
    let name = if artists.is_empty() { t.title.clone() } else { format!("{artists} - {}", t.title) };
    let clean: String = name.chars().map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c }).collect();
    let clean = clean.trim().trim_end_matches('.').trim();
    let clean: String = clean.chars().take(150).collect();
    if clean.is_empty() { t.id.clone() } else { clean }
}

fn tag(path: &Path, t: &Track, cover: Option<Vec<u8>>) -> anyhow::Result<()> {
    let mut tag = mp4ameta::Tag::read_from_path(path)?;
    tag.set_title(&t.title);
    for (i, a) in t.artists.iter().enumerate() {
        if i == 0 {
            tag.set_artist(&a.name);
        } else {
            tag.add_artist(&a.name);
        }
    }
    if let Some(a) = t.artists.first() {
        tag.set_album_artist(&a.name);
    }
    if let Some(album) = &t.album {
        tag.set_album(&album.name);
    }
    if let Some(n) = t.track_nr {
        tag.set_track_number(n);
    }
    let img = cover.and_then(|c| match c.get(..4) {
        Some([0xff, 0xd8, ..]) => Some(mp4ameta::Img::jpeg(c)),
        Some([0x89, b'P', b'N', b'G']) => Some(mp4ameta::Img::png(c)),
        _ => None,
    });
    if let Some(img) = img {
        tag.set_artwork(img);
    }
    tag.write_to_path(path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Link;

    #[test]
    fn file_names_are_safe_on_windows() {
        let track = |title: &str, artists: Vec<Link>| Track {
            id: "abc".into(),
            title: title.into(),
            artists,
            album: None,
            duration: None,
            thumbs: Vec::new(),
            track_nr: None,
            plays: None,
            music_video: false,
        };
        let t = track("What? / Why: \"Now\"...", vec![Link { name: "AC/DC".into(), id: None }]);
        assert_eq!(file_stem(&t), "AC_DC - What_ _ Why_ _Now_");
        assert_eq!(file_stem(&track("...", Vec::new())), "abc");
    }
}
