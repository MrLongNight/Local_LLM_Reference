# Architecture

## Dependency direction

```
                 ┌──────────────► :engine:runtime ─────► :engine:native
:app ────────────┤                    │
                 └──────────────► :benchmark ─────────► :engine:api
                                      ▲
                   :engine:runtime ───┘
```

The reference app depends on runtime and benchmark. Runtime consumes API and owns all Android model-access and process behavior. Native is an implementation detail of runtime. Benchmark depends only on API. No engine module depends on app or DMS code.

## Boundaries

- `:engine:api`: Kotlin/JVM contracts only. No Android, Compose, Room, vault, OCR, JNI or llama.cpp types. The source surface is platform-neutral; the module is packaged as an Android library AAR for direct Android integration.
- `:engine:runtime`: SAF/file descriptor handling, model lifecycle, Binder IPC, deadlines and service process management.
- `:engine:native`: the only module that knows the llama.cpp C API, JNI and CMake.
- `:benchmark`: independent scenario execution and result calculations. The benchmark report stores only token counts and timings, never prompt or generated text. Its source uses no Android framework types.
- `:app`: sample client only; no native state or engine implementation belongs in Activities or UI state holders.

## Inference process and cancellation

All native model objects belong to the dedicated Android process `:llm_inference`. A single-thread executor serializes load, generate and unload operations. Binder message dispatch remains responsive while native work is active, allowing a cancellation message to reach the native abort flag concurrently. The pinned llama.cpp API documents this abort callback as CPU-only; it is not presented as Vulkan cancellation support.

The runtime passes a duplicated, seekable file descriptor to llama.cpp and retains its owning `FILE*` until the model is freed. The engine does not copy or rename model files.

## Thread defaults

A thread count of zero is a sentinel meaning "use llama.cpp defaults". Runtime only overrides `n_threads` and `n_threads_batch` when the client explicitly requests a positive count. The default path therefore follows upstream platform behavior and is not hard-coded to a device core count.

## Timing semantics

Native timing reports three distinct values:
- Prompt prefill: prompt-token batch evaluation in llama.cpp, excluding tokenizer work.
- Generation: autoregressive decode loop after prefill, excluding prompt prefill.
- Native total: prefill through sampler completion, including sampler setup.

Wall timing wraps the complete suspend request, so it also includes tokenization, IPC and runtime overhead. Generation tok/s must be compared using generation time, not total time. This avoids mislabelling prefill-heavy runs as slow token decoding.

## Reference client

The app uses Android's document picker and sends the selected original `content://` URI to runtime. The initial UI intentionally tests raw text completion without assuming a model-specific chat template. It exposes cancellation and a short CPU benchmark (one warmup plus three measured generations). The UI displays prefill, generation and end-to-end measurements separately. It is a smoke-test UI, not the final end-user chat experience.

## Verification

CI runs unit tests, Android lint and a clean debug build. The native library is built for the configured `arm64-v8a` and `x86_64` ABIs. These checks establish build correctness only. Model loading, inference correctness, cancellation and performance still require real-device validation.

## Baseline policy

CPU-only, static backend registration is the control condition. Vulkan, OpenMP, dynamic backend loading, KleidiAI and automatic CPU variants remain disabled until controlled comparisons justify enabling them. A successful build is not evidence of inference correctness or device performance.
