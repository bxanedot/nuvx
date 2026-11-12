use std::collections::HashMap;

use super::credits::{dedup, is_credit_line, split_names, writers_on};
use super::{Line, Part};

fn lrc_time(t: &str) -> i32 {
    let p: Vec<&str> = t.split(':').collect();
    let s = match p.len() {
        1 => p[0].parse::<f64>().unwrap_or(0.0) * 1000.0,
        2 => p[0].parse::<f64>().unwrap_or(0.0).trunc() * 60_000.0 + p[1].parse::<f64>().unwrap_or(0.0) * 1000.0,
        3 => p[0].parse::<f64>().unwrap_or(0.0).trunc() * 3_600_000.0 + p[1].parse::<f64>().unwrap_or(0.0).trunc() * 60_000.0 + p[2].parse::<f64>().unwrap_or(0.0) * 1000.0,
        _ => 0.0,
    };
    s.round() as i32
}

const LRC_META_TAGS: &[&str] = &["ti", "ar", "al", "au", "lr", "length", "by", "offset", "re", "tool", "ve", "#"];

fn line_stamps(s: &str) -> Vec<(std::ops::Range<usize>, &str)> {
    stamps(s, '[', ']')
}

fn word_stamps(s: &str) -> Vec<(std::ops::Range<usize>, &str)> {
    stamps(s, '<', '>')
}

fn stamps(s: &str, open: char, close: char) -> Vec<(std::ops::Range<usize>, &str)> {
    let mut out = Vec::new();
    let mut i = 0;
    while let Some(o) = s[i..].find(open) {
        let o = i + o;
        let Some(c) = s[o + 1..].find(close) else { break };
        let inner = &s[o + 1..o + 1 + c];
        let is_time = inner.split_once(':').is_some_and(|(m, sec)| {
            !m.is_empty() && m.bytes().all(|b| b.is_ascii_digit()) && sec.split_once('.').is_some_and(|(a, b)| !a.is_empty() && !b.is_empty() && a.bytes().chain(b.bytes()).all(|b| b.is_ascii_digit()))
        });
        if is_time {
            out.push((o..o + c + 2, inner));
            i = o + c + 2;
        } else {
            i = o + open.len_utf8();
        }
    }
    out
}

fn split_stamps(s: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut at = 0;
    for (r, t) in word_stamps(s) {
        out.push(&s[at..r.start]);
        out.push(t);
        at = r.end;
    }
    out.push(&s[at..]);
    out
}

fn remove_ranges(s: &str, ranges: &[(std::ops::Range<usize>, &str)]) -> String {
    let mut out = String::with_capacity(s.len());
    let mut at = 0;
    for (r, _) in ranges {
        out.push_str(&s[at..r.start]);
        at = r.end;
    }
    out.push_str(&s[at..]);
    out
}

pub fn parse_lrc(text: &str, song_ms: i32) -> Vec<Line> {
    let mut lines: Vec<Line> = Vec::new();
    let mut meta: HashMap<String, String> = HashMap::new();
    for raw in text.split('\n') {
        let a = raw.trim();
        if let Some(inner) = a.strip_prefix('[').and_then(|r| r.strip_suffix(']'))
            && let Some((tag, val)) = inner.split_once(':')
            && !tag.is_empty()
            && tag.chars().all(|c| c.is_alphanumeric() || c == '_')
            && LRC_META_TAGS.contains(&tag)
        {
            meta.insert(tag.to_owned(), val.to_owned());
            continue;
        }
        let ls = line_stamps(a);
        if ls.is_empty() {
            continue;
        }
        let times: Vec<i32> = ls.iter().map(|(_, t)| lrc_time(t)).collect();
        let (c, u) = (*times.iter().min().unwrap(), *times.iter().max().unwrap());
        let g = remove_ranges(a, &ls).trim().to_owned();
        let (main, bg) = match g.rfind("[bg:") {
            Some(i) if g[i..].trim_end().ends_with(']') => {
                let inner = g[i + 4..].trim_end();
                (g[..i].to_owned(), Some(inner[..inner.len() - 1].to_owned()))
            }
            _ => (g.clone(), None),
        };
        let o = split_stamps(&main);
        let spaced = o.iter().enumerate().any(|(e, w)| e % 2 == 0 && e > 0 && e < o.len() - 1 && !w.is_empty() && w.trim().is_empty());
        let mut parts: Vec<Part> = Vec::new();
        let mut prev: Option<i32> = None;
        let mut b = String::new();
        let mut walk = |w: &str, is_bg: bool, parts: &mut Vec<Part>, b: &mut String| {
            for (m, seg) in split_stamps(w).into_iter().enumerate() {
                if m % 2 == 0 {
                    let mut d = seg.to_owned();
                    if spaced {
                        let t = d.trim();
                        d = if t.is_empty() && !d.is_empty() { " ".to_owned() } else { t.to_owned() };
                    }
                    b.push_str(&d);
                    if let Some(last) = parts.last_mut() {
                        last.text.push_str(&d);
                    }
                } else {
                    let d = lrc_time(seg);
                    if let (Some(p), Some(last)) = (prev, parts.last_mut()) {
                        last.dur = d - p;
                    }
                    parts.push(Part { start: d, dur: 0, text: String::new(), bg: is_bg });
                    prev = Some(d);
                }
            }
        };
        walk(&main, false, &mut parts, &mut b);
        if let Some(bg) = &bg {
            walk(bg, true, &mut parts, &mut b);
        }
        let plain: String = remove_ranges(&main, &word_stamps(&main));
        if is_credit_line(&plain) {
            continue;
        }
        lines.push(Line { start: c, dur: u - c, text: b.trim().to_owned(), parts, ..Default::default() });
    }
    let n = lines.len();
    for l in 0..n {
        if l + 1 < n {
            let next_start = lines[l + 1].start;
            let a = &mut lines[l];
            if a.dur == 0 {
                a.dur = (next_start - a.start).max(0);
            }
            if !a.parts.is_empty() {
                let mut c = next_start;
                for f in &a.parts {
                    c = c.max(f.start);
                }
                let last = a.parts.last_mut().unwrap();
                last.dur = (next_start - last.start).max(0);
                a.dur = (c - a.start).max(0);
            }
        } else {
            let a = &mut lines[l];
            let i = song_ms - a.start;
            if a.dur == 0 && i > 0 {
                a.dur = i;
            }
            if let Some(last) = a.parts.last_mut() {
                let u = song_ms - last.start;
                if u > 0 {
                    last.dur = u;
                }
            }
        }
    }
    if let Some(off) = meta.get("offset") {
        let off = (off.trim().parse::<f64>().unwrap_or(0.0) * 1000.0) as i32;
        for a in &mut lines {
            a.start -= off;
            for p in &mut a.parts {
                p.start -= off;
            }
        }
    }
    lines
}

