# Local_LLM_Reference

A modular Android reference engine for evaluating local GGUF inference with llama.cpp. The engine is separated from the sample UI so that another Android application can consume it without depending on this reference app.

## Modules

| Module | Responsibility | Android dependency |
|---|---|---|
| `:app` | Thin reference client and manual device checks | Yes |
| `:engine:api` | Stable, platform-neutral contracts and data types | No |
| `:engine:runtime` | SAF/file descriptor access, lifecycle, IPC and isolated process | Yes |
| `:engine:native` | JNI/CMake and pinned llama.cpp implementation | Yes |
| `:benchmark` | Independent benchmark scenarios and structured results | Yes |

Dependency direction: app → runtime → native; runtime → api; app → benchmark → api. The engine has no dependency on app/UI code or DMS-specific services.

## Current implementation

- Modular Gradle project and platform-neutral API.
- Exact llama.cpp upstream submodule pin.
- Native CPU-only decoder path using the upstream llama API.
- Original model file descriptor is passed to the isolated process; no model copy or rename is performed by the engine.
- Inference runs in Android process `:llm_inference`, separate from the app process.
- Generation cancellation is passed from the client through IPC to llama.cpp's CPU abort callback.
- Reference app selects a GGUF file through Android's document picker, retains the provider URI and displays the original file name.
- Reference app runs direct completion without assuming a model-specific chat template.
- The UI has a cancel button for generation and the benchmark.
- Native timing separates prompt prefill from token generation. End-to-end wall timing includes tokenizer, IPC and the full request.
- Benchmark runner provides warmups, repeated measured samples, native prefill throughput, native generation throughput and end-to-end throughput. Reports contain timing/token counts only, not prompt or generated text.

The first reference path supports decoder-only GGUF models and deterministic greedy sampling. It is a CPU baseline, not a performance claim. Clean CI builds, benchmark unit tests and APK artifact uploads have been verified. Real-device model loading, generation, cancellation and benchmark results remain to be verified.

## Build

Requirements: JDK 17, Android SDK platform 36, NDK 30.0.16248370 and CMake 3.31.6.

```bash
git clone --recurse-submodules https://github.com/MrLongNight/Local_LLM_Reference.git
cd Local_LLM_Reference
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions verifies the wrapper and submodule pins, runs unit tests, builds the APK and uploads it as `local-llm-reference-debug-apk`.

## API boundaries

The public API in `:engine:api` contains only Kotlin/JVM data types and suspend interfaces. Android `Uri`, `ParcelFileDescriptor`, Binder, JNI and llama.cpp objects stay inside runtime/native modules. Runtime re-exports the API contract; it does not re-export native implementation details.

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

See [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) for version selection, upstream compatibility checks and the update policy. Dependabot checks Gradle, GitHub Actions and the llama.cpp submodule weekly.
