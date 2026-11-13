

use super::*;

#[test]
fn camelot_neighbours_and_relative_keys_never_clash() {

    assert!(!keys_clash("C major", 0.6, "A minor", 0.6));

    assert!(!keys_clash("A minor", 0.6, "E minor", 0.6));

    assert!(!keys_clash("C major", 0.6, "G major", 0.6));

    assert!(keys_clash("C major", 0.6, "F♯ major", 0.6));

    assert!(!keys_clash("C major", 0.24, "F♯ major", 0.9));
    assert!(!keys_clash("C major", 0.9, "F♯ major", 0.10));

    assert_eq!(camelot("A minor"), Some((8, true)));
    assert_eq!(camelot("C major"), Some((8, false)));
    assert_eq!(camelot("E minor"), Some((9, true)));
    assert_eq!(camelot("G major"), Some((9, false)));
    assert_eq!(camelot(""), None);
}

#[test]
fn phrase_switch_refuses_incompatible_keys() {
    let mut out = analysis(0.5, 200.0, vec![0.5, 1.0, 1.5, 2.0]);
    out.key = "C major".into();
    out.key_confidence = 0.9;
    out.bpm = 120.0;
    out.beat_confidence = 0.8;
    let mut inc = analysis(0.5, 200.0, vec![0.5, 1.0, 1.5, 2.0]);
    inc.key = "D♯ major".into();
    inc.key_confidence = 0.9;
    inc.bpm = 120.0;
    inc.beat_confidence = 0.8;
    assert!(phrase_switch(&out, &inc).is_none());
}

#[test]
fn harmonically_compatible_matches_upstream_semantics() {

    assert!(harmonically_compatible("C major", "G major"));
    assert!(harmonically_compatible("C major", "D major"));
    assert!(!harmonically_compatible("C major", "D♯ major"));

    assert!(harmonically_compatible("C major", "C minor"));
    assert!(!harmonically_compatible("C major", "D minor"));
    assert!(!harmonically_compatible("C major", "G minor"));

    assert!(!harmonically_compatible("", "C major"));
}

#[test]
fn key_distance_matches_upstream_semantics() {
    assert_eq!(key_distance("C major", "C major"), Some(0));

    assert_eq!(key_distance("C major", "G major"), Some(5));

    assert_eq!(key_distance("C major", "C minor"), Some(1));

    assert_eq!(key_distance("", "C major"), None);

    let mut a = analysis(0.0, 0.0, vec![]);
    a.key = "C major".into();
    a.key_confidence = 0.1;
    assert_eq!(trusted_key(&a), "");
}
