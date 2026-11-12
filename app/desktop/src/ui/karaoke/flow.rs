use super::*;

#[derive(Clone, Copy, PartialEq)]
pub(super) enum Align {
    Left,
    Right,
    Center,
}

pub(super) struct Tok {
    pub(super) text: String,
    pub(super) start: f64,
    pub(super) dur: f64,
    pub(super) bg: bool,
    pub(super) space: bool,
}

pub(super) fn split_part(p: &Part) -> Vec<Tok> {
    let mut chunks: Vec<(String, bool)> = Vec::new();
    for c in p.text.chars() {
        let ws = c.is_whitespace();
        match chunks.last_mut() {
            Some((s, w)) if *w == ws => s.push(c),
            _ => chunks.push((c.to_string(), ws)),
        }
    }
    let total: usize = chunks.iter().filter(|(_, w)| !*w).map(|(s, _)| s.chars().count()).sum();
    let mut cursor = 0usize;
    chunks
        .into_iter()
        .map(|(text, space)| {
            if space {
                return Tok { text, start: 0.0, dur: 0.0, bg: p.bg, space: true };
            }
            let n = text.chars().count();
            let (start_ms, end_ms) = if total == 0 {
                (p.start as f64, p.start as f64)
            } else {
                (
                    p.start as f64 + (p.dur as f64 * cursor as f64 / total as f64).round(),
                    p.start as f64 + (p.dur as f64 * (cursor + n) as f64 / total as f64).round(),
                )
            };
            cursor += n;
            Tok { text, start: start_ms / 1000.0, dur: (end_ms - start_ms) / 1000.0, bg: p.bg, space: false }
        })
        .collect()
}

pub(super) fn line_synced_parts(l: &Line) -> Vec<Part> {
    let mut out = Vec::new();
    let mut word = 0;
    let mut chunks: Vec<(String, bool)> = Vec::new();
    for c in l.text.chars() {
        let ws = c.is_whitespace();
        match chunks.last_mut() {
            Some((s, w)) if *w == ws => s.push(c),
            _ => chunks.push((c.to_string(), ws)),
        }
    }
    for (text, ws) in chunks {
        let start = l.start + (word as f64 * LINE_SYNC_DELAY * 1000.0) as i32;
        out.push(Part { start, dur: 0, text, bg: false });
        if !ws {
            word += 1;
        }
    }
    out
}

fn joining_script(s: &str) -> bool {
    s.chars().any(|c| matches!(c as u32, 0x0600..=0x06FF | 0x0700..=0x074F | 0x0750..=0x077F | 0x0780..=0x07BF | 0x0840..=0x085F | 0x08A0..=0x08FF | 0x1800..=0x18AF | 0xFB50..=0xFDFF | 0xFE70..=0xFEFF))
}

pub(super) struct Flow {
    pub(super) words: Vec<Word>,
    pub(super) groups: Vec<Group>,
    pub(super) height: f32,
    pub(super) width: f32,
}

pub(super) fn text_size(fonts: &mut FontsView<'_>, s: &str, font: &FontId) -> Vec2 {
    fonts.layout_no_wrap(s.to_owned(), font.clone(), Color32::WHITE).size()
}

