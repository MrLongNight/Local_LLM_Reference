# Diagnostics and log format

The reference app writes one compact, human-readable log per Android process:

- `app.log`: user actions, benchmark samples/summary, export failures and app-side transport errors.
- `inference.log`: isolated model loading, native generation, cancellation, memory snapshots and service failures.
- `.log.1`: the immediately previous rotated segment for that process.

The files live in the app-private `files/diagnostics/` directory. No prompt, generated answer, model/document URI, absolute path, password or credential is intentionally written to a log.

## Line format

Every event is one UTF-8 line with fixed columns:

```text
UTC timestamp | level | process | component/event | sorted key=value attributes
```

Severity values are `I` (information), `W` (warning) and `E` (error). String values are quoted; quotes, backslashes and line breaks are escaped. Attribute names are sorted for stable output. Each line is limited to 1,400 characters.

Example:

```text
2026-11-16 08:12:03.245Z | I | inference | model/native_load_completed | context_tokens=2048 elapsed_ms=18421 heap_used_bytes=88342112 native_heap_bytes=17823456
2026-11-16 08:13:10.512Z | I | inference | generation/native_generation_completed | generated_tokens=64 generation_elapsed_ms=8120 native_elapsed_ms=14590 prompt_tokens=42
2026-11-16 08:13:11.001Z | W | inference | inference_service/active_generation_abort_requested | request_id=12
2026-11-16 08:13:11.043Z | E | inference | inference_service/request_failed | command="generate" error_class="RuntimeException" request_id=12
```

The lines above are format examples only, not measurements from a device run.

## Event catalog

| Component | Event | Purpose |
|---|---|---|
| `diagnostics` | `logger_ready` | SDK, device/ABI and initial memory snapshot |
| `model` | `native_load_started` / `native_load_completed` | Context/thread settings, load duration and before/after memory state |
| `generation` | `native_generation_started` / `native_generation_completed` | Token counts, prefill/decode timings and before/after memory state |
| `inference_service` | `active_generation_abort_requested` / `queued_generation_cancel_requested` | Distinguishes active native abort from cancellation before execution |
| `ipc` | `request_cancelled`, `send_failed`, `service_connection_lost` | Client-side cancellation and transport faults |
| `generation` | `request_timed_out` | Client-side generation deadline reached |
| `inference_service` | `request_failed` | Failed load/generation/unload request, with command, request ID and exception class |
| `benchmark` | `sample_completed`, `completed` | Individual measured samples plus average/median summary |

Routine happy-path IPC queue/accepted/sent/started/completed events are deliberately omitted. Model-load and native-generation boundaries are the canonical success milestones; errors, cancellations and transport events remain explicit.

## Memory and timing

Load and generation boundaries record runtime heap used/committed/max, native heap allocated, system available/total memory, Android low-memory threshold/flag, context/thread parameters and request timings.

Native timings distinguish prompt prefill, generation/decode and native total. Benchmark wall time also includes tokenization and IPC. Memory values are point-in-time diagnostics, not guarantees of allocatable memory.

## Size and retention limits

- Each line: at most 1,400 characters.
- Background writer queue: at most 256 pending records per process.
- Active file: at most 128 KiB per process.
- Previous rotation: at most 128 KiB per process.
- App and inference processes together: at most approximately 512 KiB of log text before ZIP overhead.

When the queue or filesystem cannot accept a record, dropped records are counted and the count is included in the next successfully written event. Persistent overload is reported to Logcat at the first loss and then at 100-record intervals. Routine INFO events are not duplicated into Logcat.

On upgrade, oversized legacy JSONL files and oversized old-format log files are removed. A failed rotation fails closed: the writer drops the incoming event rather than appending beyond the size cap. Export includes only files at or below the configured per-file cap.

## Privacy and export

A central attribute policy redacts sensitive field names, URI-like strings, absolute paths and unknown non-scalar objects. Long strings are truncated. Logging call sites should still provide metadata and metrics only; they must never pass prompts or generated text.

Use **Diagnoseprotokoll exportieren (ZIP)** in the app. Before the snapshot, the app sends a flush barrier to the isolated inference process and then flushes its own writer. The ZIP contains current/previous process logs and a `README.txt` explaining the format and limits.

For support or analysis, attach the ZIP or the relevant `.log` file rather than pasting a large raw log into chat.
