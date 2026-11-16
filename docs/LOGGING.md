# Diagnostics and log format

The app writes compact event logs plus an actual prompt/answer transcript in the app-private files/diagnostics directory.

Files included in diagnostic ZIP exports:
- app.log and app.log.1: UI actions, benchmark events, app-side transport errors and exceptions.
- inference.log and inference.log.1: isolated model load, generation, cancellation, memory and service events.
- interactions.log and interactions.log.1: successful generation prompts and generated answers.
- README.txt: export format, retention and privacy notes.

## Prompt and answer logging

Every successful generation writes one JSON record to interactions.log. It contains:
- UTC timestamp
- original model filename and selected backend
- the actual prompt submitted to inference
- the actual generated answer
- prompt and generated token counts
- native elapsed time

In chat mode, the prompt field contains the ordered system/user/assistant message transcript used to construct the model-default GGUF chat template. The answer field contains the new model response.

The interaction log is intentionally not redacted for prompt/answer content. It is stored locally and included in the diagnostic ZIP. Do not enter passwords, API keys or other secrets in prompts while interaction logging is enabled.

One JSON record is limited to 96 KiB encoded. If an unusually large prompt/answer exceeds that limit, the text is shortened and prompt_truncated/answer_truncated flags are set. The interaction file is capped at 128 KiB plus one previous 128 KiB rotation. Older interactions are discarded when the rotation is replaced.

## Event format

Normal event logs use one UTF-8 line with fixed columns:

    UTC timestamp | level | process | component/event | sorted key=value attributes

Severity values are I (information), W (warning) and E (error). String values are quoted and escaped. Each normal event line is limited to 1,400 characters.

Example:

    2026-11-16 08:12:03.245Z | I | inference | model/native_load_completed | context_tokens=2048 elapsed_ms=18421 native_heap_bytes=17823456
    2026-11-16 08:13:10.512Z | I | inference | generation/native_generation_completed | generated_tokens=64 generation_elapsed_ms=8120 native_elapsed_ms=14590 prompt_tokens=42

Examples are format illustrations, not measurements from a device run.

## Size limits and queueing

- Event log: 128 KiB active + one 128 KiB previous file per process.
- Interaction log: 128 KiB active + one 128 KiB previous file.
- Event writer queue: at most 256 pending records per process.
- Interaction record: at most 96 KiB encoded.
- All event and interaction records use the process-aware asynchronous writer and flush barrier.

The app and inference event files plus interaction transcript remain bounded. Queue saturation and disk errors are recorded as dropped records. Export includes only files at or below their configured cap.

## Performance metadata

Model load and generation boundaries report context, thread/backend settings and memory snapshots. Native measurements distinguish prompt prefill, token generation and native total. Benchmark CSV adds wall time, per-run results, model/prompt SHA-256, backend/GPU layers, model-load duration and device/native backend inventory.

## Export

Use Weitere Aktionen -> Diagnoseprotokoll exportieren (ZIP). Before the snapshot, the app sends a flush barrier to the isolated inference process and flushes the app writer. The archive contains event logs and the actual prompt/answer interaction transcript.
