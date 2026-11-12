use super::*;
pub(super) fn queries(t: &Track) -> Vec<String> {
    let title = plain_title(&t.name);
    let first = t.artists.first().map(String::as_str).unwrap_or_default();
    let mut out = vec![format!("{title} {first}").trim().to_owned()];
    let full = format!("{} {}", t.name, t.artists.join(" ")).trim().to_owned();
    if !out.contains(&full) {
        out.push(full);
    }
    out
}

pub(super) fn plain_title(s: &str) -> &str {
    let cut = s.find(['(', '[']).into_iter().chain(s.find(" - ")).min().unwrap_or(s.len());
    match s[..cut].trim() {
        "" => s.trim(),
        t => t,
    }
}

pub(super) async fn search_song(api: &Api, t: &Track) -> Result<Option<(YtTrack, bool)>> {
    let mut hits: Vec<YtTrack> = Vec::new();
    let add = |hits: &mut Vec<YtTrack>, found: Vec<YtTrack>| {
        let new: Vec<YtTrack> = found.into_iter().take(5).filter(|h| !hits.iter().any(|x| x.id == h.id)).collect();
        hits.extend(new);
    };
    let queries = queries(t);
    for q in &queries {
        let found = match ytm::search_songs(api, q, false).await {
            Ok(found) => found,
            Err(e) => {
                log::debug!("transfer: signed-out search refused ({e:#}); searching signed in");
                ytm::search_tracks_signed_in(api, q, ytm::Filter::Songs).await?
            }
        };
        add(&mut hits, found);
        if let Some(b) = best(&hits, t).filter(|(_, sure)| *sure) {
            return Ok(Some(b));
        }
    }
    if api.account().is_some() {
        match ytm::search_tracks_signed_in(api, &queries[0], ytm::Filter::Songs).await {
            Ok(found) => add(&mut hits, found),
            Err(e) if hits.is_empty() => return Err(e),
            Err(e) => log::debug!("transfer: signed-in search: {e:#}"),
        }
    }
    Ok(best(&hits, t))
}

pub(super) fn best(hits: &[YtTrack], t: &Track) -> Option<(YtTrack, bool)> {
    let hit = hits.iter().rev().max_by_key(|h| score(h, t))?;
    Some((hit.clone(), matches(hit, t)))
}

pub(super) async fn candidates(api: &Api, t: &Track) -> Result<Vec<(YtTrack, bool)>> {
    let q = queries(t).swap_remove(0);
    let (songs, videos) = tokio::try_join!(ytm::search_tracks_signed_in(api, &q, ytm::Filter::Songs), ytm::search_tracks_signed_in(api, &q, ytm::Filter::Videos))?;
    let mut out: Vec<(YtTrack, bool)> = Vec::new();
    for (hit, video) in songs.into_iter().take(5).map(|s| (s, false)).chain(videos.into_iter().take(3).map(|v| (v, true))) {
        let label = |t: &YtTrack| (t.title.to_lowercase(), t.artist_line().to_lowercase());
        if !out.iter().any(|(h, _)| h.id == hit.id || (!video && label(h) == label(&hit))) {
            out.push((hit, video));
        }
    }
    out.sort_by_key(|(h, video)| (*video, if *video { 0 } else { -score(h, t) }));
    Ok(out)
}

pub(super) fn matches(hit: &YtTrack, t: &Track) -> bool {
    let same_length = gap(hit, t).is_some_and(|g| g <= 3);
    title_matches(hit, t) && !too_long_or_short(hit, t) && (artist_matches(hit, t) || (same_length && other_script(hit, t)))
}

pub(super) fn title_matches(hit: &YtTrack, t: &Track) -> bool {
    title_score(hit, t) > 0
}

pub(super) fn title_score(hit: &YtTrack, t: &Track) -> i32 {
    let want = base_title(&t.name);
    let parts = std::iter::once(hit.title.as_str()).chain(hit.title.split(" - "));
    parts
        .map(base_title)
        .map(|got| match () {
            _ if want.is_empty() || got.is_empty() => 0,
            _ if want == got => 4,
            _ if similar(&want, &got) => 3,
            _ if want.contains(&got) || got.contains(&want) => 2,
            _ => 0,
        })
        .max()
        .unwrap_or(0)
}

