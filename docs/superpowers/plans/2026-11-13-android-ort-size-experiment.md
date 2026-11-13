# Android ONNX Runtime Size Experiment

> **For agentic workers:** Execute natively in the current worktree; the user authorized implementation and pushes.

**Goal:** Measure a model-specific ONNX Runtime Android AAR against the current full AAR without changing shipped playback or analysis behavior.

**Architecture:** Derive the reduced operator config from both checked-in ONNX models using ONNX Runtime v1.31.1's own generator. Build the custom AAR with the same three Android ABIs and execution providers, compare APKs, and run both models on an Android emulator. Keep the full Maven runtime as the fallback until size and inference evidence is sufficient.

**Tech Stack:** Kotlin/Gradle, ONNX Runtime Android 1.31.1, Python 3.12 + ONNX, Docker on GitHub Actions, Android Emulator.

## Global Constraints

- Preserve both models, their analysis behavior, and all supported ABIs: `armeabi-v7a`, `arm64-v8a`, and `x86_64`.
- Preserve NNAPI, XNNPACK, and WebGPU build support.
- Do not replace the production dependency until all three ABI variants pass model inference and the APK size reduction is measured.
- Pin the ONNX Runtime build to tag `v1.31.1` and verify the operator config against the current model files.

## Review Focus

- A newly used model operator is absent from the config — regenerate and compare against both models.
- One ABI's native library is missing — inspect the AAR and each split APK.
- Quantized LSTM or large vocal inference fails — run both real model graphs, not session creation alone.
- The reduced AAR is larger or slower — retain the Maven runtime and leave issue #230 open.
- Gradle local builds lack Docker — preserve the existing Maven fallback when no custom AAR exists.

### Task 1: Pin and validate the reduced-runtime inputs

**Files:** `app/android/onnxruntime/required_operators.config`, `app/android/onnxruntime/build_settings.json`

- [x] Generate required operators from the current beat and vocal models.
- [x] Verify the generated config equals the operators in both graphs and their subgraphs.
- [x] Match all supported ABIs and existing execution providers in build settings.

### Task 2: Keep runtime selection compatible

**Files:** `app/android/mobile/build.gradle.kts`, `app/android/tv/build.gradle.kts`

- [x] Prefer the generated local custom AAR when present.
- [x] Keep the pinned Maven runtime as the fallback when it is absent.
- [ ] Confirm both modules assemble with either dependency selection.

### Task 3: Compare APKs and execute both models

**Files:** `app/android/mobile/src/androidTest/java/com/bxane/nuvx/OnnxRuntimeModelTest.kt`, `.github/workflows/android-ort-size-experiment.yml`

- [x] Add instrumentation that executes beat and vocal model inference.
- [ ] Add a manual CI workflow that builds the pinned custom AAR, compares full/custom APK sizes for phone and TV, and runs the inference tests on an emulator.
- [ ] Inspect all three ABI libraries and record the measured size result.

### Task 4: Decide whether to ship

- [ ] Only wire the custom AAR into release builds after all ABI inference runs pass and the measured APKs are smaller.
- [ ] Otherwise keep the current runtime, remove the experiment artifacts, and close issue #230 only if the result shows no useful reduction.
