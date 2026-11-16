# Implementation status

Last updated: 2026-11-16

## Primary prototype goal

This app is primarily a reproducibility/performance-comparison prototype. The central question is whether identical GGUF model bytes, prompt, context, thread and sampling settings produce materially equivalent timings across engine revisions, devices and CPU/Vulkan backends. Chat is secondary. Raw-completion benchmark runs deliberately do not apply chat templates.

## Implemented in source

- [x] Modular API, runtime, native JNI, benchmark and reference-app modules.
- [x] Original GGUF descriptor access; no engine-owned model copy or rename.
- [x] Streaming SHA-256 fingerprint of the complete GGUF source file.
- [x] Persistent library of original SAF document URIs, original filenames, sizes and fingerprints.
- [x] Remembered last selection and automatic reload after app start, subject to a still-valid SAF permission.
- [x] Persistent context, threads, output limit, timeout, temperature, top-p, top-k, seed, backend, GPU-layer and benchmark-suite settings.
- [x] Generation settings passed through IPC to JNI and llama.cpp.
- [x] Greedy default plus temperature/top-p/top-k sampling and fixed-seed sampling.
- [x] CPU reference path and optional Vulkan offload path using the pinned upstream llama.cpp source.
- [x] Vulkan device enumeration and backend inventory in native system information.
- [x] Model-default GGUF chat-template formatting, optional system prompt and multi-turn in-memory conversation. Raw completion remains the benchmark path.
- [x] Isolated inference service, serialized worker and per-client native model lease.
- [x] Cancellation/timeout forwarding and app self-test for long prompt, long decode, 1-second timeout and recovery generations.
- [x] Quick and Full benchmark plans with configurable warmups and measured runs.
- [x] CPU/Vulkan comparison reloads the same original model and captures backend-specific load time.
- [x] Full benchmark metadata: model filename, full model SHA-256, size, prompt SHA-256, prompt character count, context, threads, output limit, timeout, sampling, seed, backend, GPU layers, target device/API/ABIs and native/backend system information.
- [x] Benchmark CSV export contains exact metadata, per-run prefill/decode/wall values, averages and medians. The prompt is identified by hash; prompt text is not duplicated in the benchmark CSV.
- [x] Every successful generation records the actual prompt and generated answer in a separate local interaction log, included in diagnostic ZIP exports.
- [x] Interaction log and event logs use bounded asynchronous writing, 128 KiB rotation and one retained previous segment. Very large single interactions are explicitly marked when truncated.
- [x] Normal event logs remain compact, with location and credential attributes centrally redacted.
- [x] Existing OLED-dark default and manual light appearance are retained.

## Verification

- [x] Historical CPU timing evidence from the supplied device export: 1 warmup + 3 measured runs, mean 6.3307 tok/s and median 6.3322 tok/s; 52 generated tokens in approximately 8.20-8.23 seconds per run on the reported nubia P720F11 / Android 16. The export did not identify the model bytes or complete runtime parameters, so this is not yet a fully matched comparison scenario.
- [x] Earlier code revisions produced successful CI/APK runs 43827449493 and 43839389835. Both predate the current combined feature set.
- [ ] Latest combined source changes require a fresh CI build/APK artifact check.
- [ ] Physical-device cancellation/timeout self-test and app/inference log inspection.
- [ ] Physical-device Vulkan initialization, generation and CPU/Vulkan comparison.
- [ ] Physical device UI review: dark/light, empty/loaded, running/cancelled and enlarged font.

## Remaining items, in order

1. **P0 — Device reliability verification.** Execute the in-app self-test with a loaded model. Confirm the long-prefill cancellation, decode cancellation, 1-second timeout and each subsequent recovery generation from the visible result and exported logs. Do not mark complete from source code or CI alone.
2. **P2 — Vulkan device verification.** Run the Quick comparison on the target GPU using identical model SHA-256, prompt SHA-256, context, threads, output limit, sampling/seed and warmup/measurement counts. Inspect model-load, prefill, decode and wall timing independently.
3. **Final UI verification.** Inspect the rendered dark/light, empty/loaded, running/cancelled and larger-text states on the target device.

A successful APK build proves compilation and packaging only. It does not prove device inference, cancellation behavior or timing reproducibility.
