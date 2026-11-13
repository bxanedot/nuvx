use std::sync::Arc;

use egui::{FontData, FontDefinitions, FontFamily, FontId};

pub fn regular(size: f32) -> FontId {
    FontId::proportional(size)
}
static SEMIBOLD: std::sync::LazyLock<FontFamily> = std::sync::LazyLock::new(|| FontFamily::Name("semibold".into()));
static BOLD: std::sync::LazyLock<FontFamily> = std::sync::LazyLock::new(|| FontFamily::Name("bold".into()));
static ICONS: std::sync::LazyLock<FontFamily> = std::sync::LazyLock::new(|| FontFamily::Name("icons".into()));

pub fn semibold(size: f32) -> FontId {
    FontId::new(size, SEMIBOLD.clone())
}
pub fn bold(size: f32) -> FontId {
    FontId::new(size, BOLD.clone())
}
pub fn icon_font(size: f32) -> FontId {
    FontId::new(size, ICONS.clone())
}

const INTER: [&[u8]; 3] = [
    include_bytes!("../../assets/fonts/Inter-Regular.ttf"),
    include_bytes!("../../assets/fonts/Inter-SemiBold.ttf"),
    include_bytes!("../../assets/fonts/Inter-ExtraBold.ttf"),
];
const LUCIDE: &[u8] = include_bytes!("../../assets/fonts/lucide.ttf");

const LYRIC_FONTS: &[(&str, [&[u8]; 3])] = &[
    ("dmsans", [
        include_bytes!("../../assets/fonts/DMSans-Regular.ttf"),
        include_bytes!("../../assets/fonts/DMSans-SemiBold.ttf"),
        include_bytes!("../../assets/fonts/DMSans-Bold.ttf"),
    ]),
    ("googlesansflex", [
        include_bytes!("../../assets/fonts/GoogleSansFlex-Regular.ttf"),
        include_bytes!("../../assets/fonts/GoogleSansFlex-SemiBold.ttf"),
        include_bytes!("../../assets/fonts/GoogleSansFlex-Bold.ttf"),
    ]),
    ("figtree", [
        include_bytes!("../../assets/fonts/Figtree-Regular.ttf"),
        include_bytes!("../../assets/fonts/Figtree-SemiBold.ttf"),
        include_bytes!("../../assets/fonts/Figtree-ExtraBold.ttf"),
    ]),
    ("instrumentsans", [
        include_bytes!("../../assets/fonts/InstrumentSans-Regular.ttf"),
        include_bytes!("../../assets/fonts/InstrumentSans-SemiBold.ttf"),
        include_bytes!("../../assets/fonts/InstrumentSans-SemiBold.ttf"),
    ]),
];

pub const BUNDLED_FONTS: &[&str] = &["Inter", "DM Sans", "Figtree", "Google Sans Flex", "Instrument Sans"];

fn lyric_key(name: &str) -> Option<&'static str> {
    Some(match name.to_ascii_lowercase().replace(' ', "").as_str() {
        "dmsans" => "dmsans",
        "googlesansflex" | "googlesans" => "googlesansflex",
        "figtree" | "spotifymixuititle" | "spotifymix" | "circular" => "figtree",
        "switzer" | "instrumentsans" => "instrumentsans",
        _ => return None,
    })
}

static FONT_CHOICE: std::sync::RwLock<String> = std::sync::RwLock::new(String::new());

static CHOICE: std::sync::RwLock<Option<Chosen>> = std::sync::RwLock::new(None);

struct Chosen {
    name: String,
    faces: [Face; 3],
}

#[derive(Clone, Copy)]
struct Face {
    bytes: &'static [u8],
    index: u32,
    wght: Option<f32>,
}

impl Face {
    const fn of(bytes: &'static [u8]) -> Self {
        Self { bytes, index: 0, wght: None }
    }
}

pub fn set_font_choice(name: &str) {
    *FONT_CHOICE.write().unwrap() = name.to_owned();
}

pub fn font_choice() -> String {
    FONT_CHOICE.read().unwrap().clone()
}

pub fn effective_font(theme_font: &str) -> String {
    let choice = FONT_CHOICE.read().unwrap();
    if choice.is_empty() { theme_font.to_owned() } else { choice.clone() }
}

fn font_key(name: &str) -> &'static str {
    let name = effective_font(name);
    if let Some(k) = lyric_key(&name) {
        return k;
    }
    if CHOICE.read().unwrap().as_ref().is_some_and(|c| c.name.eq_ignore_ascii_case(&name)) { "choice" } else { "inter" }
}

