










































































const FRAME: usize = 4096;



#[cfg(test)]
const HOP: usize = FRAME / 4;




const SEARCH: isize = 1200;



const COARSE: usize = 4;



const WEIGHT_FLOOR: f32 = 1.0e-6;


pub struct TimeStretch {
    frame: usize,
    hop: usize,
    search: usize,
    correlate: usize,
    window: Vec<f32>,



    fifo: Vec<f32>,
    base: usize,

    accum: Vec<f32>,





    weight: Vec<f32>,

    out: Vec<f32>,




    anchor: usize,


    anchor_frac: f64,

    placed: Option<usize>,
    rate: f64,


    produced: usize,
    drained: bool,
}

impl TimeStretch {
    pub fn new(rate: f64) -> Self {
        Self::with_sample_rate(rate, 48_000)
    }


    pub fn with_sample_rate(rate: f64, sample_rate: u32) -> Self {
        let scaled = (FRAME as u64 * sample_rate.max(1) as u64 / 48_000).max(4) as usize;
        let frame = scaled.div_ceil(4) * 4;
        let hop = frame / 4;
        let search = (SEARCH as u64 * sample_rate.max(1) as u64 / 48_000).max(1) as usize;
        let window: Vec<f32> = (0..frame)
            .map(|i| (0.5 - 0.5 * (std::f64::consts::TAU * i as f64 / frame as f64).cos()) as f32)
            .collect();
        Self {
            frame,
            hop,
            search,
            correlate: frame / 2,
            window,
            fifo: Vec::new(),
            base: 0,
            accum: vec![0.0f32; frame * 2],
            weight: vec![0.0f32; frame],
            out: Vec::new(),
            anchor: 0,
            anchor_frac: 0.0,
            placed: None,
            rate: rate.clamp(0.5, 2.0),
            produced: 0,
            drained: false,
        }
    }


    fn left_at(&self, frame_index: usize) -> f32 {
        self.fifo[(frame_index - self.base) * 2]
    }


    fn right_at(&self, frame_index: usize) -> f32 {
        self.fifo[(frame_index - self.base) * 2 + 1]
    }






    fn limit(&self) -> usize {
        self.base + self.fifo.len() / 2
    }




    pub fn set_rate(&mut self, rate: f64) {
        self.rate = rate.clamp(0.5, 2.0);
    }

    pub fn rate(&self) -> f64 {
        self.rate
    }


    pub fn take(&mut self) -> Vec<f32> {
        core::mem::take(&mut self.out)
    }








    pub fn latency_frames(&self) -> f64 {
        self.limit().saturating_sub(self.anchor) as f64
    }




    pub fn process(&mut self, input: &[f32]) {
        self.fifo.extend_from_slice(input);
        self.pump();
    }





    pub fn process_and_take(&mut self, input: &[f32]) -> Vec<f32> {
        self.process(input);
        self.take()
    }










    pub fn flush(&mut self) {
        if self.drained {
            return;
        }
        self.drained = true;
        self.rate = 1.0;



        loop {



            let at = self.placed.map(|p| p + self.hop).unwrap_or(self.anchor);
            if self.limit() < at + self.frame {
                break;
            }
            self.place(at);
        }





        let held = if self.placed.is_some() {
            self.frame - self.hop
        } else {
            0
        };
        for frame in 0..held {




            let norm = self.weight[frame].max(f32::MIN_POSITIVE);
            self.out.push(self.accum[frame * 2] / norm);
            self.out.push(self.accum[frame * 2 + 1] / norm);
        }




        let end = self.placed.map(|placed| placed + self.frame).unwrap_or(0);
        if end < self.limit() {
            self.out
                .extend_from_slice(&self.fifo[(end - self.base) * 2..]);
        }
        self.produced = self.out.len() / 2;
        self.anchor = self.limit();
    }


    fn pump(&mut self) {
        loop {



            if self.limit() < self.anchor + self.search as usize + self.frame {
                break;
            }
            let at = self.best_offset();
            self.place(at);
        }
        self.reclaim();
    }




    fn reclaim(&mut self) {
        let horizon = self
            .placed
            .unwrap_or(self.anchor)
            .saturating_sub(self.frame * 2);
        if horizon > self.base {
            let drop = horizon - self.base;
            self.fifo.drain(..drop * 2);
            self.base = horizon;
        }
    }







