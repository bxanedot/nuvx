








const OPEN_HZ: f32 = 20_000.0;
const OFF_HZ: f32 = 20.0;
const MAX_HIGH_PASS_HZ: f32 = 2_000.0;
const MIN_HZ: f32 = 10.0;
const STAGES: usize = 2;
const BUTTERWORTH_Q: [f32; 2] = [0.54120, 1.30656];
const GLIDE_FRAMES: usize = 64;
const GLIDE_RATE: f32 = 0.05;
const SETTLED_HZ: f32 = 1.0;
const MAX_CUTOFF_FRACTION: f32 = 0.45;



pub struct TransitionFilter {
    channels: usize,
    sample_rate: u32,

    target_low_pass_hz: f32,
    target_high_pass_hz: f32,

    current_low_pass_hz: f32,
    current_high_pass_hz: f32,



    low_state: Vec<f32>,
    high_state: Vec<f32>,

    low_a1: [f32; STAGES],
    low_a2: [f32; STAGES],
    low_a3: [f32; STAGES],
    high_a1: [f32; STAGES],
    high_a2: [f32; STAGES],
    high_a3: [f32; STAGES],
    high_k: [f32; STAGES],
}

impl TransitionFilter {
    pub fn new(channels: usize, sample_rate: u32) -> Self {
        let mut filter = Self {
            channels: channels.max(1),
            sample_rate: sample_rate.max(1),
            target_low_pass_hz: OPEN_HZ,
            target_high_pass_hz: OFF_HZ,
            current_low_pass_hz: OPEN_HZ,
            current_high_pass_hz: OFF_HZ,
            low_state: Vec::new(),
            high_state: Vec::new(),
            low_a1: [0.0; STAGES],
            low_a2: [0.0; STAGES],
            low_a3: [0.0; STAGES],
            high_a1: [0.0; STAGES],
            high_a2: [0.0; STAGES],
            high_a3: [0.0; STAGES],
            high_k: [0.0; STAGES],
        };
        filter.configure();
        filter
    }