fn family(key: &str, weight: crate::preset::Weight, size: f32) -> FontId {
    use crate::preset::Weight;
    let w = match weight {
        Weight::Regular => 0,
        Weight::Semibold => 1,
        Weight::Bold => 2,
    };
    FontId::new(size, FontFamily::Name(format!("{key}-{w}").into()))
}

pub fn lyric_font(name: &str, weight: crate::preset::Weight, size: f32) -> FontId {
    family(font_key(name), weight, size)
}

pub fn lyric_font_for(name: &str, weight: crate::preset::Weight, size: f32, text: &str) -> FontId {
    let key = font_key(name);
    let key = if key != "inter" && lacks_letters(key, text) { "inter" } else { key };
    family(key, weight, size)
}

fn lacks_letters(key: &str, text: &str) -> bool {
    use skrifa::MetadataProvider;
    let face = match key {
        "choice" => match CHOICE.read().unwrap().as_ref() {
            Some(c) => c.faces[0],
            None => return false,
        },
        k => match LYRIC_FONTS.iter().find(|(n, _)| *n == k) {
            Some((_, w)) => Face::of(w[0]),
            None => return false,
        },
    };
    let (Ok(font), Ok(inter)) = (skrifa::FontRef::from_index(face.bytes, face.index), skrifa::FontRef::new(INTER[0])) else { return false };
    let (has, inter) = (font.charmap(), inter.charmap());
    text.chars().any(|c| c.is_alphabetic() && has.map(c).is_none() && inter.map(c).is_some())
}

pub struct SysFamily {
    pub name: String,
    faces: Vec<SysFace>,
}

struct SysFace {
    path: std::path::PathBuf,
    index: u32,
    weight: f32,
    wght: Option<(f32, f32)>,
}

static INSTALLED_FONTS: std::sync::OnceLock<Vec<SysFamily>> = std::sync::OnceLock::new();
static SCANNING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn installed_fonts(done: impl FnOnce() + Send + 'static) -> Option<&'static [SysFamily]> {
    if let Some(v) = INSTALLED_FONTS.get() {
        return Some(v);
    }
    if !SCANNING.swap(true, std::sync::atomic::Ordering::SeqCst) {
        std::thread::spawn(move || {
            let _ = INSTALLED_FONTS.set(scan_fonts());
            done();
        });
    }
    None
}

fn font_dirs() -> Vec<std::path::PathBuf> {
    let mut dirs = Vec::new();
    #[cfg(windows)]
    {
        let windir = std::env::var_os("WINDIR").unwrap_or_else(|| "C:\\Windows".into());
        dirs.push(std::path::PathBuf::from(windir).join("Fonts"));
        if let Some(local) = std::env::var_os("LOCALAPPDATA") {
            dirs.push(std::path::PathBuf::from(local).join("Microsoft\\Windows\\Fonts"));
        }
    }
    #[cfg(target_os = "linux")]
    dirs.extend(["/usr/share/fonts", "/usr/local/share/fonts"].map(Into::into));
    #[cfg(target_os = "macos")]
    dirs.extend(["/System/Library/Fonts", "/Library/Fonts"].map(Into::into));
    if let Some(home) = std::env::var_os("HOME") {
        let home = std::path::PathBuf::from(home);
        dirs.push(home.join(".local/share/fonts"));
        #[cfg(target_os = "macos")]
        dirs.push(home.join("Library/Fonts"));
    }
    dirs
}

fn scan_fonts() -> Vec<SysFamily> {
    use skrifa::{MetadataProvider, attribute::Style, string::StringId};
    let mut files = Vec::new();
    let mut dirs = font_dirs();
    while let Some(dir) = dirs.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for e in entries.flatten() {
            let path = e.path();
            if path.is_dir() {
                dirs.push(path);
            } else if path.extension().and_then(|x| x.to_str()).is_some_and(|x| matches!(x.to_ascii_lowercase().as_str(), "ttf" | "otf" | "ttc" | "otc")) {
                files.push(path);
            }
        }
    }
    let mut families: std::collections::BTreeMap<String, SysFamily> = std::collections::BTreeMap::new();
    for path in files {
        let Ok(file) = std::fs::File::open(&path) else { continue };
        let Ok(data) = (unsafe { memmap2::Mmap::map(&file) }) else { continue };
        let Ok(fonts) = skrifa::raw::FileRef::new(&data) else { continue };
        for (index, font) in fonts.fonts().enumerate() {
            let Ok(font) = font else { continue };
            let cmap = font.charmap();
            if cmap.is_symbol() || cmap.map('a').is_none() || cmap.map('Z').is_none() {
                continue;
            }
            let attrs = font.attributes();
            if attrs.style != Style::Normal || (attrs.stretch.ratio() - 1.0).abs() > 0.01 {
                continue;
            }
            let name = [StringId::TYPOGRAPHIC_FAMILY_NAME, StringId::FAMILY_NAME]
                .into_iter()
                .find_map(|id| font.localized_strings(id).english_or_first())
                .map(|s| s.to_string().trim().to_owned());
            let Some(name) = name.filter(|n| !n.is_empty() && !n.starts_with('@') && lyric_key(n).is_none() && !n.eq_ignore_ascii_case("inter")) else { continue };
            let wght = font.axes().iter().find(|a| a.tag() == skrifa::Tag::new(b"wght")).map(|a| (a.min_value(), a.max_value()));
            let face = SysFace { path: path.clone(), index: index as u32, weight: attrs.weight.value(), wght };
            families.entry(name.to_lowercase()).or_insert_with(|| SysFamily { name, faces: Vec::new() }).faces.push(face);
        }
    }
    families.into_values().collect()
}