pub(super) fn similar(a: &str, b: &str) -> bool {
    let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
    let longest = a.len().max(b.len());
    if a.len().min(b.len()) < 6 || a.len().abs_diff(b.len()) * 7 > longest {
        return false;
    }
    let mut row: Vec<usize> = (0..=b.len()).collect();
    for (i, ca) in a.iter().enumerate() {
        let mut diag = row[0];
        row[0] = i + 1;
        for (j, cb) in b.iter().enumerate() {
            let next = (diag + (ca != cb) as usize).min(row[j] + 1).min(row[j + 1] + 1);
            diag = row[j + 1];
            row[j + 1] = next;
        }
    }
    row[b.len()] * 7 <= longest
}

pub(super) fn artist_matches(hit: &YtTrack, t: &Track) -> bool {
    t.artists.iter().any(|a| same_artist(hit, a))
}

pub(super) fn same_artist(hit: &YtTrack, artist: &str) -> bool {
    let name = |s: &str| {
        let s = s.trim();
        norm(s.get(..4).filter(|p| p.eq_ignore_ascii_case("the ")).map_or(s, |_| &s[4..]))
    };
    let (want, line) = (name(artist), norm(&hit.artist_line()));
    let members: Vec<String> = artist.split(" & ").flat_map(|p| p.split(" and ")).flat_map(|p| p.split(", ")).map(name).collect();
    !want.is_empty()
        && (line.contains(&want)
            || hit.artists.iter().map(|a| name(&a.name)).any(|got| similar(&want, &got) || (members.len() > 1 && members.contains(&got))))
}

pub(super) fn other_script(hit: &YtTrack, t: &Track) -> bool {
    let latin = |s: &str| s.chars().any(|c| c.is_ascii_alphabetic());
    latin(&t.artist_line()) != latin(&hit.artist_line())
}

pub(super) fn gap(hit: &YtTrack, t: &Track) -> Option<u32> {
    Some(hit.duration?.abs_diff(t.duration?))
}

pub(super) fn too_long_or_short(hit: &YtTrack, t: &Track) -> bool {
    gap(hit, t).is_some_and(|g| g > 30.max(t.duration.unwrap_or(0) / 5))
}

pub(super) fn base_title(s: &str) -> String {
    let s = s.trim();
    let from = if s.starts_with(['(', '[']) { s.find([')', ']']).map_or(s.len(), |i| i + 1) } else { 0 };
    let rest = &s[from..];
    let cut = rest.find(['(', '[']).into_iter().chain(rest.find(" - ")).min().map_or(s.len(), |i| from + i);
    norm(&s[..cut])
}

pub(super) fn norm(s: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    s.replace('&', "and").nfkd().filter(|c| !('\u{300}'..='\u{36f}').contains(c) && c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

const VARIANTS: &[&str] = &[
    "remix", "edit", "mix", "live", "karaoke", "instrumental", "cover", "sped up", "slowed", "acoustic", "version", "made famous", "originally performed", "in the style of", "tribute", "tv size",
];

pub(super) fn score(hit: &YtTrack, t: &Track) -> i32 {
    let title = title_score(hit, t);
    let mut score = title;
    for (i, artist) in t.artists.iter().enumerate() {
        if same_artist(hit, artist) {
            score += if i == 0 { 3 } else { 1 };
        }
    }
    score += match gap(hit, t) {
        Some(0..=3) if title > 0 => 3,
        Some(4..=10) if title > 0 => 1,
        _ if too_long_or_short(hit, t) => -3,
        _ => 0,
    };
    let (spotify, youtube) = (t.name.to_lowercase(), hit.title.to_lowercase());
    for v in VARIANTS {
        score += match (youtube.contains(v), spotify.contains(v)) {
            (true, false) => -1,
            (true, true) => 1,
            _ => 0,
        };
    }
    score
}
