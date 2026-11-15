//! Sleep timer: counts down, fades the volume out over the last seconds, then pauses.

pub const FADE_SECS: f64 = 10.0;
pub const CHOICES_MIN: [u32; 4] = [15, 30, 45, 60];

#[derive(Default)]
pub struct SleepTimer {
    end: Option<f64>,
}

pub enum Step {
    Idle,
    /// Attenuation in permille (0 = full volume), while the timer runs.
    Fade(u32),
    /// Time is up: pause and restore the volume.
    Done,
}

impl SleepTimer {
    pub fn start(&mut self, minutes: u32, now: f64) {
        self.end = Some(now + minutes as f64 * 60.0);
    }

    pub fn cancel(&mut self) {
        self.end = None;
    }

    pub fn remaining(&self, now: f64) -> Option<f64> {
        self.end.map(|e| (e - now).max(0.0))
    }

    pub fn tick(&mut self, now: f64) -> Step {
        let Some(left) = self.remaining(now) else { return Step::Idle };
        if left <= 0.0 {
            self.end = None;
            return Step::Done;
        }
        Step::Fade(((1.0 - left / FADE_SECS).clamp(0.0, 1.0) * 1000.0).round() as u32)
    }
}

pub fn label(left: f64) -> String {
    let m = (left / 60.0).ceil() as u32;
    format!("{m} min left")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fades_only_in_the_last_seconds_then_finishes() {
        let mut t = SleepTimer::default();
        assert!(matches!(t.tick(0.0), Step::Idle));
        t.start(15, 100.0);
        assert!(matches!(t.tick(200.0), Step::Fade(0)));
        assert!(matches!(t.tick(100.0 + 900.0 - 5.0), Step::Fade(500)));
        assert!(matches!(t.tick(1000.0), Step::Done));
        assert!(matches!(t.tick(1001.0), Step::Idle));
    }

    #[test]
    fn cancel_stops_the_countdown() {
        let mut t = SleepTimer::default();
        t.start(30, 0.0);
        t.cancel();
        assert_eq!(t.remaining(1.0), None);
        assert!(matches!(t.tick(5000.0), Step::Idle));
    }
}
