package de.mrlongnight.llmreference.engine.runtime

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModelSessionOwnerTest {
    @Test
    fun onlyTheClientThatLoadedTheModelCanUseOrUnloadIt() {
        val owner = ModelSessionOwner()
        owner.claim("client-a")

        assertDoesNotThrow { owner.requireOwner("client-a") }
        assertThrows(IllegalStateException::class.java) {
            owner.requireOwner("client-b")
        }
        assertThrows(IllegalStateException::class.java) {
            owner.release("client-b")
        }

        owner.release("client-a")
        assertThrows(IllegalStateException::class.java) {
            owner.requireOwner("client-a")
        }
    }

    @Test
    fun startingReplacementLoadInvalidatesPreviousLeaseUntilLoadSucceeds() {
        val owner = ModelSessionOwner()
        owner.claim("client-a")

        owner.resetForLoad()

        assertThrows(IllegalStateException::class.java) {
            owner.requireOwner("client-a")
        }
        assertThrows(IllegalStateException::class.java) {
            owner.requireOwner("client-b")
        }

        owner.claim("client-b")
        assertDoesNotThrow { owner.requireOwner("client-b") }
    }

    @Test
    fun blankClientIdsCannotClaimTheNativeModelSlot() {
        val owner = ModelSessionOwner()
        assertThrows(IllegalArgumentException::class.java) {
            owner.claim(" ")
        }
        assertThrows(IllegalStateException::class.java) {
            owner.requireOwner("")
        }
    }
}