    pub fn active(&self) -> bool {
        self.target_low_pass_hz < OPEN_HZ
            || self.target_high_pass_hz > OFF_HZ
            || self.current_low_pass_hz < OPEN_HZ - 1.0
            || self.current_high_pass_hz > OFF_HZ + 0.01
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> usize {
        self.channels
    }

    pub fn open_hz() -> f32 {
        OPEN_HZ
    }

    pub fn off_hz() -> f32 {
        OFF_HZ
    }


    #[cfg(test)]
    pub(crate) fn target_high_pass_hz(&self) -> f32 {
        self.target_high_pass_hz
    }



    pub fn set_cutoffs(&mut self, low_pass_hz: f32, high_pass_hz: f32) {
        self.target_low_pass_hz = low_pass_hz.clamp(MIN_HZ, OPEN_HZ);
        self.target_high_pass_hz = high_pass_hz.clamp(OFF_HZ, MAX_HIGH_PASS_HZ);
    }


    pub fn open(&mut self) {
        self.set_cutoffs(OPEN_HZ, OFF_HZ);
    }

    fn configure(&mut self) {
        self.low_state = vec![0.0; self.channels * STAGES * 2];
        self.high_state = vec![0.0; self.channels * STAGES * 2];
        self.current_low_pass_hz = self.target_low_pass_hz;
        self.current_high_pass_hz = self.target_high_pass_hz;
    }




    pub fn flush(&mut self) {
        self.low_state.fill(0.0);
        self.high_state.fill(0.0);
        self.current_low_pass_hz = self.target_low_pass_hz;
        self.current_high_pass_hz = self.target_high_pass_hz;
    }








    pub fn process(&mut self, samples: &mut [f32]) {
        let ch = self.channels;
        if ch == 0 || samples.is_empty() || samples.len() % ch != 0 {
            return;
        }
        let frame_count = samples.len() / ch;

        let target_low = self.target_low_pass_hz;
        let target_high = self.target_high_pass_hz;
        let parked = target_low >= OPEN_HZ
            && target_high <= OFF_HZ
            && self.current_low_pass_hz >= OPEN_HZ - SETTLED_HZ
            && self.current_high_pass_hz <= OFF_HZ + SETTLED_HZ;
        if parked {


            if self.current_low_pass_hz != target_low || self.current_high_pass_hz != target_high {
                self.flush();
            }
            return;
        }

        let mut remaining = frame_count;
        let mut cursor = 0;
        while remaining > 0 {
            let block = remaining.min(GLIDE_FRAMES);
            self.current_low_pass_hz = Self::glide(self.current_low_pass_hz, target_low);
            self.current_high_pass_hz = Self::glide(self.current_high_pass_hz, target_high);
            let low_on = self.current_low_pass_hz < OPEN_HZ - SETTLED_HZ;
            let high_on = self.current_high_pass_hz > OFF_HZ + SETTLED_HZ;
            if low_on {
                self.update_low_coefficients();
            }
            if high_on {
                self.update_high_coefficients();
            }

            for frame in 0..block {
                for channel in 0..ch {
                    let idx = cursor + frame * ch + channel;
                    let mut value = samples[idx];
                    if low_on {
                        value = self.low_pass(channel, value);
                    }
                    if high_on {
                        value = self.high_pass(channel, value);
                    }


                    samples[idx] = value;
                }
            }
            cursor += block * ch;
            remaining -= block;
        }
    }

    fn glide(current: f32, target: f32) -> f32 {
        let from = current.max(MIN_HZ).ln();
        let to = target.max(MIN_HZ).ln();
        (from + (to - from) * GLIDE_RATE).exp()
    }


    fn usable_cutoff(&self, hz: f32) -> f32 {
        hz.clamp(MIN_HZ, self.sample_rate as f32 * MAX_CUTOFF_FRACTION)
    }

    fn update_low_coefficients(&mut self) {
        let g = (core::f64::consts::PI * self.usable_cutoff(self.current_low_pass_hz) as f64
            / self.sample_rate as f64)
            .tan() as f32;
        for stage in 0..STAGES {
            let k = 1.0 / BUTTERWORTH_Q[stage];
            let a1 = 1.0 / (1.0 + g * (g + k));
            self.low_a1[stage] = a1;
            self.low_a2[stage] = g * a1;
            self.low_a3[stage] = g * (g * a1);
        }
    }

    fn update_high_coefficients(&mut self) {
        let g = (core::f64::consts::PI * self.usable_cutoff(self.current_high_pass_hz) as f64
            / self.sample_rate as f64)
            .tan() as f32;
        for stage in 0..STAGES {
            let k = 1.0 / BUTTERWORTH_Q[stage];
            let a1 = 1.0 / (1.0 + g * (g + k));
            self.high_a1[stage] = a1;
            self.high_a2[stage] = g * a1;
            self.high_a3[stage] = g * (g * a1);
            self.high_k[stage] = k;
        }
    }

    fn low_pass(&mut self, channel: usize, input: f32) -> f32 {
        let mut value = input;
        for stage in 0..STAGES {
            let i = (channel * STAGES + stage) * 2;
            let ic1 = self.low_state[i];
            let ic2 = self.low_state[i + 1];
            let v3 = value - ic2;
            let v1 = self.low_a1[stage] * ic1 + self.low_a2[stage] * v3;
            let v2 = ic2 + self.low_a2[stage] * ic1 + self.low_a3[stage] * v3;
            self.low_state[i] = 2.0 * v1 - ic1;
            self.low_state[i + 1] = 2.0 * v2 - ic2;
            value = v2;
        }
        value
    }

    fn high_pass(&mut self, channel: usize, input: f32) -> f32 {
        let mut value = input;
        for stage in 0..STAGES {
            let i = (channel * STAGES + stage) * 2;
            let ic1 = self.high_state[i];
            let ic2 = self.high_state[i + 1];
            let v3 = value - ic2;
            let v1 = self.high_a1[stage] * ic1 + self.high_a2[stage] * v3;
            let v2 = ic2 + self.high_a2[stage] * ic1 + self.high_a3[stage] * v3;
            self.high_state[i] = 2.0 * v1 - ic1;
            self.high_state[i + 1] = 2.0 * v2 - ic2;
            value -= self.high_k[stage] * v1 + v2;
        }
        value
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, rate: u32, frames: usize) -> Vec<f32> {
        (0..frames * 2)
            .map(|i| {
                let t = (i / 2) as f32 / rate as f32;
                (2.0 * core::f32::consts::PI * freq * t).sin() * 0.5
            })
            .collect()
    }

    fn rms(buf: &[f32]) -> f32 {
        (buf.iter().map(|v| v * v).sum::<f32>() / buf.len() as f32).sqrt()
    }

    #[test]
    fn parked_state_is_an_exact_buffer_copy() {
        let mut filter = TransitionFilter::new(2, 48_000);
        let input = sine(1000.0, 48_000, 1024);
        let mut buf = input.clone();
        filter.process(&mut buf);
        assert_eq!(buf, input);
    }

    #[test]
    fn opening_a_used_filter_retires_its_active_state() {
        let mut filter = TransitionFilter::new(2, 48_000);
        filter.set_cutoffs(OPEN_HZ, 200.0);
        filter.flush();
        filter.open();
        for _ in 0..300 {
            filter.process(&mut vec![0.1; 512]);
        }
        assert!(
            !filter.active(),
            "The open filter remained marked active indefinitely"
        );
        let mut pcm = sine(100.0, 48_000, 512);
        let dry = pcm.clone();
        filter.process(&mut pcm);
        assert_eq!(pcm, dry);
    }

    #[test]
    fn low_pass_attenuates_above_cutoff_and_passes_below() {
        let mut filter = TransitionFilter::new(2, 48_000);

        filter.set_cutoffs(500.0, OFF_HZ);

        for _ in 0..200 {
            let mut warm = sine(4000.0, 48_000, 64);
            filter.process(&mut warm);
        }
        let mut high = sine(4000.0, 48_000, 4096);
        filter.process(&mut high);
        let high_rms = rms(&high);

        filter.flush();
        filter.set_cutoffs(OPEN_HZ, OFF_HZ);
        for _ in 0..200 {
            let mut warm = sine(250.0, 48_000, 64);
            filter.process(&mut warm);
        }
        let mut low = sine(250.0, 48_000, 4096);
        filter.process(&mut low);
        let low_rms = rms(&low);



        assert!(
            low_rms > high_rms * 20.0,
            "passband {low_rms} should far exceed stopband {high_rms}"
        );
    }

    #[test]
    fn geometric_glide_travels_monotonically_toward_target() {
        let mut filter = TransitionFilter::new(2, 48_000);
        filter.flush();
        filter.set_cutoffs(1_000.0, OFF_HZ);







        let mut block = sine(100.0, 48_000, 64);
        let before = block.clone();
        filter.process(&mut block);



        assert!(filter.current_low_pass_hz < OPEN_HZ - SETTLED_HZ);
        assert!(filter.current_low_pass_hz > 1_000.0);
        assert_ne!(block, before);
    }
}