fn choose_faces(family: &SysFamily) -> Option<[Face; 3]> {
    let mut faces = Vec::new();
    for target in [400.0, 600.0, 750.0] {
        let best = family.faces.iter().min_by_key(|f| match f.wght {
            Some((lo, hi)) if (lo..=hi).contains(&target) => 0,
            Some((lo, hi)) => (target - target.clamp(lo, hi)).abs() as i32,
            None => (f.weight - target).abs() as i32,
        })?;
        let bytes = map(&best.path)?;
        faces.push(Face { bytes, index: best.index, wght: best.wght.map(|(lo, hi)| target.clamp(lo, hi)) });
    }
    faces.try_into().ok()
}

fn load_choice(ui_font: &str) {
    let name = effective_font(ui_font);
    if name.is_empty() || lyric_key(&name).is_some() || name.eq_ignore_ascii_case("inter") {
        *CHOICE.write().unwrap() = None;
        return;
    }
    if CHOICE.read().unwrap().as_ref().is_some_and(|c| c.name == name) {
        return;
    }
    let all = INSTALLED_FONTS.get_or_init(scan_fonts);
    let faces = name.split(',').map(str::trim).find_map(|n| all.iter().find(|f| f.name.eq_ignore_ascii_case(n)).and_then(choose_faces));
    if faces.is_none() {
        log::warn!("font {name:?} isn't installed; using Inter");
    }
    *CHOICE.write().unwrap() = faces.map(|faces| Chosen { name, faces });
}

#[cfg(windows)]
const CANDIDATES: &[(&str, u32)] = &[
    ("C:\\Windows\\Fonts\\segoeui.ttf", 0),
    ("C:\\Windows\\Fonts\\seguisym.ttf", 0),
    ("C:\\Windows\\Fonts\\YuGothM.ttc", 0),
    ("C:\\Windows\\Fonts\\msyh.ttc", 0),
    ("C:\\Windows\\Fonts\\msjh.ttc", 0),
    ("C:\\Windows\\Fonts\\malgun.ttf", 0),
    ("C:\\Windows\\Fonts\\Nirmala.ttc", 0),
    ("C:\\Windows\\Fonts\\Nirmala.ttf", 0),
    ("C:\\Windows\\Fonts\\LeelawUI.ttf", 0),
    ("C:\\Windows\\Fonts\\ebrima.ttf", 0),
    ("C:\\Windows\\Fonts\\seguiemj.ttf", 0),
];

