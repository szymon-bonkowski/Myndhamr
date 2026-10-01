package io.github.szymonbonkowski.myndhamr.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationVersionTest {
    @Test
    fun formatVersionIsOne() {
        assertEquals(1, FoundationVersion.FORMAT_VERSION)
    }

    @Test
    fun milestoneIsV0Point0() {
        assertEquals("v0.0", FoundationVersion.MILESTONE)
    }
}
