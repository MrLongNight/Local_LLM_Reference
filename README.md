# Local_LLM_Reference

A small, reproducible Android reference implementation for evaluating local GGUF inference with llama.cpp.

The repository is intentionally structured as an engine that can later be consumed by another Android application (including myDocAnizer) without taking a dependency on this reference UI.

## Module boundaries

| Module | Responsibility | Android dependency |
| --- | --- | --- |
| `:app` | Thin reference UI and manual device checks | Yes |
| `:engine:api` | Stable engine contracts and platform-neutral data types | No |
| `:engine:runtime` | Android model access, lifecycle and isolated inference process | Yes |
| `:engine:native` | JNI bridge, CMake and pinned llama.cpp source | Yes |
| `:benchmark` | Repeatable benchmark scenarios and structured results | Yes |

The app is an example client only. Engine behavior must not live in Activities, Compose screens or UI state holders.

## Engineering rules

- Keep the public API in `:engine:api` free of Android, UI, JNI and llama.cpp types.
- Keep llama.cpp implementation details inside `:engine:native`.
- Keep Android-specific file access and process communication inside `:engine:runtime`.
- Keep benchmark orchestration separate from production inference APIs.
- Never commit GGUF models, model output, private diagnostics or device-specific paths.
- Pin upstream llama.cpp to an exact commit; do not silently track a moving branch.
- Establish a reproducible CPU reference before enabling additional backends such as Vulkan.
- Report measured device results separately from build or host benchmark results.

## Current status

Repository bootstrap. The first milestone is a clean, buildable module skeleton and CI. Native inference and the first on-device CPU baseline follow as separate implementation steps.