#[cfg(windows)]
const HEAVIER: &[(&str, [(&str, u32); 2])] = &[
    ("C:\\Windows\\Fonts\\segoeui.ttf", [("C:\\Windows\\Fonts\\seguisb.ttf", 0), ("C:\\Windows\\Fonts\\segoeuib.ttf", 0)]),
    ("C:\\Windows\\Fonts\\YuGothM.ttc", [("C:\\Windows\\Fonts\\YuGothB.ttc", 0), ("C:\\Windows\\Fonts\\YuGothB.ttc", 0)]),
    ("C:\\Windows\\Fonts\\msyh.ttc", [("C:\\Windows\\Fonts\\msyhbd.ttc", 0), ("C:\\Windows\\Fonts\\msyhbd.ttc", 0)]),
    ("C:\\Windows\\Fonts\\msjh.ttc", [("C:\\Windows\\Fonts\\msjhbd.ttc", 0), ("C:\\Windows\\Fonts\\msjhbd.ttc", 0)]),
    ("C:\\Windows\\Fonts\\malgun.ttf", [("C:\\Windows\\Fonts\\malgunbd.ttf", 0), ("C:\\Windows\\Fonts\\malgunbd.ttf", 0)]),
    ("C:\\Windows\\Fonts\\Nirmala.ttc", [("C:\\Windows\\Fonts\\Nirmala.ttc", 1), ("C:\\Windows\\Fonts\\Nirmala.ttc", 1)]),
    ("C:\\Windows\\Fonts\\LeelawUI.ttf", [("C:\\Windows\\Fonts\\LeelaUIb.ttf", 0), ("C:\\Windows\\Fonts\\LeelaUIb.ttf", 0)]),
    ("C:\\Windows\\Fonts\\ebrima.ttf", [("C:\\Windows\\Fonts\\ebrimabd.ttf", 0), ("C:\\Windows\\Fonts\\ebrimabd.ttf", 0)]),
];
#[cfg(not(windows))]
const HEAVIER: &[(&str, [(&str, u32); 2])] = &[];

#[cfg(target_os = "linux")]
const CANDIDATES: &[(&str, u32)] = &[
    ("/usr/share/fonts/noto/NotoSans-Regular.ttf", 0),
    ("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf", 0),
    ("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc", 0),
    ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 0),
    ("/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc", 0),
    ("/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf", 0),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0),
];

#[cfg(target_os = "macos")]
const CANDIDATES: &[(&str, u32)] = &[
    ("/System/Library/Fonts/Supplemental/Arial.ttf", 0),
    ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", 0),
    ("/System/Library/Fonts/Apple Symbols.ttf", 0),
    ("/System/Library/Fonts/Apple Color Emoji.ttc", 0),
    ("/System/Library/Fonts/PingFang.ttc", 0),
    ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),
    ("/System/Library/Fonts/AppleSDGothicNeo.ttc", 0),
];

fn em_metrics(bytes: &[u8], index: u32) -> Option<(f32, f32)> {
    use skrifa::MetadataProvider;
    let font = skrifa::FontRef::from_index(bytes, index).ok()?;
    let m = font.metrics(skrifa::instance::Size::unscaled(), skrifa::instance::LocationRef::default());
    let upem = m.units_per_em.max(1) as f32;
    Some((m.ascent / upem, (m.ascent - m.descent + m.leading) / upem))
}

fn font_data(face: Face, y_offset_factor: f32) -> FontData {
    let mut tweak = egui::FontTweak { y_offset_factor, ..Default::default() };
    if let Some(w) = face.wght {
        tweak.coords.push(b"wght", w);
    }
    FontData { index: face.index, ..FontData::from_static(face.bytes) }.tweak(tweak)
}

