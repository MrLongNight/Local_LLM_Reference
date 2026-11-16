package de.mrlongnight.llmreference.engine.runtime

/**
 * Tracks the single native model slot owned by the client that loaded it.
 *
 * All calls originate from the inference service's serialized worker. Synchronization also keeps
 * the helper safe if its use is expanded later or tests invoke it concurrently.
 */
internal class ModelSessionOwner {
    private var ownerClientId: String? = null

    @Synchronized
    fun resetForLoad() {
        ownerClientId = null
    }

    @Synchronized
    fun claim(clientId: String) {
        require(clientId.isNotBlank()) { "Engine client identity must not be empty." }
        ownerClientId = clientId
    }

    @Synchronized
    fun requireOwner(clientId: String) {
        check(clientId.isNotBlank() && ownerClientId == clientId) {
            if (ownerClientId == null) {
                "No model is loaded for this engine client. Load a model first."
            } else {
                "The inference model slot belongs to another engine client. Load the model again."
            }
        }
    }

    @Synchronized
    fun release(clientId: String) {
        requireOwner(clientId)
        ownerClientId = null
    }
}
