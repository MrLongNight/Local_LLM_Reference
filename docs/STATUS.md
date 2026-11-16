# Implementation status

Last updated: 2026-11-16

## Implemented in source

- [x] Modular repository layout on `main`: app, api, runtime, native and benchmark modules.
- [x] Platform-neutral API contracts for model metadata, generation configuration/results and inference lifecycle.
- [x] Exact llama.cpp submodule pin at upstream commit `a946eb541a6d3051496bdcc451a0fa85772b8dfd` (verified signed upstream commit).
- [x] llama.cpp refreshed to the latest signed upstream master commit checked on 2026-11-16; exact submodule pin gate, unit tests, Android lint, native builds for both ABIs and APK packaging verified.
- [x] Native CPU-only JNI baseline using the pinned upstream llama API.
- [x] Original model descriptor access by file descriptor; no engine-owned GGUF copy/rename.
- [x] Dedicated Android inference process and serialized worker execution.
- [x] Service teardown rejects queued requests, aborts active generation and serializes native cleanup.
- [x] Client generation deadline and cancellation forwarding to llama.cpp's CPU abort callback.
- [x] Process-wide request ID allocation prevents collisions across concurrent engine clients.
- [x] Native cancellation is checked before prompt conversion and immediately after non-interruptible tokenization.
- [x] JNI empty-string conversion does not pass a potentially null buffer to `NewString`.
- [x] `threads=0` now preserves llama.cpp's own default; positive values explicitly override both thread pools.
- [x] Native model slot is leased to the engine client that loaded it; cross-client generate/unload requests are rejected and failed replacement loads invalidate the old lease.
- [x] Gradle 9.9.1 wrapper with official wrapper-JAR and distribution SHA-256 pins.
- [x] Current stable GitHub Actions pinned to verified immutable commit SHAs.
- [x] Android CLI version pinned to `1.1.16919734` with CI version gate.
- [x] CI validates wrapper and native submodule pins, runs unit tests and Android lint, builds native arm64-v8a/x86_64 and uploads debug APK.
- [x] Pre-commit CI validates JSON, YAML and TOML plus merge-conflict markers and added-file size.
- [x] Weekly Dependabot monitoring for Gradle, GitHub Actions and git submodules.
- [x] Dependency decisions and compatibility rationale documented.
- [x] Reference app provides SAF GGUF selection, original display name and raw completion.
- [x] Reference UI exposes cancel and 1-warmup/3-measurement CPU benchmark controls.
- [x] In-app reliability self-test exercises long-prompt cancellation, long-generation cancellation, a 1-second request timeout and follow-up generation after each interruption.
- [x] Native metrics separate prompt prefill, token generation and native total time.
- [x] Benchmark runner calculates prefill, generation and end-to-end throughput, averages and medians.
- [x] Benchmark unit test verifies warmup exclusion and prefill/decode metric separation.
- [x] Runtime concurrency test verifies 8,000 unique request IDs across eight concurrent clients.
- [x] Model session ownership tests cover client isolation, replacement-load invalidation and blank identities.
- [x] Persistent OLED-black default theme with manual light-mode toggle and low-glare system bars.
- [x] Process-separated formatted plain-text diagnostics with bounded asynchronous writes, central privacy redaction and ZIP export; export waits for an inference-process IPC flush barrier before snapshotting both process logs.
- [x] Logs are capped at 128 KiB active + one 128 KiB rotation per process; routine IPC/UI duplicates removed and legacy oversized JSONL removed on initialization.
- [x] Model-load and generation boundary events capture managed/native heap and system memory-pressure snapshots.
- [x] Diagnostic privacy policy tests cover sensitive keys, URIs, absolute paths, value limits and safe performance counters.

## Verified

