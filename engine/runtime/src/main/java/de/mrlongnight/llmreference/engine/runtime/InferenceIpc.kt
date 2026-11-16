package de.mrlongnight.llmreference.engine.runtime

internal object InferenceIpc {
    const val LOAD_MODEL = 1
    const val GENERATE = 2
    const val UNLOAD_MODEL = 3
    const val SYSTEM_INFO = 4
    const val FLUSH_DIAGNOSTICS = 6
    const val CANCEL_GENERATION = 5
    const val RESULT = 100

    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    const val KEY_CLIENT_ID = "client_id"
    const val KEY_DESCRIPTOR = "model_descriptor"
    const val KEY_CONTEXT_TOKENS = "context_tokens"
    const val KEY_THREADS = "threads"
    const val KEY_PROMPT = "prompt"
    const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
    const val KEY_TEXT = "text"
    const val KEY_PROMPT_TOKENS = "prompt_tokens"
    const val KEY_GENERATED_TOKENS = "generated_tokens"
    const val KEY_PROMPT_ELAPSED_MILLIS = "prompt_elapsed_millis"
    const val KEY_GENERATION_ELAPSED_MILLIS = "generation_elapsed_millis"
    const val KEY_ELAPSED_MILLIS = "elapsed_millis"
    const val KEY_SYSTEM_INFO = "system_info"
    const val KEY_ERROR = "error"
}
