package io.github.szymonbonkowski.myndhamr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoundationCommandTest {
    @Test
    fun noArgumentsPrintFoundationMessage() {
        assertEquals("Myndhamr v0.0 foundation", foundationCommand(emptyList()))
    }

    @Test
    fun helpPrintsUsage() {
        assertTrue(foundationCommand(listOf("--help")).contains("myndhamr inspect"))
    }

    @Test
    fun unknownArgumentFailsClearly() {
        val error = assertFailsWith<IllegalArgumentException> {
            foundationCommand(listOf("--scan"))
        }

        assertTrue(error.message.orEmpty().contains("Invalid arguments: --scan"))
    }
}
