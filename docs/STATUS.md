# Implementation status

Last updated: 2026-11-16

## Implemented in source

- [x] Modular repository layout on `main`: app, api, runtime, native and benchmark modules.
- [x] Platform-neutral API contracts for model metadata, generation configuration/results and inference lifecycle.
- [x] Exact llama.cpp submodule pin at upstream commit `05db09a4d3c3c1caa7cda31f98eca6ae28d92eb5` (verified signed commit; current upstream master at check time).
- [x] Native CPU-only JNI baseline using the pinned upstream llama API.
- [x] Original model descriptor access by file descriptor; no engine-owned GGUF copy/rename.
- [x] Dedicated Android inference process and serialized worker execution.
- [x] Service teardown rejects queued requests, aborts active generation and serializes native cleanup.
- [x] Client generation deadline and cancellation forwarding to llama.cpp's CPU abort callback.
- [x] Process-wide request ID allocation prevents collisions between concurrent engine clients.
- [x] Native cancellation is checked before prompt conversion and immediately after non-interruptible tokenization.
- [x] JNI empty-string conversion does not pass a potentially null buffer to `NewString`.
- [x] Gradle 9.9.1 wrapper with official wrapper-JAR and distribution SHA-256 pins.
- [x] CI workflow checks toolchain/submodule pins, runs unit tests, assembles the debug APK and uploads it.
- [x] Pre-commit CI validates JSON, YAML and TOML plus merge-conflict markers and added-file size.
- [x] Weekly Dependabot monitoring for Gradle, GitHub Actions and git submodules.
- [x] Dependency decisions and compatibility rationale documented.
- [x] Reference app provides SAF GGUF selection, original display name and raw completion.
- [x] Reference UI exposes cancel and 1-warmup/3-measurement CPU benchmark controls.
- [x] Native metrics separate prompt prefill, token generation and native total time.
- [x] Benchmark runner calculates prefill, generation and end-to-end throughput, averages and medians.
- [x] Benchmark unit test verifies warmup exclusion and prefill/decode metric separation.
- [x] Runtime concurrency test verifies 8,000 unique request IDs across eight concurrent clients.

## Verified

- [x] CI clean Android build and APK artifact on commit `f79e348b705c3f5680bc6352d6d0d1d570fbebf7` (run 43786637232).
- [x] CI unit tests and APK artifact on commit `3887aa25a2c0712a490b55871ddbfc8aca2e25de` (run 43787764791).
- [x] Build, unit tests and debug APK artifact for timing, cancellation and runtime request-ID hardening on commit `465d72c7099d8fd0d7f492e2b3832ff7865a966f` (run 43793903832).
- [x] Native late-cancellation and JNI empty-output hardening built and packaged on commit `f4f04d200fd97ea60de096cd0f813688e7500506` (run 43794346530).
- [x] Structured service lifecycle and cancellation logging on commit `0bde8d2a4327862cb529879ca892f4b2d1591e07` (run 43796271176).
- [ ] CI verification after updating llama.cpp to `05db09a4d3c3c1caa7cda31f98eca6ae28d92eb5`.
- [ ] APK installation and GGUF load/generation smoke test on a physical device.
- [ ] Cancellation/timeout behavior verified on a physical device.
- [ ] Repeatable benchmark measurements on the target device.

Source code being present is not evidence that it works on a device. No real-device inference or performance result is claimed until the corresponding device result is inspected.
