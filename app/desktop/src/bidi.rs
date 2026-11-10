use std::borrow::Cow;

use unicode_bidi::ParagraphBidiInfo;

pub fn rtl_letter(c: char) -> bool {
    matches!(c as u32, 0x0590..=0x08FF | 0xFB1D..=0xFDFF | 0xFE70..=0xFEFF | 0x10800..=0x10FFF | 0x1E800..=0x1EFFF)
}

pub fn has_rtl(text: &str) -> bool {
    text.chars().any(rtl_letter)
}

pub fn rtl_base(text: &str) -> bool {
    has_rtl(text) && ParagraphBidiInfo::new(text, None).paragraph_level.is_rtl()
}

fn mirror(c: char) -> char {
    match c {
        '(' => ')',
        ')' => '(',
        '[' => ']',
        ']' => '[',
        '{' => '}',
        '}' => '{',
        '<' => '>',
        '>' => '<',
        '«' => '»',
        '»' => '«',
        '‹' => '›',
        '›' => '‹',
        c => c,
    }
}

pub fn visual(text: &str) -> Cow<'_, str> {
    if !has_rtl(text) {
        return Cow::Borrowed(text);
    }
    Cow::Owned(text.split('\n').map(|l| visual_pieces(l).concat()).collect::<Vec<_>>().join("\n"))
}

pub fn job(text: &str, font: egui::FontId, color: egui::Color32) -> egui::text::LayoutJob {
    use egui::epaint::text::{ByteIndex, LayoutSection};
    let mut job = egui::text::LayoutJob::default();
    let format = egui::TextFormat::simple(font, color);
    let push = |job: &mut egui::text::LayoutJob, s: &str| {
        let start = job.text.len();
        job.text.push_str(s);
        job.sections.push(LayoutSection { leading_space: 0.0, byte_range: ByteIndex(start)..ByteIndex(job.text.len()), format: format.clone() });
    };
    for (i, line) in text.split('\n').enumerate() {
        if i > 0 {
            push(&mut job, "\n");
        }
        for piece in visual_pieces(line) {
            push(&mut job, &piece);
        }
    }
    job
}

fn visual_pieces(line: &str) -> Vec<String> {
    if !has_rtl(line) {
        return vec![line.to_owned()];
    }
    let info = ParagraphBidiInfo::new(line, None);
    let (levels, runs) = info.visual_runs(0..line.len());
    let mut out = Vec::new();
    for run in runs {
        let text = &line[run.clone()];
        if !levels[run.start].is_rtl() {
            out.push(text.to_owned());
            continue;
        }
        let mut pieces: Vec<Cow<str>> = Vec::new();
        let mut start: Option<usize> = None;
        for (i, c) in text.char_indices() {
            if rtl_letter(c) {
                start.get_or_insert(i);
                continue;
            }
            if let Some(s) = start.take() {
                pieces.push(Cow::Borrowed(&text[s..i]));
            }
            pieces.push(Cow::Owned(mirror(c).to_string()));
        }
        if let Some(s) = start {
            pieces.push(Cow::Borrowed(&text[s..]));
        }
        out.extend(pieces.into_iter().rev().map(Cow::into_owned));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ltr_text_is_untouched() {
        assert!(matches!(visual("Hello (world)"), Cow::Borrowed(_)));
    }

    #[test]
    fn rtl_words_go_right_to_left() {
        assert_eq!(visual("שלום עולם"), "עולם שלום");
        assert_eq!(visual("مرحبا بالعالم"), "بالعالم مرحبا");
        assert_eq!(visual("שלום עולם."), ".עולם שלום");
    }

    #[test]
    fn numbers_and_latin_stay_left_to_right() {
        assert_eq!(visual("שיר 123"), "123 שיר");
        assert_eq!(visual("אני אוהב Taylor Swift"), "Taylor Swift אוהב אני");
        assert_eq!(visual("Song (שלום עולם) remix"), "Song (עולם שלום) remix");
    }

    #[test]
    fn brackets_mirror_in_rtl() {
        assert_eq!(visual("(שלום)"), "(שלום)");
        assert!(rtl_base("שלום world"));
        assert!(!rtl_base("hello שלום"));
    }
}
