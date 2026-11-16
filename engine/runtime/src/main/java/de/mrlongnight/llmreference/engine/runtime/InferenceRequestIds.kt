package de.mrlongnight.llmreference.engine.runtime

import java.util.concurrent.atomic.AtomicInteger

/**
 * Request IDs are shared by every engine client in this app process. The service receives
 * requests from multiple Messenger clients on one queue, so per-instance counters can collide.
 */
internal object InferenceRequestIds {
    private val nextId = AtomicInteger(1)

    fun next(): Int = nextId.getAndUpdate { current ->
        if (current >= Int.MAX_VALUE - 1) 1 else current + 1
    }
}
