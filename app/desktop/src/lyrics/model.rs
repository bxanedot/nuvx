#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Provider {
    BLyricsRich,
    UnisonRich,
    BinimumRich,
    UnisonWord,
    PortatoRich,
    MusixmatchRich,
    LrcredRich,
    BLyricsSynced,
    UnisonSynced,
    BinimumSynced,
    LrclibSynced,
    LegatoSynced,
    MusixmatchSynced,
    LrcredSynced,
    UnisonPlain,
    LrclibPlain,
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum SyncKind {
    Syllable,
    Word,
    Line,
    Unsynced,
}

impl Provider {
    pub const ALL: [Provider; 16] = [
        Provider::LrcredRich,
        Provider::LrcredSynced,
        Provider::BinimumRich,
        Provider::BinimumSynced,
        Provider::BLyricsRich,
        Provider::BLyricsSynced,
        Provider::PortatoRich,
        Provider::LegatoSynced,
        Provider::MusixmatchRich,
        Provider::MusixmatchSynced,
        Provider::UnisonRich,
        Provider::UnisonWord,
        Provider::UnisonSynced,
        Provider::UnisonPlain,
        Provider::LrclibSynced,
        Provider::LrclibPlain,
    ];

    pub fn key(self) -> &'static str {
        match self {
            Provider::BLyricsRich => "bLyrics-richsynced",
            Provider::UnisonRich => "unison-richsynced",
            Provider::BinimumRich => "binimum-richsynced",
            Provider::UnisonWord => "unison-wordsynced",
            Provider::PortatoRich => "portato-richsynced",
            Provider::MusixmatchRich => "musixmatch-richsync",
            Provider::LrcredRich => "lrcred-richsynced",
            Provider::BLyricsSynced => "bLyrics-synced",
            Provider::UnisonSynced => "unison-synced",
            Provider::BinimumSynced => "binimum-synced",
            Provider::LrclibSynced => "lrclib-synced",
            Provider::LegatoSynced => "legato-synced",
            Provider::MusixmatchSynced => "musixmatch-synced",
            Provider::LrcredSynced => "lrcred-synced",
            Provider::UnisonPlain => "unison-plain",
            Provider::LrclibPlain => "lrclib-plain",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.key() == key)
    }

    pub fn name(self) -> &'static str {
        match self {
            Provider::BLyricsRich | Provider::BLyricsSynced => "Better Lyrics",
            Provider::UnisonRich | Provider::UnisonWord | Provider::UnisonSynced | Provider::UnisonPlain => "Unison",
            Provider::BinimumRich | Provider::BinimumSynced => "BiniLyrics",
            Provider::PortatoRich => "Better Lyrics Portato",
            Provider::MusixmatchRich | Provider::MusixmatchSynced => "Musixmatch",
            Provider::LrclibSynced | Provider::LrclibPlain => "LRCLib",
            Provider::LrcredRich | Provider::LrcredSynced => "lrc.red",
            Provider::LegatoSynced => "Better Lyrics Legato",
        }
    }

    pub fn sync(self) -> SyncKind {
        match self {
            Provider::BLyricsRich | Provider::UnisonRich | Provider::BinimumRich => SyncKind::Syllable,
            Provider::UnisonWord | Provider::PortatoRich | Provider::MusixmatchRich | Provider::LrcredRich => SyncKind::Word,
            Provider::BLyricsSynced
            | Provider::UnisonSynced
            | Provider::BinimumSynced
            | Provider::LrclibSynced
            | Provider::LegatoSynced
            | Provider::MusixmatchSynced
            | Provider::LrcredSynced => SyncKind::Line,
            Provider::UnisonPlain | Provider::LrclibPlain => SyncKind::Unsynced,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Sync {
    None,
    Line,
    Word,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Part {
    pub start: i32,
    pub dur: i32,
    pub text: String,
    pub bg: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Line {
    pub start: i32,
    pub dur: i32,
    pub text: String,
    pub parts: Vec<Part>,
    pub agent: Option<String>,
    pub instrumental: bool,
    pub translations: Vec<(String, String)>,
    pub romanization: Option<String>,
    pub timed_roman: Vec<Part>,
}

impl Line {
    pub fn end(&self) -> i32 {
        self.start + self.dur
    }
}

#[derive(Clone, Debug)]
pub struct Lyrics {
    pub lines: Vec<Line>,
    pub sync: Sync,
    pub provider: Provider,
    pub language: Option<String>,
    pub songwriters: Vec<String>,
}

impl Lyrics {
    pub(super) fn new(provider: Provider, lines: Vec<Line>, language: Option<String>, songwriters: Vec<String>) -> Option<Self> {
        if lines.is_empty() {
            return None;
        }
        let sync = derive_sync(&lines);
        Some(Self { lines, sync, provider, language, songwriters })
    }
}

fn derive_sync(lines: &[Line]) -> Sync {
    if lines.iter().any(|l| !l.instrumental && l.parts.iter().any(|p| p.dur != 0)) {
        Sync::Word
    } else if lines.iter().all(|l| l.start == 0) {
        Sync::None
    } else {
        Sync::Line
    }
}

#[derive(Clone, Debug, Default)]
pub struct LyricsSet {
    pub sources: Vec<Lyrics>,
}

impl LyricsSet {
    pub fn get(&self, p: Provider) -> Option<&Lyrics> {
        self.sources.iter().find(|l| l.provider == p)
    }
}
