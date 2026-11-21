package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplatesTest {
    private val all = TaskTemplate.defaults

    @Test fun matchesSpokenNames() {
        assertEquals("fix-lint", TaskTemplate.matchSpoken("fix lint", all)?.id)
        assertEquals("bump-deps", TaskTemplate.matchSpoken("Bump dependencies.", all)?.id)
        assertEquals("bump-deps", TaskTemplate.matchSpoken("run bump dependencies", all)?.id)
    }

    @Test fun unknownOrBlankMatchesNothing() {
        assertNull(TaskTemplate.matchSpoken("deploy", all))
        assertNull(TaskTemplate.matchSpoken("  ", all))
    }
}
