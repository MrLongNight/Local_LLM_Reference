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
- [x] CI workflow checks toolchain/submodule pins, assembles the debug APK and uploads it.
- [x] Weekly Dependabot monitoring for Gradle, GitHub Actions and git submodules.
- [x] Dependency decisions and compatibility rationale documented.

## Not yet verified / open

- [ ] Clean CI build with all native targets completed successfully.
- [ ] APK artifact uploaded and inspected from the current successful workflow run.
- [ ] Device installation and GGUF load/generation smoke test.
- [ ] Cancellation/timeout behavior verified on a physical device.
- [ ] Benchmark module implementation and repeatable CPU measurements.
- [ ] Model/chat UI and model picker.
- [ ] Optional Vulkan build and controlled CPU/Vulkan comparison after CPU baseline.
- [ ] API and runtime tests for lifecycle, descriptor handling, process death and cancellation.

Source code being present is not evidence that it compiles or works on a device. No successful build, inference or performance result is claimed until the corresponding run/device result is inspected.
