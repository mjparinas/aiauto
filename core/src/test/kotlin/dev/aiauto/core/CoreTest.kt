package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun parsesRepoText() {
        assertEquals(RepoRef("mjparinas", "aiauto"), RepoRef.parse("mjparinas/aiauto"))
        assertEquals(RepoRef("a", "b.c"), RepoRef.parse("https://github.com/a/b.c.git"))
        assertNull(RepoRef.parse("not a repo"))
    }

    @Test fun cleansMarkdownForSpeech() {
        val text = "## Done\n- Updated **README** with `make test`\n```kotlin\nfun x() {}\n```\nSee [PR](https://x)."
        assertEquals("Done Updated README with make test See PR.", SpokenStatus.clean(text))
    }

    @Test fun trimsLongMessagesAtASentence() {
        val first = "I updated the settings screen and added a dark mode toggle that follows the system."
        assertEquals(first, SpokenStatus.clean(first + " " + "More detail here. ".repeat(20)).substringBefore(" More"))
        assertTrue(SpokenStatus.clean("x ".repeat(300)).length <= 221)
    }

    @Test fun describesCommands() {
        assertEquals("Pushing changes to GitHub", SpokenStatus.forToolUse("bash", "git push -u origin x"))
        assertEquals("Building or testing", SpokenStatus.forToolUse("bash", "npm test"))
        assertEquals("Editing files", SpokenStatus.forToolUse("edit", null))
    }

    @Test fun makesTitlesAndBranches() {
        assertEquals("Add a dark mode toggle to settings", TaskEngine.titleFrom("add a dark mode toggle to settings"))
        assertEquals("add-a-dark-mode-toggle", TaskEngine.slug("Add a dark-mode toggle, to settings!"))
        assertEquals("task", TaskEngine.slug("!!!"))
    }

    @Test fun comparesVersions() {
        assertTrue(Updates.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Updates.isNewer("v1.0", "0.9.9"))
        assertTrue(Updates.isNewer("v0.1.10", "0.1.9"))
        assertFalse(Updates.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Updates.isNewer("v0.1.0", "0.2.0"))
    }
}
