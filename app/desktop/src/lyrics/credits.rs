const WRITER_TAGS: &[&str] = &["词", "詞", "作词", "作詞", "曲", "作曲", "词曲", "詞曲", "作词作曲", "作詞作曲", "writtenby", "lyricsby", "composedby", "lyricist", "composer"];
const CREDIT_TAGS: &[&str] = &[
    "编曲", "編曲", "和声", "和聲", "混音", "吉他", "制作人", "製作人", "演唱", "原唱", "翻唱", "后期", "後期", "和音", "录音", "錄音", "策划", "策劃", "伴奏", "美工", "海报", "海報", "旁白", "producedby",
    "arrangedby", "mixing", "mastering", "vocal", "vocals", "guitar", "bass", "drums", "producer", "arranger",
];
const CREDIT_SUFFIXES: &[char] = &['词', '詞', '曲', '声', '聲', '音'];

pub(super) fn norm_tag(t: &str) -> String {
    t.to_lowercase().split_whitespace().collect()
}

pub(super) fn is_credit_tag(t: &str) -> bool {
    WRITER_TAGS.contains(&t) || CREDIT_TAGS.contains(&t) || (t.chars().count() <= 4 && t.ends_with(CREDIT_SUFFIXES))
}

pub(super) fn tag_pieces(n: &str) -> Vec<String> {
    let no_latin: String = n.chars().filter(|c| !c.is_ascii_lowercase()).collect();
    no_latin.split(['/', '&', '、', ',', '，', '・', '·']).filter(|s| !s.is_empty()).map(str::to_owned).collect()
}

pub(super) fn is_credit_label(t: &str) -> bool {
    let n = norm_tag(t);
    if WRITER_TAGS.contains(&n.as_str()) || CREDIT_TAGS.contains(&n.as_str()) {
        return true;
    }
    let pieces = tag_pieces(&n);
    !pieces.is_empty() && pieces.iter().all(|p| is_credit_tag(p))
}

pub(super) fn is_writer_label(t: &str) -> bool {
    let n = norm_tag(t);
    WRITER_TAGS.contains(&n.as_str()) || (is_credit_label(t) && tag_pieces(&n).iter().any(|p| WRITER_TAGS.contains(&p.as_str())))
}

pub(super) fn split_label(t: &str) -> Option<(&str, &str)> {
    let i = t.find([':', '：'])?;
    let sep = t[i..].chars().next()?.len_utf8();
    let (name, rest) = (&t[..i], t[i + sep..].trim_start());
    (!name.is_empty() && !rest.is_empty()).then_some((name, rest))
}

pub(super) fn is_credit_line(t: &str) -> bool {
    split_label(t.trim()).is_some_and(|(name, _)| is_credit_label(name))
}

pub(super) fn split_names(t: &str) -> Vec<String> {
    t.split(['/', '、', '，', ',']).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned).collect()
}

pub(super) fn writers_on(t: &str) -> Vec<String> {
    match split_label(t.trim()) {
        Some((name, rest)) if is_writer_label(name) => split_names(rest),
        _ => vec![],
    }
}

pub(super) fn dedup(names: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for n in names {
        let n = n.trim().to_owned();
        if !n.is_empty() && !out.contains(&n) {
            out.push(n);
        }
    }
    out
}
