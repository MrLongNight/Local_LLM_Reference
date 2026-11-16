# Architecture

## Dependency direction

```
:app ───────────────► :engine:runtime ─────► :engine:native
  │                          │
  │                          ▼
  └──────────────► :benchmark       :engine:api
                           ▲
:engine:runtime ───────────┘
```

The reference app depends on runtime and benchmark. Runtime exposes the platform-neutral API and depends internally on native/JNI. Benchmark depends only on API. No engine module depends on app or DMS code.

## Boundaries

- `:engine:api`: Kotlin/JVM contracts only. No Android, Compose, Room, vault, OCR, JNI or llama.cpp types.
- `:engine:runtime`: SAF/file descriptor handling, model lifecycle, Binder IPC, deadlines and service process management.
- `:engine:native`: the only module that knows the llama.cpp C API, JNI and CMake.
- `:benchmark`: independent scenario execution and result calculations. The benchmark report stores only token counts and timings, never prompt or generated text.
- `:app`: sample client only; no native state or engine implementation belongs in Activities or UI state holders.

## Inference process and cancellation

All native model objects belong to the dedicated Android process `:llm_inference`. A single-thread executor serializes load, generate and unload operations. Binder message dispatch remains responsive while native work is active, allowing a cancellation message to reach the native abort flag concurrently. The pinned llama.cpp API documents this abort callback as CPU-only; it is not presented as Vulkan cancellation support.

The runtime passes a duplicated, seekable file descriptor to llama.cpp and retains its owning `FILE*` until the model is freed. The engine does not copy or rename model files.

## Timing semantics

Native timing reports three distinct values:
- Prompt prefill: prompt-token batch evaluation in llama.cpp, excluding tokenizer work.
- Generation: autoregressive decode loop after prefill, excluding prompt prefill.
- Native total: prefill through sampler completion, including sampler setup.

Wall timing wraps the complete suspend request, so it also includes tokenization, IPC and runtime overhead. Generation tok/s must be compared using generation time, not total time. This avoids mislabelling prefill-heavy runs as slow token decoding.

## Reference client

The app uses Android's document picker and sends the selected original `content://` URI to runtime. The initial UI intentionally tests raw text completion without assuming a model-specific chat template. It exposes cancellation and a short CPU benchmark (one warmup plus three measured generations). The UI displays prefill, generation and end-to-end measurements separately. It is a smoke-test UI, not the final end-user chat experience.

## Milestones

1. Modular build skeleton and dependency boundaries.
2. Exact llama.cpp pin and reproducible CPU-only native baseline.
3. Runtime model access, isolated lifecycle and cancellation.
4. Reference UI for model selection, completion, cancellation and benchmarks.
5. Repeatable on-device CPU measurements.
6. Optional backends only after the CPU baseline is reproducible.

## Baseline policy

CPU-only, static backend registration is the control condition. Vulkan, OpenMP, dynamic backend loading, KleidiAI and automatic CPU variants remain disabled until controlled comparisons justify enabling them. A successful build is not evidence of inference correctness or device performance.
