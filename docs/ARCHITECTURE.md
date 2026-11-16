# Architecture

## Dependency direction

```
:app ───────────────► :engine:runtime ─────► :engine:api
  │                         │
  │                         ▼
  └──────────────► :benchmark       :engine:native
                           │
                           └──────────────► :engine:api
```

The public API module must remain free of Android, Compose, Room, vault, OCR, JNI and llama.cpp types. Android URI handling belongs in runtime adapters; API model locations are represented as opaque strings so the contract remains portable.

## Milestones

1. Modular build skeleton and dependency boundaries.
2. Pin an exact upstream llama.cpp commit and establish a reproducible CPU-only native build.
3. Implement runtime model access and isolated inference lifecycle.
4. Add a thin manual reference UI for model selection, generation and diagnostics.
5. Add repeatable benchmark scenarios and record on-device results.
6. Evaluate optional backends only after the CPU baseline is reproducible.

## Baseline policy

The first native configuration is CPU-only. Vulkan, OpenMP and dynamic CPU backend variants remain disabled until a controlled comparison demonstrates a reason to enable them. Build success is not evidence of inference correctness or device performance.
