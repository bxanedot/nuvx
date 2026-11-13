













use std::cell::Cell;
use std::time::{Duration, Instant};



const SLICE: Duration = Duration::from_millis(40);


const LONGEST_REST: Duration = Duration::from_millis(400);


pub(crate) const BACKGROUND_DUTY: f64 = 0.6;

thread_local! {

    static STATE: Cell<Option<(f64, Instant)>> = const { Cell::new(None) };
}



pub(crate) struct Paced {
    previous: Option<(f64, Instant)>,
    _thread_bound: std::marker::PhantomData<std::rc::Rc<()>>,
}

impl Paced {
    pub(crate) fn arm(duty: Option<f64>) -> Self {
        let next = duty
            .filter(|d| d.is_finite() && *d > 0.0 && *d < 1.0)
            .map(|d| (d, Instant::now()));
        let previous = STATE.with(|state| state.replace(next));
        Self { previous, _thread_bound: std::marker::PhantomData }
    }
}

impl Drop for Paced {
    fn drop(&mut self) {
        STATE.with(|state| state.set(self.previous));
    }
}



#[inline]
pub(crate) fn tick() {
    STATE.with(|state| {
        let Some((duty, start)) = state.get() else { return };
        let worked = start.elapsed();
        if worked < SLICE {
            return;
        }
        let rest = worked.mul_f64((1.0 - duty) / duty).min(LONGEST_REST);
        std::thread::sleep(rest);
        state.set(Some((duty, Instant::now())));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unarmed_threads_never_rest() {
        let start = Instant::now();
        for _ in 0..100_000 {
            tick();
        }
        assert!(start.elapsed() < Duration::from_millis(50));
    }

    #[test]
    fn armed_work_rests_to_its_duty_and_disarms_on_drop() {
        let busy = |until: Instant| while Instant::now() < until {};
        {
            let _paced = Paced::arm(Some(0.5));
            let start = Instant::now();
            let mut worked = Duration::ZERO;
            while worked < Duration::from_millis(200) {
                let step = Instant::now();
                busy(step + Duration::from_millis(5));
                worked += step.elapsed();
                tick();
            }
            let wall = start.elapsed();

            assert!(wall >= Duration::from_millis(330), "{wall:?}");
        }
        assert!(STATE.with(|s| s.get()).is_none());
    }

    #[test]
    fn performance_tier_is_unpaced() {
        let _paced = Paced::arm(None);
        assert!(STATE.with(|s| s.get()).is_none());
    }
}