    fn best_offset(&self) -> usize {
        let Some(previous) = self.placed else {
            return self.anchor;
        };



        let reference = previous + self.hop;
        let limit = self.limit();
        if reference + self.correlate > limit {
            return self.anchor;
        }



        let reference_stereo: Vec<[f32; 2]> = (0..self.correlate)
            .map(|i| [self.left_at(reference + i), self.right_at(reference + i)])
            .collect();
        let earliest = self.anchor.saturating_sub(self.search as usize);
        let latest = self.anchor + self.search as usize;
        let usable = |candidate: usize| {
            candidate >= earliest && candidate <= latest && candidate + self.frame <= limit
        };

        let mut best = self.anchor;
        let mut best_score = f32::NEG_INFINITY;


        for distance in (0..=self.search as usize).step_by(COARSE) {
            let mut considered = false;
            for candidate in [
                self.anchor.checked_sub(distance),
                if distance == 0 {
                    None
                } else {
                    Some(self.anchor + distance)
                },
            ] {
                let Some(candidate) = candidate else { continue };
                if !usable(candidate) {
                    continue;
                }
                considered = true;
                let score = self.similarity(&reference_stereo, candidate, COARSE);
                if score > best_score {
                    best_score = score;
                    best = candidate;
                }
            }
            if considered && best_score > 0.999 {


                if best == self.anchor {
                    return best;
                }
                break;
            }
        }

        let centre = best;
        let mut best_score = self.similarity(&reference_stereo, centre, 1);
        for distance in 1..COARSE {
            for candidate in [centre.checked_sub(distance), Some(centre + distance)] {
                let Some(candidate) = candidate else { continue };
                if !usable(candidate) {
                    continue;
                }
                let score = self.similarity(&reference_stereo, candidate, 1);
                if score > best_score {
                    best_score = score;
                    best = candidate;
                }
            }
        }
        best
    }




    fn similarity(&self, reference: &[[f32; 2]], candidate: usize, stride: usize) -> f32 {
        let mut dot = 0.0f32;
        let mut energy = 0.0f32;
        let mut reference_energy = 0.0f32;
        for i in (0..self.correlate).step_by(stride) {
            let samples = [self.left_at(candidate + i), self.right_at(candidate + i)];
            for ch in 0..2 {
                dot += samples[ch] * reference[i][ch];
                energy += samples[ch] * samples[ch];
                reference_energy += reference[i][ch] * reference[i][ch];
            }
        }
        dot / (reference_energy.max(1.0e-9) * energy.max(1.0e-9)).sqrt()
    }


    fn place(&mut self, at: usize) {
        for frame in 0..self.frame {
            let weight = self.window[frame];
            let left = self.left_at(at + frame);
            let right = self.right_at(at + frame);
            self.accum[frame * 2] += left * weight;
            self.accum[frame * 2 + 1] += right * weight;
            self.weight[frame] += weight;
        }
        for frame in 0..self.hop {
            if self.weight[frame] <= WEIGHT_FLOOR {




                self.out.push(self.left_at(at + frame));
                self.out.push(self.right_at(at + frame));
                continue;
            }
            let norm = self.weight[frame];
            self.out.push(self.accum[frame * 2] / norm);
            self.out.push(self.accum[frame * 2 + 1] / norm);
        }
        self.produced += self.hop;

        self.accum.copy_within(self.hop * 2..self.frame * 2, 0);
        self.weight.copy_within(self.hop..self.frame, 0);
        for sample in (self.frame - self.hop) * 2..self.frame * 2 {
            self.accum[sample] = 0.0;
        }
        for frame in self.frame - self.hop..self.frame {
            self.weight[frame] = 0.0;
        }
        self.placed = Some(at);





        let step = self.hop as f64 * self.rate + self.anchor_frac;
        let whole = step.floor() as usize;
        self.anchor_frac = step - whole as f64;
        self.anchor += whole.max(1);
    }
}




pub fn stretch_whole(input: &[f32], rate: f64) -> Vec<f32> {
    let mut stretcher = TimeStretch::new(rate);
    stretcher.process(input);
    stretcher.flush();
    stretcher.take()
}

#[cfg(test)]
mod tests {
    use super::*;

    use core::f64::consts::PI as TAU;

    const RATE: u32 = 48_000;

