# Implementation status

Last updated: 2026-11-16

## Implemented in source

- [x] Modular repository layout on `main`: app, api, runtime, native and benchmark modules.
- [x] Platform-neutral API contracts for model metadata, generation configuration/results and inference lifecycle.
- [x] Exact llama.cpp submodule pin at upstream commit `9fba51d2c9a1b5cb5f0c47986c031334e2d7663a`.
- [x] Native CPU-only JNI baseline using the pinned upstream llama API.
- [x] Original model descriptor access by file descriptor; no engine-owned GGUF copy/rename.
- [x] Dedicated Android inference process and serialized worker execution.
- [x] Client generation deadline and cancellation forwarding to llama.cpp's CPU abort callback.
- [x] Gradle 9.9.1 wrapper with official wrapper-JAR and distribution SHA-256 pins.
- [x] CI workflow checks toolchain/submodule pins, runs unit tests, assembles the debug APK and uploads it.
- [x] Weekly Dependabot monitoring for Gradle, GitHub Actions and git submodules.
- [x] Dependency decisions and compatibility rationale documented.
- [x] Reference app provides SAF GGUF selection, original display name, direct completion and inference metrics.
- [x] Benchmark runner with warmup, repeated samples, native/wall timing and median/average statistics.

## Verified

- [x] CI clean Android build and debug APK artifact on commit `f79e348b705c3f5680bc6352d6d0d1d570fbebf7` (run 43786637232).
- [ ] Unit-test result for the newly added benchmark test and latest build commit.
- [ ] APK installation and GGUF load/generation smoke test on a physical device.
- [ ] Cancellation/timeout behavior verified on a physical device.
- [ ] Repeatable benchmark measurements on the target device.

Source code being present is not evidence that it works on a device. No real-device inference or performance result is claimed until the corresponding device result is inspected.
