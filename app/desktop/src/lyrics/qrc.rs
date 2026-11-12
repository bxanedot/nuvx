use std::collections::HashMap;

use super::credits::{dedup, is_credit_label, writers_on};
use super::{Line, Part, insert_instrumentals};

struct QrcLine {
    time: i32,
    duration: i32,
    text: String,
    syllables: Vec<(String, i32, i32)>,
    agent: Option<String>,
}

struct Singers {
    aliases: HashMap<String, String>,
    next: u32,
    current: Option<String>,
}

fn qrc_content(t: &str) -> String {
    let Some(i) = t.find("LyricContent=\"") else { return t.to_owned() };
    let rest = &t[i + 14..];
    let mut end = None;
    for (j, _) in rest.match_indices('"') {
        let after = rest[j + 1..].trim_start();
        if after.starts_with("/>") || after.starts_with('>') || after.split_once('=').is_some_and(|(k, _)| !k.is_empty() && k.chars().all(|c| c.is_ascii_alphabetic())) {
            end = Some(j);
            break;
        }
    }
    let Some(end) = end else { return t.to_owned() };
    rest[..end].replace("&quot;", "\"").replace("&amp;", "&")
}

fn qrc_header(t: &str) -> Option<(i32, i32, &str)> {
    let inner = t.strip_prefix('[')?;
    let close = inner.find(']')?;
    let (a, b) = inner[..close].split_once(',')?;
    Some((a.parse().ok()?, b.parse().ok()?, &inner[close + 1..]))
}

fn qrc_syllables(t: &str) -> Vec<(String, i32, i32)> {
    let mut out = Vec::new();
    let mut s = 0;
    let mut i = 0;
    while let Some(o) = t[i..].find('(') {
        let o = i + o;
        let parsed = t[o + 1..].find(')').and_then(|c| {
            let (a, b) = t[o + 1..o + 1 + c].split_once(',')?;
            let ok = |x: &str| !x.is_empty() && x.bytes().all(|b| b.is_ascii_digit());
            (ok(a) && ok(b)).then(|| (a.parse::<i32>().ok(), b.parse::<i32>().ok(), o + 2 + c))
        });
        match parsed {
            Some((Some(a), Some(b), end)) => {
                out.push((t[s..o].to_owned(), a, b));
                s = end;
                i = end;
            }
            _ => i = o + 1,
        }
    }
    out
}

fn assign_singer(name: &str, line: &mut QrcLine, st: &mut Singers) {
    if !st.aliases.contains_key(name) {
        let up = name.to_uppercase();
        let group = up == "合" || up == "ALL" || up == "合唱";
        let id = if group {
            "v1000".to_owned()
        } else {
            let id = format!("v{}", st.next);
            st.next += 1;
            id
        };
        st.aliases.insert(name.to_owned(), id);
    }
    line.agent = st.aliases.get(name).cloned();
}

fn retime(line: &mut QrcLine) {
    if let (Some(f), Some(l)) = (line.syllables.first(), line.syllables.last()) {
        line.time = f.1;
        line.duration = l.1 + l.2 - f.1;
    }
}

fn similarity(a: &str, b: &str) -> f32 {
    let (a, b): (Vec<char>, Vec<char>) = (a.to_lowercase().chars().collect(), b.to_lowercase().chars().collect());
    if a.len() < 2 || b.len() < 2 {
        return 0.0;
    }
    let mut map: HashMap<(char, char), i32> = HashMap::new();
    for w in a.windows(2) {
        *map.entry((w[0], w[1])).or_default() += 1;
    }
    let mut hits = 0;
    for w in b.windows(2) {
        if let Some(c) = map.get_mut(&(w[0], w[1]))
            && *c > 0
        {
            *c -= 1;
            hits += 1;
        }
    }
    hits as f32 * 2.0 / (a.len() + b.len() - 2) as f32
}

