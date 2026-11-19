//! Lyrics: the line/part model, LRC (plain, enhanced, word-synced), TTML and QRC parsing, and songwriter-credit
//! detection. Pure text in, lines out; the apps fetch and draw.

pub mod credits;
pub mod lrc;
pub mod model;
pub mod qrc;
pub mod timeline;
pub mod ttml;