pub fn install_fonts(ctx: &egui::Context, ui_font: &str) {
    load_choice(ui_font);
    let ui_key = font_key(ui_font);
    static INSTALLED: std::sync::Mutex<Option<(&'static str, String)>> = std::sync::Mutex::new(None);
    let loaded = CHOICE.read().unwrap().as_ref().map(|c| c.name.clone()).unwrap_or_default();
    let state = Some((ui_key, loaded));
    {
        let mut installed = INSTALLED.lock().unwrap();
        if *installed == state {
            return;
        }
        *installed = state;
    }
    let mut defs = FontDefinitions::default();
    let egui_default = defs.families.get(&FontFamily::Proportional).cloned().unwrap_or_default();
    defs.font_data.insert("lucide".into(), Arc::new(FontData::from_static(LUCIDE).tweak(egui::FontTweak { y_offset_factor: 0.0, ..Default::default() })));
    defs.families.insert(FontFamily::Name("icons".into()), vec!["lucide".to_owned()]);

    type Fallbacks = [Vec<(String, Face)>; 3];
    static SYSTEM: std::sync::OnceLock<Fallbacks> = std::sync::OnceLock::new();
    let system = SYSTEM.get_or_init(|| {
        std::array::from_fn(|w| {
            CANDIDATES
                .iter()
                .filter_map(|&(path, index)| {
                    let heavy = (w > 0).then(|| HEAVIER.iter().find(|(base, _)| *base == path)).flatten().map(|(_, v)| v[w - 1]);
                    let mapped = |(p, i): (&str, u32)| map(p).map(|bytes| (format!("sys:{p}#{i}"), Face { bytes, index: i, wght: None }));
                    heavy.and_then(mapped).or_else(|| mapped((path, index)))
                })
                .collect()
        })
    });
    let choice = CHOICE.read().unwrap().as_ref().map(|c| c.faces);
    let mut groups: Vec<(&str, [Face; 3])> = vec![("inter", INTER.map(Face::of))];
    groups.extend(LYRIC_FONTS.iter().map(|(k, w)| (*k, w.map(Face::of))));
    groups.extend(choice.map(|faces| ("choice", faces)));
    for (key, faces) in &groups {
        let (asc_p, h_p) = em_metrics(faces[0].bytes, faces[0].index).unwrap_or((0.97, 1.21));
        for (w, face) in faces.iter().enumerate() {
            let name = format!("{key}-{w}");
            defs.font_data.insert(name.clone(), Arc::new(font_data(*face, 0.0)));
            let mut chain = vec![name.clone()];
            let inter = (*key != "inter").then(|| ("inter".to_owned(), Face::of(INTER[w])));
            for (fallback, f) in inter.iter().chain(&system[w]) {
                let Some((asc_f, h_f)) = em_metrics(f.bytes, f.index) else { continue };
                let shift = asc_p - (asc_f + 0.5 * (h_p - h_f));
                let id = format!("{fallback}-{w}@{key}");
                if !defs.font_data.contains_key(&id) {
                    defs.font_data.insert(id.clone(), Arc::new(font_data(*f, shift)));
                }
                chain.push(id);
            }
            if *key == "inter" && w == 0
                && let Some(mono) = defs.families.get_mut(&FontFamily::Monospace)
            {
                mono.extend(chain[1..].iter().cloned());
            }
            chain.extend(egui_default.iter().filter(|n| n.as_str() != "Ubuntu-Light").cloned());
            defs.families.insert(FontFamily::Name(name.clone().into()), chain.clone());
            if *key == ui_key {
                let family = match w {
                    0 => FontFamily::Proportional,
                    1 => FontFamily::Name("semibold".into()),
                    _ => FontFamily::Name("bold".into()),
                };
                defs.families.insert(family, chain);
            }
        }
    }
    if choice.is_none() {
        for w in 0..3 {
            let inter = defs.families[&FontFamily::Name(format!("inter-{w}").into())].clone();
            defs.families.insert(FontFamily::Name(format!("choice-{w}").into()), inter);
        }
    }
    for data in defs.font_data.values_mut() {
        let font = shared_bytes(&data.font);
        if !Arc::ptr_eq(&font, &data.font) {
            *data = Arc::new(FontData { font, ..(**data).clone() });
        }
    }
    ctx.set_fonts(defs);
}

fn shared_bytes(blob: &egui::epaint::text::Blob) -> egui::epaint::text::Blob {
    static SEEN: std::sync::Mutex<Vec<egui::epaint::text::Blob>> = std::sync::Mutex::new(Vec::new());
    let bytes = (**blob).as_ref();
    let mut seen = SEEN.lock().unwrap();
    if let Some(b) = seen.iter().find(|b| std::ptr::eq((***b).as_ref(), bytes)) {
        return b.clone();
    }
    seen.push(blob.clone());
    blob.clone()
}

fn map(path: impl AsRef<std::path::Path>) -> Option<&'static [u8]> {
    static MAPPED: std::sync::Mutex<Vec<(std::path::PathBuf, &'static [u8])>> = std::sync::Mutex::new(Vec::new());
    let path = std::fs::canonicalize(path).ok()?;
    let mut mapped = MAPPED.lock().unwrap();
    if let Some((_, bytes)) = mapped.iter().find(|(p, _)| *p == path) {
        return Some(bytes);
    }
    let file = std::fs::File::open(&path).ok()?;
    let mmap = unsafe { memmap2::Mmap::map(&file) }.ok()?;
    let bytes: &'static [u8] = Box::leak(Box::new(mmap));
    mapped.push((path, bytes));
    Some(bytes)
}