    #[test]
    fn retiring_a_glided_stretcher_continues_the_actual_waveform() {
        let mut stage = TimeStretch::new(0.97);
        for second in 0..4 {
            if second == 2 {
                stage.set_rate(1.0);
            }
            let input: Vec<f32> = (second * RATE..(second + 1) * RATE)
                .flat_map(|i| {
                    let x = (i as f64 * 2.0 * TAU * 440.0 / RATE as f64).sin() as f32 * 0.3;
                    [x, x]
                })
                .collect();
            for chunk in input.chunks(1024) {
                stage.process(chunk);
                stage.take();
            }
        }
        let continuation = stage.placed.unwrap() + stage.hop;
        let expected: Vec<f32> = (continuation..stage.limit())
            .flat_map(|i| [stage.left_at(i), stage.right_at(i)])
            .collect();
        stage.flush();
        let tail = stage.take();
        assert_eq!(
            tail.len(),
            expected.len(),
            "The flush jumped from the waveform placement to the nominal anchor"
        );
        for (a, b) in tail.iter().zip(expected) {
            assert!(
                (a - b).abs() < 1e-4,
                "The glide hand-back mixed different waveform phases"
            );
        }
        assert_eq!(stage.latency_frames(), 0.0);
    }

    #[test]
    fn bass_alignment_scales_with_the_output_route() {
        for sample_rate in [44_100u32, 96_000] {
            for hz in [30.0, 60.0] {
                for rate in [0.97, 1.03] {
                    let input: Vec<f32> = (0..sample_rate * 3)
                        .flat_map(|i| {
                            let x =
                                (i as f64 * 2.0 * TAU * hz / sample_rate as f64).sin() as f32 * 0.3;
                            [x, x]
                        })
                        .collect();
                    let mut stage = TimeStretch::with_sample_rate(rate, sample_rate);
                    let mut output = stage.process_and_take(&input);
                    stage.flush();
                    output.extend(stage.take());
                    let actual = frequency_at_rate(
                        &output,
                        hz,
                        sample_rate as usize,
                        sample_rate as usize,
                        sample_rate,
                    );
                    let cents = cents_off(actual, hz);
                    assert!(
                        cents.abs() < 12.0,
                        "{sample_rate} Hz route, {hz} Hz bass, tempo {rate}: {cents:.1} cents"
                    );
                }
            }
        }
    }

    #[test]
    fn tempo_changes_preserve_deep_bass_pitch() {
        for hz in [30.0, 45.0, 60.0] {
            for rate in [0.97, 1.03] {
                let input: Vec<f32> = (0..RATE as usize * 4)
                    .flat_map(|i| {
                        let x = (i as f64 * 2.0 * TAU * hz / RATE as f64).sin() as f32 * 0.3;
                        [x, x]
                    })
                    .collect();
                let output = stretch_whole(&input, rate);
                let actual = frequency_at(&output, hz, RATE as usize, RATE as usize);
                let cents = cents_off(actual, hz);
                assert!(
                    cents.abs() < 12.0,
                    "{hz} Hz bass at tempo {rate} shifted by {cents:.1} cents"
                );
            }
        }
    }

    #[test]
    fn alignment_is_invariant_to_channel_polarity() {
        for rate in [0.97, 1.03] {
            let render = |right_polarity: f32| {
                let input: Vec<f32> = (0..RATE as usize * 2)
                    .flat_map(|i| {
                        let x = (i as f64 * 2.0 * TAU * 440.0 / RATE as f64).sin() as f32 * 0.3;
                        [x, x * right_polarity]
                    })
                    .collect();
                let mut stretch = TimeStretch::new(rate);
                let mut output = Vec::new();
                for chunk in input.chunks(1024) {
                    output.extend(stretch.process_and_take(chunk));
                }
                stretch.flush();
                output.extend(stretch.take());
                output
            };
            let same = render(1.0);
            let opposite = render(-1.0);
            assert_eq!(same.len(), opposite.len());
            for (a, b) in same.chunks_exact(2).zip(opposite.chunks_exact(2)) {
                assert_eq!(
                    a[0], b[0],
                    "right-channel polarity changed left-channel alignment at {rate}"
                );
                assert_eq!(a[1], -b[1]);
            }
        }
    }



    fn tone(frames: usize, hz: f64) -> Vec<f32> {
        (0..frames * 2)
            .map(|i| {
                let t = (i / 2) as f64 / RATE as f64;
                let value = (2.0 * TAU * hz * t).sin() + 0.5 * (2.0 * TAU * 2.0 * hz * t).sin();
                ((value / 1.5) as f32) * 0.5
            })
            .collect()
    }




    fn frequency_at(samples: &[f32], hz: f64, start: usize, frames: usize) -> f64 {
        frequency_at_rate(samples, hz, start, frames, RATE)
    }

