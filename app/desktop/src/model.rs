#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Thumb {
    pub url: String,
    pub width: u32,
    pub height: u32,
}

impl Thumb {
    pub fn square_uv(&self) -> egui::Rect {
        use egui::{pos2, Rect};
        let ratio = if self.height > 0 { self.width as f32 / self.height as f32 } else { 1.0 };
        if ratio > 1.6 {
            let w = 1.0 / ratio;
            Rect::from_min_max(pos2(0.5 - w / 2.0, 0.0), pos2(0.5 + w / 2.0, 1.0))
        } else if ratio > 1.2 {
            let w = 0.75 / ratio;
            Rect::from_min_max(pos2(0.5 - w / 2.0, 0.125), pos2(0.5 + w / 2.0, 0.875))
        } else {
            Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))
        }
    }
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Link {
    pub name: String,
    pub id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artists: Vec<Link>,
    pub album: Option<Link>,
    pub duration: Option<u32>,
    pub thumbs: Vec<Thumb>,
    pub track_nr: Option<u16>,
    pub plays: Option<u64>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub music_video: bool,
}

impl Track {
    pub fn artist_line(&self) -> String {
        self.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ")
    }
}

pub fn pick_thumb(thumbs: &[Thumb], min_px: u32) -> Option<&Thumb> {
    thumbs.iter().filter(|t| t.width >= min_px).min_by_key(|t| t.width).or_else(|| thumbs.iter().max_by_key(|t| t.width))
}

pub fn pick(thumbs: &[Thumb], min_px: u32) -> Option<&str> {
    pick_thumb(thumbs, min_px).map(|t| t.url.as_str())
}

pub fn sized(url: &str, px: u32) -> String {
    if (url.contains("googleusercontent.com") || url.contains("ggpht.com"))
        && let Some(i) = url.rfind('=')
    {
        let crop = square_crop(&url[i + 1..]);
        return format!("{}=w{px}-h{px}{}-l90-rj", &url[..i], if crop { "-p" } else { "" });
    }
    url.to_owned()
}

fn square_crop(opts: &str) -> bool {
    let parts: Vec<&str> = opts.split('-').collect();
    if !parts.iter().any(|p| *p == "p" || *p == "c") {
        return false;
    }
    let num = |prefix: char| parts.iter().find_map(|p| p.strip_prefix(prefix)?.parse::<u32>().ok());
    parts.iter().any(|p| p.strip_prefix('s').is_some_and(|n| n.parse::<u32>().is_ok())) || matches!((num('w'), num('h')), (Some(w), Some(h)) if w == h)
}

#[cfg(test)]
mod sized_tests {
    use super::sized;

    #[test]
    fn square_crops_stay_cropped() {
        let base = "https://yt3.ggpht.com/abc";
        assert_eq!(sized(&format!("{base}=w120-h120-p-l90-rj"), 544), format!("{base}=w544-h544-p-l90-rj"));
        assert_eq!(sized(&format!("{base}=s88-c-k-c0x00ffffff-no-rj"), 544), format!("{base}=w544-h544-p-l90-rj"));
        assert_eq!(sized("https://lh3.googleusercontent.com/x=w120-h120-l90-rj", 544), "https://lh3.googleusercontent.com/x=w544-h544-l90-rj");
        assert_eq!(sized("https://lh3.googleusercontent.com/x=w1440-h600-p-l90-rj", 544), "https://lh3.googleusercontent.com/x=w544-h544-l90-rj");
    }
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum Target {
    Song(Track),
    Album(String),
    Playlist(String),
    Artist(String),
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Card {
    pub title: String,
    pub subtitle: String,
    pub thumbs: Vec<Thumb>,
    pub target: Target,
    pub round: bool,
    pub links: Vec<Link>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ShelfKind {
    Cards(Vec<Card>),
    Songs { tracks: Vec<Track>, rows: usize },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Shelf {
    pub title: String,
    pub strapline: Option<String>,
    pub strap_thumb: Option<String>,
    pub kind: ShelfKind,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Mood {
    pub title: String,
    pub browse_id: String,
    pub params: Option<String>,
    pub color: Option<u32>,
    pub thumb: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MoodSection {
    pub title: String,
    pub items: Vec<Mood>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MoodPage {
    pub browse_id: String,
    pub params: Option<String>,
    pub title: Option<String>,
    pub shelves: Vec<Shelf>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Chip {
    pub title: String,
    pub params: Option<String>,
    pub selected: bool,
}