fn keep_qrc_line(t: &mut QrcLine, st: &mut Singers, early: bool, meta: Option<(&str, &str)>) -> bool {
    if t.syllables.is_empty() {
        if let Some(c) = &st.current {
            t.agent = Some(c.clone());
        }
        return true;
    }
    if early {
        if let Some((title, artist)) = meta
            && (!title.is_empty() || !artist.is_empty())
        {
            let b = |p: &str| p.to_lowercase().chars().filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || ('\u{3000}'..='\u{9fff}').contains(c) || ('\u{ac00}'..='\u{d7af}').contains(c)).collect::<String>();
            let (h, tt, r) = (b(&t.text), b(title), b(artist));
            if (!tt.is_empty() && h.contains(&tt) && !r.is_empty() && h.contains(&r))
                || (!tt.is_empty() && h.contains(&tt) && r.is_empty() && h.chars().count() < tt.chars().count() + 15)
                || (!r.is_empty() && h == r)
            {
                return false;
            }
            if similarity(&t.text, &format!("{title} {artist}")) > 0.5 {
                return false;
            }
        }
        if t.syllables.len() > 2 && t.syllables.iter().all(|s| (s.2 - t.syllables[0].2).abs() < 10) {
            return false;
        }
    }
    if let Some(c) = &st.current {
        t.agent = Some(c.clone());
    }
    let mut r = String::new();
    let mut o = 0;
    for p in &t.syllables {
        r.push_str(&p.0);
        o += 1;
        if r.contains(':') || r.contains('：') {
            break;
        }
        if r.chars().count() > 40 {
            return true;
        }
    }
    let colon = r.find([':', '：']);
    let Some(ci) = colon else { return true };
    let name = r[..ci].trim().to_owned();
    let after = &r[ci + r[ci..].chars().next().unwrap().len_utf8()..];
    if name.is_empty() {
        return true;
    }
    if after.trim().is_empty() {
        if is_credit_label(&name) {
            return false;
        }
        if name.chars().count() <= 30 {
            t.syllables.drain(..o);
            t.text = t.text.get(r.len()..).unwrap_or_default().to_owned();
            assign_singer(&name, t, st);
            st.current = st.aliases.get(&name).cloned();
            retime(t);
        }
        return true;
    }
    if is_credit_label(&name) {
        return false;
    }
    if name.chars().count() > 20 {
        return true;
    }
    let rest = after.trim_start().to_owned();
    let u = o - 1;
    let tail = t.syllables[u].0.find([':', '：']).map(|i| t.syllables[u].0[i..].chars().skip(1).collect::<String>().trim_start().to_owned()).unwrap_or_default();
    if !tail.is_empty() {
        t.syllables[u].0 = tail;
        t.syllables.drain(..u);
    } else {
        t.syllables.drain(..o);
    }
    assign_singer(&name, t, st);
    st.current = st.aliases.get(&name).cloned();
    t.text = rest;
    retime(t);
    true
}

pub fn parse_qrc(text: &str, song_ms: i32, meta: Option<(&str, &str)>) -> Vec<Line> {
    if text.is_empty() {
        return vec![];
    }
    let content = qrc_content(text);
    let mut r: Vec<QrcLine> = Vec::new();
    let mut st = Singers { aliases: HashMap::new(), next: 1, current: None };
    for a in content.split('\n') {
        let i = a.trim();
        if i.is_empty() || (i.starts_with('[') && i[1..].find(':').is_some_and(|c| c > 0 && i[1..1 + c].chars().all(|ch| ch.is_ascii_alphabetic()))) {
            continue;
        }
        let Some((time, duration, rest)) = qrc_header(i) else { continue };
        let syllables = qrc_syllables(rest);
        let text = syllables.iter().map(|s| s.0.as_str()).collect::<String>();
        let mut g = QrcLine { time, duration, text, syllables, agent: None };
        let early = r.len() < 5;
        if keep_qrc_line(&mut g, &mut st, early, meta) {
            r.push(g);
        }
    }
    if r.is_empty() {
        return vec![];
    }
    let n = r.len();
    let lines = (0..n)
        .map(|i| {
            let a = &r[i];
            let mut parts: Vec<Part> = a.syllables.iter().map(|(t, s, d)| Part { start: *s, dur: *d, text: t.clone(), bg: false }).collect();
            let mut u = a.duration;
            if u == 0 && i + 1 < n {
                u = (r[i + 1].time - a.time).max(0);
            } else if u == 0 {
                u = song_ms - a.time;
            }
            if let Some(f) = parts.last_mut()
                && f.dur == 0
            {
                f.dur = if i + 1 < n { (r[i + 1].time - f.start).max(0) } else { song_ms - f.start };
            }
            Line { start: a.time, dur: u, text: a.text.clone(), parts, agent: a.agent.clone(), ..Default::default() }
        })
        .collect();
    insert_instrumentals(lines, song_ms)
}

pub(super) fn qrc_songwriters(text: &str) -> Vec<String> {
    let mut n = Vec::new();
    for s in qrc_content(text).split('\n') {
        if let Some((_, _, rest)) = qrc_header(s.trim()) {
            let t: String = qrc_syllables(rest).into_iter().map(|s| s.0).collect();
            n.extend(writers_on(&t));
        }
    }
    dedup(n)
}
