# Architecture

## Dependency direction

```
:app ───────────────► :engine:runtime ─────► :engine:native
  │                          │
  │                          ▼
  └──────────────► :benchmark ─────────────► :engine:api
                             ▲
:engine:runtime ─────────────┘
```

The reference UI depends on runtime and benchmark. Runtime depends on native and the platform-neutral API. Benchmark depends on the API only. No engine module depends on the app.

## Boundaries

- `:engine:api`: Kotlin/JVM contracts only. No Android, Compose, Room, vault, OCR, JNI or llama.cpp types.
- `:engine:runtime`: SAF/file descriptor handling, model lifecycle, Binder IPC, deadlines and service process management.
- `:engine:native`: the only module that knows the llama.cpp C API, JNI and CMake.
- `:benchmark`: independent scenario execution and result persistence/export. It must not become part of the production inference path.
- `:app`: sample client only; no native state or engine implementation belongs in Activities or UI state holders.

## Inference process and cancellation

All native model objects belong to the dedicated Android process `:llm_inference`. A single-thread executor serializes load, generate and unload operations. Binder message dispatch remains responsive while native work is active, allowing a cancellation message to reach the native abort flag concurrently. The pinned llama.cpp API documents this abort callback as CPU-only; it is not presented as Vulkan cancellation support.

The runtime passes a duplicated, seekable file descriptor to llama.cpp and retains its owning `FILE*` until the model is freed. The engine does not copy or rename model files.

## Milestones

1. Modular build skeleton and dependency boundaries.
2. Exact llama.cpp pin and reproducible CPU-only native baseline.
3. Runtime model access, isolated lifecycle and cancellation.
4. Minimal reference UI for model selection, generation and diagnostics.
5. Repeatable benchmark scenarios and on-device CPU measurements.
6. Optional backends only after the CPU path is reproducible.

## Baseline policy

CPU-only, static backend registration is the control condition. Vulkan, OpenMP, dynamic backend loading, KleidiAI and automatic CPU variants remain disabled until controlled comparisons justify enabling them. A successful build is not evidence of inference correctness or device performance.