    fn frequency_at_rate(
        samples: &[f32],
        hz: f64,
        start: usize,
        frames: usize,
        sample_rate: u32,
    ) -> f64 {
        let magnitude = |probe: f64| -> f64 {
            let w = 2.0 * TAU * probe / sample_rate as f64;
            let coeff = 2.0 * w.cos();
            let (mut s1, mut s2) = (0.0f64, 0.0f64);
            for i in start..start + frames {
                let s0 = samples[i * 2] as f64 + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            (s1 * s1 + s2 * s2 - coeff * s1 * s2).max(0.0)
        };
        let step = 1.0f64;
        let before = magnitude(hz - step);
        let centre = magnitude(hz);
        let after = magnitude(hz + step);
        let denominator = before - 2.0 * centre + after;
        if denominator.abs() < 1.0e-12 {
            return hz;
        }
        hz + 0.5 * step * (before - after) / denominator
    }

    fn cents_off(measured: f64, expected: f64) -> f64 {
        1200.0 * (measured / expected).log2()
    }

    #[test]
    fn unity_rate_reconstructs_the_input() {
        let input = tone(RATE as usize * 2, 220.0);
        let output = stretch_whole(&input, 1.0);



        let end = (input.len() / 2).min(output.len() / 2) - FRAME * 2;
        let mut best_delay = 0usize;
        let mut best_error = f32::INFINITY;
        for delay in 0..(FRAME * 2) {
            let mut worst = 0.0f32;
            for i in FRAME * 4..end {
                let at = i * 2;
                worst = worst
                    .max((output[at + delay * 2] - input[at]).abs())
                    .max((output[at + delay * 2 + 1] - input[at + 1]).abs());
            }
            if worst < best_error {
                best_error = worst;
                best_delay = delay;
            }
        }
        assert!(
            best_error < 1.0e-3,
            "unity rate should reconstruct the input, worst sample error {best_error} \
             at a delay of {best_delay} frames"
        );
        assert!(
            best_delay <= FRAME + SEARCH as usize,
            "latency of {best_delay} frames exceeded the {FRAME}+{SEARCH} bound"
        );
    }




    #[test]
    fn flushing_hands_over_without_a_gap() {
        let frames = RATE as usize;
        let input: Vec<f32> = (0..frames * 2)
            .map(|i| 0.1 + (i / 2) as f32 * 1.0e-6)
            .collect();
        let unity = stretch_whole(&input, 1.0);
        assert_eq!(unity.len(), input.len(), "unity must keep every sample");
        for (i, (a, b)) in unity.iter().zip(&input).enumerate() {
            assert!(
                (a - b).abs() < 1.0e-5,
                "unity flush moved sample {} from {b} to {a}",
                i / 2
            );
        }
        for rate in [0.97f64, 1.03] {
            let output = stretch_whole(&input, rate);
            if let Some(i) = output.iter().position(|s| *s < 0.05) {
                panic!("rate {rate}: silent sample at output frame {}", i / 2);
            }
        }
    }

    #[test]
    fn a_faster_speed_shortens_the_stream() {


        let input = tone(RATE as usize * 3, 220.0);
        let base = input.len() as f64;
        let faster = stretch_whole(&input, 1.03).len() as f64;
        let slower = stretch_whole(&input, 0.97).len() as f64;
        assert!(
            faster < base * 0.98,
            "1.03 should shorten the stream, got {faster} vs {base}"
        );
        assert!(
            slower > base * 1.02,
            "0.97 should lengthen the stream, got {slower} vs {base}"
        );
    }

    #[test]
    fn stretching_preserves_pitch() {


        for rate in [0.97f64, 1.03] {
            let input = tone(RATE as usize * 4, 220.0);
            let output = stretch_whole(&input, rate);
            let start = FRAME * 8;
            let frames = RATE as usize / 2;
            assert!(output.len() > (start + frames) * 2 + 2);
            let cents = cents_off(frequency_at(&output, 220.0, start, frames), 220.0);
            assert!(
                cents.abs() < 12.0,
                "rate {rate} moved 220 Hz by {cents:.1} cents — that is a resampler, not WSOLA"
            );
        }
    }

    #[test]
    fn streaming_in_chunks_matches_the_whole_buffer() {
        let input = tone(RATE as usize * 3, 220.0);
        let whole = stretch_whole(&input, 1.031);



        let mut streamed = TimeStretch::new(1.031);
        let mut at = 0usize;
        let mut sizes = vec![1024usize, 512, 96, 4096, 2048];
        while at < input.len() {
            let take = sizes.remove(0).min((input.len() - at) / 2) * 2;
            sizes.push(1024);
            streamed.process(&input[at..at + take]);
            at += take;
        }
        streamed.flush();
        let chunked = streamed.take();
        assert_eq!(
            chunked.len(),
            whole.len(),
            "chunking changed the output length"
        );
        let mut worst = 0.0f32;
        for i in 0..whole.len() {
            worst = worst.max((whole[i] - chunked[i]).abs());
        }
        assert!(
            worst < 1.0e-3,
            "chunked streaming diverged from the whole buffer by {worst}"
        );
    }

    #[test]
    fn latency_is_bounded_and_does_not_grow() {
        let mut stretcher = TimeStretch::new(0.97);
        let mut first = 0.0f64;
        for _ in 0..200 {
            stretcher.process(&tone(2048, 220.0));
            let latency = stretcher.latency_frames();


            if stretcher.produced == 0 {
                continue;
            }
            if first == 0.0 {
                first = latency;
            }
            assert!(
                latency <= (FRAME + SEARCH as usize) as f64,
                "latency {latency} frames exceeded the {FRAME}+{SEARCH} bound"
            );


            assert!(
                (latency - first).abs() < HOP as f64,
                "latency drifted from {first} to {latency} — it must not accumulate"
            );
        }
    }

    #[test]
    fn the_search_cannot_drift_the_effective_rate() {




        let input = tone(RATE as usize * 4, 220.0);

        for rate in [0.97f64, 1.03] {
            let output = stretch_whole(&input, rate).len() as f64;
            let ratio = output / input.len() as f64;
            let expected = 1.0 / rate;
            assert!(
                (ratio - expected).abs() < 0.005,
                "asked for speed {rate}, expected a length ratio of {expected:.4}, got {ratio:.4}"
            );
        }
    }





    #[test]
    fn clicks_sped_up_from_116_land_on_120() {
        let speed = 120.0 / 116.0;
        let input = clicks_at(116.0, 16);
        let output = stretch_whole(&input, speed);
        let spacing = onset_spacing(&output);
        assert!(
            spacing.len() >= 12,
            "expected a click per beat, found {} gaps",
            spacing.len()
        );
        let expected = RATE as f64 * 60.0 / 120.0;
        for gap in spacing {
            let error = (gap as f64 - expected).abs() / expected;
            assert!(
                error < 0.01,
                "onset spacing {gap} samples is {error:.3} off a 120 BPM grid ({expected:.0})"
            );
        }
    }



    fn clicks_at(bpm: f64, beats: usize) -> Vec<f32> {
        let interval = (RATE as f64 * 60.0 / bpm).round() as usize;
        let burst = RATE as usize * 8 / 1000;
        let frames = interval * beats + RATE as usize;
        let mut samples = vec![0.0f32; frames * 2];
        for beat in 0..beats {
            let start = beat * interval;
            for i in 0..burst {
                let t = i as f64 / RATE as f64;
                let env = (-t * 500.0).exp();
                let value = (env * (2.0 * TAU * 1000.0 * t).sin()) as f32;
                let at = (start + i) * 2;
                samples[at] = value;
                samples[at + 1] = value;
            }
        }
        samples
    }


    fn onset_spacing(samples: &[f32]) -> Vec<usize> {
        let frames = samples.len() / 2;
        let mut peaks = Vec::new();
        let mut i = 1usize;
        while i + 1 < frames {
            let level = samples[i * 2].abs();
            if level > 0.25
                && level >= samples[(i - 1) * 2].abs()
                && level >= samples[(i + 1) * 2].abs()
            {
                peaks.push(i);
                i += RATE as usize / 10;
            } else {
                i += 1;
            }
        }
        peaks.windows(2).map(|pair| pair[1] - pair[0]).collect()
    }

    #[test]
    fn a_rate_change_mid_stream_is_a_glide_not_a_splice() {
        let input = tone(RATE as usize * 4, 220.0);
        let mut stretcher = TimeStretch::new(1.0);
        let mut output = Vec::new();
        let mut at = 0usize;
        let half = input.len() / 4;
        while at < input.len() {
            if at >= half {
                stretcher.set_rate(0.97);
            }
            let take = 2048.min(input.len() - at);
            stretcher.process(&input[at..at + take]);
            output.extend(stretcher.take());
            at += take;
        }
        stretcher.flush();
        output.extend(stretcher.take());


        let measured = frequency_at(&output, 220.0, FRAME * 4, RATE as usize / 2);
        let cents = cents_off(measured, 220.0);
        assert!(
            cents.abs() < 12.0,
            "a rate change bent the pitch by {cents:.1} cents"
        );
    }
}
