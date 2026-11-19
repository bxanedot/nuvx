//! Which nuvx release is newer, and the order releases are offered in. Stable `v*` tags, nightlies
//! `nightly.<run>.<attempt>`; stable -> nightly -> nightly 2 -> stable 2, nightlies only with the switch on.
//! Shared cases: `core/contracts/update-order.json` (also checked by Android's AppUpdateCheckerTest).

pub fn newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| {
        v.trim_start_matches('v')
            .split('.')
            .map(|p| p.parse::<u64>().unwrap_or(0))
            .collect::<Vec<_>>()
    };
    let (a, b) = (parse(a), parse(b));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (
            a.get(i).copied().unwrap_or(0),
            b.get(i).copied().unwrap_or(0),
        );
        if x != y {
            return x > y;
        }
    }
    false
}

/// Releases from before the licht line stay on GitHub as pre-releases named "old". They are other apps with
/// other package IDs and higher version numbers, so no updater may ever offer them.
pub fn is_old_build(name: Option<&str>, tag: &str, prerelease: bool) -> bool {
    name.is_some_and(|n| {
        n.split(|c: char| !c.is_alphanumeric())
            .any(|w| w.eq_ignore_ascii_case("old"))
    }) || prerelease && !tag.starts_with("nightly.")
}

pub fn release_is_newer(
    candidate_version: &str,
    candidate_tag: &str,
    candidate_time: &str,
    current_version: &str,
    current_tag: &str,
    current_time: &str,
    allow_nightly: bool,
) -> bool {
    let (candidate, current) = (
        version_core(candidate_version),
        version_core(current_version),
    );
    if candidate_tag.starts_with("nightly.") && !allow_nightly {
        return false;
    }
    if newer(candidate, current) {
        return true;
    }
    if newer(current, candidate) || candidate_tag == current_tag {
        return false;
    }
    if current_tag.starts_with("nightly.") {
        if candidate_tag.starts_with("nightly.") {
            return nightly_build(candidate_tag)
                .zip(nightly_build(current_tag))
                .is_some_and(|(candidate, current)| candidate > current);
        }
        // Stable -> nightly -> nightly 2 -> stable 2: the stable build of the version these nightlies led up to
        // comes next, with nightlies on or off.
        let published = candidate_time.get(..19).unwrap_or(candidate_time);
        let built = current_time.get(..19).unwrap_or(current_time);
        return !built.is_empty() && published > built;
    }
    let published = candidate_time.get(..19).unwrap_or(candidate_time);
    let built = current_time.get(..19).unwrap_or(current_time);
    !built.is_empty() && published > built
}

pub fn nightly_build(tag: &str) -> Option<(u64, u64)> {
    let mut parts = tag.strip_prefix("nightly.")?.split('.');
    let run = parts.next()?.parse().ok()?;
    let attempt = parts.next()?.parse().ok()?;
    if parts.next().is_some() {
        return None;
    }
    Some((run, attempt))
}

pub fn version_core(version: &str) -> &str {
    version
        .trim_start_matches('v')
        .split(['-', '+'])
        .next()
        .unwrap_or_default()
}

pub fn compare_release_order(
    a_version: &str,
    a_tag: &str,
    a_time: &str,
    b_version: &str,
    b_tag: &str,
    b_time: &str,
) -> std::cmp::Ordering {
    let a_stable = a_tag.starts_with('v');
    let b_stable = b_tag.starts_with('v');
    match a_stable.cmp(&b_stable) {
        std::cmp::Ordering::Equal => {}
        order => return order,
    }

    let (a_core, b_core) = (version_core(a_version), version_core(b_version));
    if newer(a_core, b_core) {
        return std::cmp::Ordering::Greater;
    }
    if newer(b_core, a_core) {
        return std::cmp::Ordering::Less;
    }
    if !a_stable {
        let order = nightly_build(a_tag).cmp(&nightly_build(b_tag));
        if order != std::cmp::Ordering::Equal {
            return order;
        }
    }
    a_time.cmp(b_time)
}

#[cfg(test)]
mod tests {
    #[test]
    fn agrees_with_the_shared_update_order_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../contracts/update-order.json")).unwrap();
        let cases = doc["cases"].as_array().unwrap();
        assert!(cases.len() >= 10);
        for c in cases {
            let s = |v: &serde_json::Value, i: usize| v[i].as_str().unwrap().to_owned();
            let (a, b) = (&c["candidate"], &c["current"]);
            let got = super::release_is_newer(
                &s(a, 0),
                &s(a, 1),
                &s(a, 2),
                &s(b, 0),
                &s(b, 1),
                &s(b, 2),
                c["allowNightly"].as_bool().unwrap(),
            );
            assert_eq!(got, c["newer"].as_bool().unwrap(), "{}", c["name"]);
        }
    }
}