- [x] Previous llama.cpp pin `05db09a4d3c3c1caa7cda31f98eca6ae28d92eb5`: clean native Android build, unit tests and APK artifact succeeded (run 43811994968).
- [x] New llama.cpp pin `a946eb541a6d3051496bdcc451a0fa85772b8dfd`: exact-pin gate, unit tests, Android lint, native arm64-v8a/x86_64 builds and debug APK upload passed on commit `70658c54cd178ec302cc9cc8744cf6978987cde2` (run 43825723769).
- [x] Latest full verification on commit `98e123d8afa949c2e562c36c424c47791d71f4ad`: exact llama.cpp pin gate, unit tests, Android lint, native arm64-v8a/x86_64 builds and debug APK upload all succeeded (run 43827449493).
- [x] Immutable GitHub Actions SHA pins and thread-default correction built successfully (run 43813347672).
- [x] Current full verification, including model-session ownership tests: Android CLI `1.1.16919734` version gate, dependency pin checks, unit tests, Android lint, clean native builds for arm64-v8a and x86_64, and debug APK upload all succeeded on commit `a2a2373e8d5059f727b559209bec20999e409290` (run 43815308166).
- [x] APK artifact `local-llm-reference-debug-apk`: 31,880,247 bytes, SHA-256 `93d38d8fb26fe8ea9b6f01124c1708c33f08aa46abdc14174dd98d5a7bc969b5`.
- [x] Latest verified APK artifact `local-llm-reference-debug-apk`: 31,891,529 bytes, SHA-256 `bcf5f72fa349c0231881907be1147dbbc27d5b3215eda840df3b926361c72bba` (run 43827449493 on commit `98e123d8afa949c2e562c36c424c47791d71f4ad`; expires 2026-11-30).
- [x] User-confirmed physical-device test completed without reported errors (2026-11-16; exact device/model/scenario details were not supplied).
- [x] Reliability self-test implementation compiled, passed unit tests and Android lint, and produced a debug APK on commit `a8093cb892d80bbd6507af7301d2a6f2b1bb79f3` (run 43839389835; artifact `local-llm-reference-debug-apk`, 31,897,076 bytes; SHA-256 `eb44e72f2cc102c67b258b290a4563e39527eb7b9eb2cc3760ba5f6d7968f18a`). This verifies the APK build only.
- [ ] Cancellation/timeout behavior verified on a physical device.
- [x] Repeatable target-device CPU benchmark from user ZIP export (nubia P720F11, Android 16/API 36): 1 warmup + 3 measured runs, 12 prompt tokens, 52 generated tokens/run, mean 6.3307 tok/s and median 6.3322 tok/s; measured decode times 8.201–8.229 s. Model filename/identity is not present in the privacy-filtered export, so this result cannot yet be attributed to a specific GGUF.

## Remaining work — chronological order

These items are intentionally kept in the agreed implementation order. A later phase must not be pulled ahead of an earlier one.

1. **P0 — Physical-device reliability verification.** Run the in-app Abbruch-/Timeout-Selbsttest with a loaded GGUF. It issues a cancellation during a long-prompt request, a cancellation during a long-running completion, a 1-second timeout, and then checks a follow-up generation after each interruption. The probe timing is designed to hit the relevant phase, but the current UI does not expose an independently measured native phase marker; the ZIP export must be inspected before marking P0 complete.
2. **P1 — Model library.** Persist multiple original GGUF document URIs, remember the last selection and reload it automatically after app restart. Keep SAF permissions; do not copy or rename model files.
3. **P1 — Settings.** Configure context size, CPU thread count, output-token limit, timeout and sampling parameters. Validate each value against runtime/native limits and persist settings.
4. **P2 — Vulkan/GPU.** Add an explicit backend choice and CPU/GPU comparison only after the CPU reference and P0 recovery behavior are verified. Keep CPU fallback and report the selected backend honestly.
5. **P2 — Quick/Full test system.** Add deterministic Quick and Full suites, hardware-capability reporting, comparable results and a separate benchmark-result export.
6. **P3 — Chat.** Add model-aware chat templates, system prompt and multi-turn conversation without applying unsupported templates to arbitrary GGUF models.
7. **Final device UI review.** Verify dark/light mode, empty/loaded/running/cancelled states and enlarged font scaling on the physical device.

## P0 self-test — device procedure

1. Install the APK from the workflow below and load a GGUF with a context size of at least 256 tokens (the current default is 2048).
2. Open **Weitere Aktionen → Abbruch-/Timeout-Selbsttest**.
3. A passing run must report the two cancellation probes, the 1-second timeout and all three recovery generations as successful.
4. Export the diagnostic ZIP and inspect the reliability_test and inference_service events before changing the P0 device-verification checkbox.

A successful CI build is not a device result. A passing coroutine cancellation probe alone is not proof of native-stage cancellation; recovery after the interruption and the process-separated logs are required.

The GitHub repository currently has no open Issues and no maintained feature-backlog file. The checklist above is therefore the consolidated list of gaps visible in the current source and the stated reference-app scope; it is not a claim that all items have already been prioritized for implementation.

Source code being present is not evidence that it works on a device. No real-device inference or performance result is claimed until the corresponding device result is inspected.