pub fn lrc_fixers(lines: &mut [Line]) {
    for e in lines.iter_mut() {
        for r in 1..e.parts.len() {
            let (l, o) = {
                let (a, b) = e.parts.split_at_mut(r);
                (&mut a[r - 1], &mut b[0])
            };
            if o.text == " " && l.text != " " {
                let a = o.dur - l.dur;
                if a.abs() <= 15 || o.dur <= 100 {
                    let i = o.dur;
                    l.dur += i;
                    o.dur -= i;
                    o.start += i;
                }
            }
        }
    }
    let (mut short, mut total) = (0, 0);
    for e in lines.iter() {
        if e.parts.is_empty() {
            continue;
        }
        for o in e.parts.iter().take(e.parts.len().saturating_sub(2)) {
            if o.text != " " {
                if o.dur <= 100 {
                    short += 1;
                }
                total += 1;
            }
        }
    }
    if total > 0 && short as f32 / total as f32 > 0.5 {
        for e in 0..lines.len() {
            for o in 0..lines[e].parts.len() {
                if lines[e].parts[o].text == " " || lines[e].parts[o].dur > 400 {
                    continue;
                }
                let next = if o + 1 < lines[e].parts.len() {
                    Some((e, o + 1))
                } else if e + 1 < lines.len() && !lines[e + 1].parts.is_empty() {
                    Some((e + 1, 0))
                } else {
                    None
                };
                match next {
                    None => lines[e].parts[o].dur = 300,
                    Some((ne, no)) => {
                        let a = lines[ne].parts[no].clone();
                        if a.text == " " {
                            lines[e].parts[o].dur += a.dur;
                            let a = &mut lines[ne].parts[no];
                            a.start += a.dur;
                            a.dur = 0;
                        } else {
                            lines[e].parts[o].dur = a.start - lines[e].parts[o].start;
                        }
                    }
                }
            }
        }
    }
}

pub(super) fn lrc_songwriters(text: &str) -> Vec<String> {
    let mut n = Vec::new();
    for s in text.split('\n') {
        let e = s.trim();
        if let Some(inner) = e.strip_prefix('[').and_then(|r| r.strip_suffix(']'))
            && let Some((tag, val)) = inner.split_once(':')
            && (tag == "au" || tag == "lr")
        {
            n.extend(split_names(val));
            continue;
        }
        let ls = line_stamps(e);
        if !ls.is_empty() {
            let o = remove_ranges(e, &ls);
            let o = match o.rfind("[bg:") {
                Some(i) => o[..i].to_owned(),
                None => o,
            };
            n.extend(writers_on(&remove_ranges(&o, &word_stamps(&o))));
        }
    }
    dedup(n)
}

pub fn parse_plain(text: &str) -> Vec<Line> {
    text.split('\n').map(|s| Line { text: s.to_owned(), ..Default::default() }).collect()
}