pub(super) fn flow(fonts: &mut FontsView<'_>, toks: Vec<Tok>, font: &FontId, max_w: f32, line_h: f32, align: Align, rtl: bool) -> Flow {
    enum Item {
        Space,
        Group(Vec<Tok>),
    }
    let mut items: Vec<Item> = Vec::new();
    let toks = toks.into_iter().flat_map(|t| {
        if t.space || joining_script(&t.text) || crate::bidi::has_rtl(&t.text) || t.text.chars().count() < 2 || text_size(fonts, &t.text, font).x <= max_w {
            return vec![t];
        }
        let n = t.text.chars().count();
        t.text
            .chars()
            .enumerate()
            .map(|(i, c)| Tok { text: c.to_string(), start: t.start + t.dur * i as f64 / n as f64, dur: t.dur / n as f64, bg: t.bg, space: false })
            .collect()
    });
    for t in toks {
        if t.space {
            items.push(Item::Space);
            continue;
        }
        match items.last_mut() {
            Some(Item::Group(g)) if g[0].bg == t.bg => g.push(t),
            _ => items.push(Item::Group(vec![t])),
        }
    }
    let space_w = text_size(fonts, " ", font).x.max(font.size * 0.25);
    let mut words: Vec<Word> = Vec::new();
    let mut groups: Vec<Group> = Vec::new();
    let mut rows: Vec<(usize, f32)> = vec![(0, 0.0)];
    let (mut x, mut row) = (0.0f32, 0usize);
    let mut pending_space = false;
    for item in items {
        match item {
            Item::Space => pending_space = !words.is_empty(),
            Item::Group(toks) => {
                let text: String = toks.iter().map(|t| t.text.as_str()).collect();
                let long = text.chars().count() > LONG_GROUP_CHARS;
                let rtl_group = crate::bidi::has_rtl(&text);
                let letters = !joining_script(&text) && !rtl_group;
                let sizes: Vec<Vec2> = toks.iter().map(|t| text_size(fonts, &t.text, font)).collect();
                let w: f32 = sizes.iter().map(|s| s.x).sum();
                let sp = if pending_space { space_w } else { 0.0 };
                pending_space = false;
                let breakable = long || w > max_w;
                if x > 0.0 && x + sp + w > max_w && !breakable {
                    row += 1;
                    x = 0.0;
                    rows.push((words.len(), 0.0));
                } else {
                    x += sp;
                }
                let gi = groups.len();
                let first = words.len();
                for (t, size) in toks.iter().zip(sizes) {
                    if breakable && x > 0.0 && x + size.x > max_w {
                        row += 1;
                        x = 0.0;
                        rows.push((words.len(), 0.0));
                    }
                    words.push(Word {
                        text: t.text.clone(),
                        font: font.clone(),
                        start: t.start,
                        dur: t.dur,
                        line_synced: t.dur <= 0.0,
                        bg: t.bg,
                        long: t.dur > LONG_WORD,
                        pos: pos2(x, row as f32 * line_h + (line_h - size.y) * 0.5),
                        w: size.x,
                        h: size.y,
                        group: gi,
                        letters,
                        rtl: crate::bidi::has_rtl(&t.text),
                    });
                    x += size.x;
                    rows[row].1 = x;
                }
                groups.push(Group { first, words: first..words.len(), wobbles: !long });
            }
        }
    }
    for (r, &(first, rw)) in rows.iter().enumerate() {
        let end = rows.get(r + 1).map_or(words.len(), |n| n.0);
        let row = &mut words[first..end];
        if rtl {
            for w in row.iter_mut() {
                w.pos.x = rw - w.pos.x - w.w;
            }
        }
        let mut i = 0;
        while i < row.len() {
            let mut j = i;
            while j < row.len() && row[j].rtl != rtl {
                j += 1;
            }
            if j > i + 1 {
                let lo = row[i..j].iter().map(|w| w.pos.x).fold(f32::INFINITY, f32::min);
                let hi = row[i..j].iter().map(|w| w.pos.x + w.w).fold(f32::NEG_INFINITY, f32::max);
                for w in &mut row[i..j] {
                    w.pos.x = lo + hi - w.pos.x - w.w;
                }
            }
            i = j.max(i + 1);
        }
    }
    if align != Align::Left {
        for (r, &(first, rw)) in rows.iter().enumerate() {
            let end = rows.get(r + 1).map_or(words.len(), |n| n.0);
            let shift = if align == Align::Right { max_w - rw } else { (max_w - rw) * 0.5 };
            for w in &mut words[first..end] {
                w.pos.x += shift.max(0.0);
            }
        }
    }
    let width = rows.iter().map(|r| r.1).fold(0.0, f32::max);
    let height = if words.is_empty() { 0.0 } else { (row + 1) as f32 * line_h };
    Flow { words, groups, height, width }
}
