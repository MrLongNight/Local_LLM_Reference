# Local_LLM_Reference

A modular Android reference engine for evaluating local GGUF inference with llama.cpp. The engine is separated from the sample UI so that another Android application can consume it without depending on this reference app.

## Modules

| Module | Responsibility | Android APIs in module source |
|---|---|---|
| `:app` | Thin reference client and manual device checks | Yes |
| `:engine:api` | Stable, platform-neutral contracts and data types | No |
| `:engine:runtime` | SAF/file descriptor access, lifecycle, IPC and isolated process | Yes |
| `:engine:native` | JNI/CMake and pinned llama.cpp implementation | Yes |
| `:benchmark` | Independent benchmark scenarios and structured results | No |

The API and benchmark source code contains no Android framework types. Both are packaged as Android library modules for direct use by Android clients. Dependency direction: app → runtime → native; runtime → api; app → benchmark → api. The engine has no dependency on app/UI code or DMS-specific services.

## Current implementation

- Modular Gradle project and platform-neutral API.
- Exact llama.cpp upstream submodule pin.
- Native CPU reference path and selectable Vulkan backend using the pinned upstream llama.cpp API.
- Original model file descriptor is passed to the isolated process; no model copy or rename is performed by the engine.
- Inference runs in Android process `:llm_inference`, separate from the app process.
- The service has one native model slot and leases it to the engine client instance that loaded it; another client cannot silently generate with or unload that model.
- Generation cancellation is passed from the client through IPC to llama.cpp's CPU abort callback.
- Reference app maintains a persistent original-GGUF URI library, remembers the last model and computes a full-file SHA-256 fingerprint without copying/renaming the model.
- Raw completion remains the benchmark path; separate chat mode uses the selected GGUF's default chat template, optional system prompt and multi-turn in-memory history.
- The UI has a cancel button for generation and the benchmark.
- Native timing separates prompt prefill from token generation. End-to-end wall timing includes tokenizer, IPC and the full request.
- Quick/Full benchmark runner records warmups and measured samples with model and prompt SHA-256, exact context/thread/output/timeout/sampling/backend settings, device/API/ABI/native backend inventory and model load duration. CSV exports include per-run prefill/decode/wall timing and summary statistics.
- When `threads=0`, llama.cpp's own platform default is retained; explicit thread counts override both context and batch thread pools.
- The reference UI starts in persistent OLED-black dark mode, with a manual light-mode switch.
- Structured process-separated diagnostics use bounded, rotated event logs; successful prompts and generated answers are also written to a separate local interaction log included in ZIP exports.
- Each process log is capped at 128 KiB plus one previous 128 KiB segment; duplicate routine IPC lifecycle records are omitted.
- Load and generation diagnostics include managed/native heap and system memory-pressure snapshots, alongside request IDs and stage timings.

The reference client supports decoder-only GGUF models, with CPU as the baseline and an optional Vulkan backend for controlled comparisons. Greedy decoding is the default; temperature, top-p, top-k and a fixed seed are configurable. This is a reproducibility/diagnostics prototype, not a complete end-user inference app. On 2026-11-16, the user's nubia P720F11 device completed model loading and a 1-warmup/3-measurement CPU benchmark (mean 6.3307 tok/s, median 6.3322 tok/s); the exported logs did not identify the selected GGUF. On-device cancellation/timeout edge cases, Vulkan inference and rendered UI review remain unverified. See `docs/STATUS.md` for the exact scope of verification.

## Build

Requirements: JDK 17, Android SDK platform 36, NDK 30.0.16248370 and CMake 3.31.6.

```bash
git clone --recurse-submodules https://github.com/MrLongNight/Local_LLM_Reference.git
cd Local_LLM_Reference
./gradlew --no-daemon clean testDebugUnitTest lint assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions verifies the wrapper, Android CLI and submodule pins, runs unit tests and lint, builds the APK and uploads it as `local-llm-reference-debug-apk`.

## API boundaries

The public API in `:engine:api` contains only Kotlin/JVM data types and suspend interfaces. Android `Uri`, `ParcelFileDescriptor`, Binder, JNI and llama.cpp objects stay inside runtime/native modules. Runtime consumes the API contract and does not expose native implementation details.

Example model descriptor:

```kotlin
ModelDescriptor(
    displayName = "Original model name",
    sourceUri = selectedDocumentUri.toString(),
    contextTokens = 2048,
    threads = 0,
)
```

The opaque URI is resolved inside the Android runtime. The current implementation accepts seekable `content://`, `file://` and local path sources.

## Engineering rules

- Keep model and backend handling model-agnostic.
- Pin all native source dependencies to exact upstream commits.
- Keep the API free of Android, UI, JNI, llama.cpp, Room, vault and OCR types.
- Keep the app process free of direct JNI/inference calls.
- Never commit GGUF models, generated model output, private diagnostics or device-specific paths.
- Do not describe build success as inference success, or infer performance from host/CI timings.
- Optional Vulkan/OpenMP/CPU variants remain separate experiments until the CPU reference is reproducible and measured.

## Maintenance

See [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) for version selection, upstream compatibility checks and the update policy. See [docs/LOGGING.md](docs/LOGGING.md) for log structure, event meanings, privacy and size limits. Dependabot checks Gradle, GitHub Actions and the llama.cpp submodule weekly.
