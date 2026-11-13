

use super::*;

#[test]
fn a_vocal_handover_ducks_the_blend_instead_of_earning_an_effect() {




    let mut out = analysis(0.0, 205.0, vec![0.0, 2.0, 4.0]);
    out.bpm = 100.0;
    out.beat_interval = 0.6;
    out.beat_confidence = 0.8;
    out.content_end = 200.0;
    out.vocal_probability = 0.85;
    with_vocal_curve(&mut out, 205.0, 0.85);
    let mut inc = analysis(0.0, 200.0, vec![0.0, 2.0, 4.0]);
    inc.bpm = 106.0;
    inc.beat_interval = 60.0 / 106.0;
    inc.beat_confidence = 0.8;
    inc.vocal_probability = 0.85;
    with_vocal_curve(&mut inc, 200.0, 0.85);
    let plan = plan_from(&out, &inc, 8.0, true);
    assert!(
        !plan.style.is_effect(),
        "got {:?}: {}",
        plan.style,
        plan.reason
    );
    assert!(plan.vocal_overlap > 0.0, "the clash must still be measured");
    assert!(plan.dip_depth > 0.0, "the mids are ducked");
    assert!(
        plan.reason.contains("ducked"),
        "the reason names the duck: {}",
        plan.reason
    );
}

#[test]
fn an_instrumental_outro_is_not_a_vocal_clash() {



    let mut out = analysis(0.0, 205.0, vec![0.0, 2.0, 4.0]);
    out.bpm = 100.0;
    out.beat_interval = 0.6;
    out.beat_confidence = 0.8;
    out.content_end = 200.0;
    out.vocal_probability = 0.85;
    with_vocal_curve(&mut out, 205.0, 0.85);
    let tail_from = ((200.0 - 8.0 * 0.6) / 0.5) as usize;
    for value in out.vocal_activity_mask.iter_mut().skip(tail_from) {
        *value = 0.15;
    }
    let mut inc = analysis(0.0, 200.0, vec![0.0, 2.0, 4.0]);
    inc.bpm = 106.0;
    inc.beat_interval = 60.0 / 106.0;
    inc.beat_confidence = 0.8;
    inc.vocal_probability = 0.85;
    with_vocal_curve(&mut inc, 200.0, 0.85);
    let plan = plan_from(&out, &inc, 8.0, true);
    assert!(
        !plan.style.is_effect(),
        "got {:?}: {}",
        plan.style,
        plan.reason
    );
    assert!(
        plan.reason.is_empty(),
        "no vocal clash to report: {}",
        plan.reason
    );
    assert_eq!(plan.vocal_overlap, 0.0);
}

#[test]
fn is_vocal_clash_needs_both_sides_above_threshold() {
    assert!(is_vocal_clash(Some(0.8), Some(0.9)));
    assert!(!is_vocal_clash(Some(0.8), Some(0.5)));
    assert!(!is_vocal_clash(None, Some(0.9)));
    assert!(!is_vocal_clash(Some(0.9), None));
}

#[test]
fn vocal_activity_between_averages_the_window() {
    let mut a = analysis(0.0, 200.0, vec![]);
    a.energy_curve = vec![
        audio_analysis::EnergyPoint {
            time: 0.0,
            energy: 1.0,
        },
        audio_analysis::EnergyPoint {
            time: 1.0,
            energy: 1.0,
        },
        audio_analysis::EnergyPoint {
            time: 2.0,
            energy: 1.0,
        },
        audio_analysis::EnergyPoint {
            time: 3.0,
            energy: 1.0,
        },
    ];
    a.vocal_activity_mask = vec![0.0, 0.5, 1.0, 0.5];

    let mean = vocal_activity_between(&a, 0.5, 2.5).unwrap();
    assert!((mean - 0.75).abs() < 1e-9, "mean {mean}");
}
