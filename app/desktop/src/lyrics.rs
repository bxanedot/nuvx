
mod ttml;
mod qrc;
mod lrc;
mod credits;
mod model;
mod provider;
mod timeline;
#[cfg(test)]
use lrc::lrc_songwriters;
#[allow(unused_imports)]
#[allow(unused_imports)]
pub use ttml::{Ttml, parse_ttml, ttml_time};
#[allow(unused_imports)]
pub use qrc::parse_qrc;
#[allow(unused_imports)]
pub use lrc::{lrc_fixers, parse_lrc, parse_plain};
pub use model::{Line, Lyrics, LyricsSet, Part, Provider, Sync, SyncKind};
#[allow(unused_imports)]
pub use provider::{Client, Query, from_stream};


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enhanced_lrc_parts() {
        let l = parse_lrc("[00:31.69] <00:31.69> Like <00:35.63>   <00:35.64> the <00:35.69>\n[00:40.00] Next", 60_000);
        assert_eq!(l[0].text, "Like the");
        assert_eq!(l[0].parts[0].start, 31690);
        assert_eq!(l[0].parts[0].text, "Like");
        assert_eq!(l[0].parts[1].text, " ");
        assert_eq!(l[0].parts[0].dur, 3940);
        assert_eq!(l[1].start, 40000);
        assert_eq!(l[1].dur, 20000);
    }

    #[test]
    fn lrc_skips_credits_and_reads_offset() {
        let l = parse_lrc("[offset:+0.5]\n[00:01.00]作词 : 野田洋次郎\n[00:05.00]Hello\n[00:07.00]World", 10_000);
        assert_eq!(l.len(), 2);
        assert_eq!(l[0].start, 4500);
        assert_eq!(lrc_songwriters("[00:01.00]作词 : 野田洋次郎\n"), vec!["野田洋次郎"]);
    }

    #[test]
    fn lrc_fixers_move_space_time_to_word() {
        let mut l = parse_lrc("[00:01.00]<00:01.00>a<00:01.50> <00:02.00>b<00:02.50>\n[00:03.00]x", 5000);
        lrc_fixers(&mut l);
        assert_eq!(l[0].parts[0].dur, 1000);
    }

    #[test]
    fn qrc_drops_title_and_reads_syllables() {
        let xml = "<Lyric_1 LyricContent=\"[ti:x]\n[0,6350]Song - Artist(0,198)\n[1000,2000]Get(1000,500) (1500,10)Lucky(1510,490)\n\"/>";
        let l = parse_qrc(xml, 3000, Some(("Song", "Artist")));
        let sung: Vec<&Line> = l.iter().filter(|l| !l.instrumental).collect();
        assert_eq!(sung.len(), 1);
        assert_eq!(sung[0].text, "Get Lucky");
        assert_eq!(sung[0].parts[2].start, 1510);
    }

    #[test]
    fn ttml_parts_background_agents_and_extras() {
        let xml = r#"<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="ja"><head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation xml:lang="en"><text for="L1">Hello world</text></translation></translations><songwriters><songwriter>野田洋次郎</songwriter></songwriters><transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="1.0" end="2.0">kon</span> <span begin="2.0" end="3.0">nichiwa</span></text></transliteration></transliterations></iTunesMetadata></metadata></head><body dur="20.0"><div>
<p begin="1.0" end="3.0" itunes:key="L1" ttm:agent="v1"><span begin="1.0" end="1.5">こん</span><span begin="1.5" end="2.0">に</span> <span begin="2.0" end="3.0">ちは</span><span ttm:role="x-bg"><span begin="2.5" end="3.5">(yeah)</span></span></p>
<p begin="10.0" end="11.0" itunes:key="L2" ttm:agent="v2">Line two</p></div></body></tt>"#;
        let t = parse_ttml(xml, 0);
        assert!(t.word_synced);
        assert_eq!(t.language.as_deref(), Some("ja"));
        assert_eq!(t.songwriters, vec!["野田洋次郎"]);
        let l = &t.lines[0];
        assert_eq!(l.text, "こんに ちは(yeah)");
        assert_eq!(l.parts.len(), 5);
        assert_eq!(l.parts[2], Part { start: 2000, dur: 0, text: " ".into(), bg: false });
        assert!(l.parts[4].bg && l.parts[4].start == 2500);
        assert_eq!(l.agent.as_deref(), Some("v1"));
        assert_eq!(l.translations, vec![("en".to_owned(), "Hello world".to_owned())]);
        assert_eq!(l.romanization.as_deref(), Some("kon nichiwa"));
        assert_eq!(l.timed_roman.len(), 3);
        assert!(t.lines[1].instrumental && t.lines[1].start == 3000);
        assert_eq!(t.lines[2].agent.as_deref(), Some("v2"));
        assert!(t.lines[2].parts.is_empty());
        assert!(t.lines.last().unwrap().instrumental);
    }

    #[test]
    fn ttml_times() {
        assert_eq!(ttml_time("1:02.345"), 62345);
        assert_eq!(ttml_time("12.5s"), 12500);
        assert_eq!(ttml_time("250ms"), 250);
        assert_eq!(ttml_time("01:00:01.000"), 3_601_000);
    }

    #[test]
    fn real_stream_parses_every_provider() {
        let Ok(body) = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/research/nandemonaiya.sse")) else { return };
        let q = Query { video_id: "n89SKAymNfA", song: "Nandemonaiya - movie ver.", artist: "RADWIMPS", album: None, duration: Some(343) };
        let got = from_stream(&body, &q);
        let keys: Vec<&str> = got.iter().map(|l| l.provider.key()).collect();
        assert!(keys.contains(&"bLyrics-richsynced"), "{keys:?}");
        let bl = got.iter().find(|l| l.provider == Provider::BLyricsRich).unwrap();
        assert_eq!(bl.sync, Sync::Word);
        assert!(bl.lines.iter().any(|l| l.timed_roman.len() > 3));
        assert_eq!(bl.songwriters, vec!["野田洋次郎"]);
    }
}
