# Implementation status

Last updated: 2026-11-16

## Implemented in source

- [x] Modular repository layout on `main`: app, api, runtime, native and benchmark modules.
- [x] Platform-neutral API contracts for model metadata, generation configuration/results and inference lifecycle.
- [x] Exact llama.cpp submodule pin at upstream commit `05db09a4d3c3c1caa7cda31f98eca6ae28d92eb5` (verified signed upstream commit).
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
- [x] Native metrics separate prompt prefill, token generation and native total time.
- [x] Benchmark runner calculates prefill, generation and end-to-end throughput, averages and medians.
- [x] Benchmark unit test verifies warmup exclusion and prefill/decode metric separation.
- [x] Runtime concurrency test verifies 8,000 unique request IDs across eight concurrent clients.
- [x] Model session ownership tests cover client isolation, replacement-load invalidation and blank identities.

## Verified

- [x] Updated llama.cpp pin `05db09a4d3c3c1caa7cda31f98eca6ae28d92eb5`: clean native Android build, unit tests and APK artifact succeeded (run 43811994968).
- [x] Immutable GitHub Actions SHA pins and thread-default correction built successfully (run 43813347672).
- [x] Current full verification, including model-session ownership tests: Android CLI `1.1.16919734` version gate, dependency pin checks, unit tests, Android lint, clean native builds for arm64-v8a and x86_64, and debug APK upload all succeeded on commit `a2a2373e8d5059f727b559209bec20999e409290` (run 43815308166).
- [x] APK artifact `local-llm-reference-debug-apk`: 31,880,247 bytes, SHA-256 `93d38d8fb26fe8ea9b6f01124c1708c33f08aa46abdc14174dd98d5a7bc969b5`.
- [ ] APK installation and GGUF load/generation smoke test on a physical device.
- [ ] Cancellation/timeout behavior verified on a physical device.
- [ ] Repeatable benchmark measurements on the target device.

Source code being present is not evidence that it works on a device. No real-device inference or performance result is claimed until the corresponding device result is inspected.
