package de.mrlongnight.llmreference.engine.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InferenceRequestIdsTest {
    @Test
    fun requestIdsRemainUniqueAcrossConcurrentEngineClients() {
        val clientCount = 8
        val requestsPerClient = 1_000
        val allocatedIds = ConcurrentHashMap.newKeySet<Int>()
        val executor = Executors.newFixedThreadPool(clientCount)

        try {
            val clients = (0 until clientCount).map {
                executor.submit {
                    repeat(requestsPerClient) {
                        check(allocatedIds.add(InferenceRequestIds.next())) {
                            "Duplicate request ID allocated across engine clients."
                        }
                    }
                }
            }
            clients.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(clientCount * requestsPerClient, allocatedIds.size)
    }
}
