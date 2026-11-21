package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncTest {
    private val repo = RepoRef("me", "app")
    private fun msg(id: String, t: Long) = TaskMessage(id, Speaker.Claude, "m$id", t)
    private fun task(id: String, updated: Long, vararg messages: TaskMessage, device: String? = "phone") =
        Task(id, repo, "t$id", "b$id", createdMillis = 0, updatedMillis = updated, messages = messages.toList(), device = device)

    @Test fun newerTaskWinsAndKeepsEveryMessage() {
        val local = SyncSnapshot(tasks = listOf(task("a", 10, msg("1", 1), msg("2", 2)).copy(status = TaskStatus.Working)))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20, msg("2", 2), msg("3", 3)).copy(status = TaskStatus.Done), task("b", 5)))
        val merged = SyncMerge.merge(local, remote)
        val a = merged.tasks.first { it.id == "a" }
        assertEquals(TaskStatus.Done, a.status)
        assertEquals(listOf("1", "2", "3"), a.messages.map { it.id })
        assertEquals(setOf("a", "b"), merged.tasks.map { it.id }.toSet())
    }

    @Test fun aTaskClearedOnEitherSideStaysCleared() {
        val local = SyncSnapshot(tasks = listOf(task("b", 5)), removedTasks = listOf("a"))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20), task("b", 5)))
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf("b"), merged.tasks.map { it.id })
        assertEquals(listOf("a"), merged.removedTasks)
        // The other way round too: the gist remembers a clear made on another device.
        assertEquals(listOf("b"), SyncMerge.merge(remote.copy(removedTasks = emptyList()), local).tasks.map { it.id })
    }

    @Test fun forgottenCarChatStaysForgotten() {
        val old = ChatExchange("hi", "hello", 10)
        val new = ChatExchange("again", "hey", 30)
        val local = SyncSnapshot(chat = emptyList(), chatForgottenMillis = 20)
        val remote = SyncSnapshot(chat = listOf(old, new))
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf(new), merged.chat)
        assertEquals(20, merged.chatForgottenMillis)
    }

    @Test fun keepsOwnUnsentPrompt() {
        val local = SyncSnapshot(tasks = listOf(task("a", 10).copy(pendingPrompt = "go")))
        val remote = SyncSnapshot(tasks = listOf(task("a", 20)))
        assertEquals("go", SyncMerge.merge(local, remote).tasks.single().pendingPrompt)
        val takenOver = SyncSnapshot(tasks = listOf(task("a", 20, device = "tablet")))
        assertNull(SyncMerge.merge(local, takenOver).tasks.single().pendingPrompt)
    }

    @Test fun reposAreAUnionAndNewerSettingsWin() {
        val local = SyncSnapshot(repos = listOf(repo), settings = mapOf("x" to "1"), settingsChangedMillis = 5)
        val remote = SyncSnapshot(repos = listOf(RepoRef("Me", "App"), RepoRef("me", "other")), settings = mapOf("x" to "2"), settingsChangedMillis = 9)
        val merged = SyncMerge.merge(local, remote)
        assertEquals(listOf("me/app", "me/other"), merged.repos.map { it.fullName })
        assertEquals("2", merged.settings["x"])
        assertEquals(9, merged.settingsChangedMillis)
    }

    @Test fun uploadTrimsMessagesAndDropsUnsentPrompts() {
        val many = (1..100).map { msg("$it", it.toLong()) }.toTypedArray()
        val up = SyncMerge.forUpload(SyncSnapshot(tasks = listOf(task("a", 1, *many).copy(pendingPrompt = "x"))))
        assertEquals(SyncMerge.MESSAGES_PER_TASK, up.tasks.single().messages.size)
        assertEquals("100", up.tasks.single().messages.last().id)
        assertNull(up.tasks.single().pendingPrompt)
    }

    private class FakeGists : GitHub("t") {
        val files = mutableMapOf<String, String>()
        var writes = 0
        override fun myGists(perPage: Int) = files.keys.map { Gist(it, files = mapOf(GistSync.FILE to GistFile())) }
        override fun gistFile(id: String, name: String) = files[id]
        override fun createGist(description: String, name: String, content: String): String { files["g1"] = content; return "g1" }
        override fun updateGist(id: String, name: String, content: String) { files[id] = content; writes++ }
    }

    @Test fun createsThenMergesAndOnlyWritesChanges() {
        val gists = FakeGists()
        val sync = GistSync(gists)
        val first = SyncSnapshot(repos = listOf(repo))
        val (id, _) = sync.sync(null, first)
        assertEquals("g1", id)
        val (_, merged) = sync.sync(null, SyncSnapshot(repos = listOf(RepoRef("me", "other"))))
        assertEquals(2, merged.repos.size)
        assertEquals(1, gists.writes)
        sync.sync(id, merged)
        assertEquals(1, gists.writes)
    }
}

class SyncFirstDeviceTest {
    @Test fun aLaterDeviceWithDefaultsTakesTheFirstDevicesSettings() {
        val files = mutableMapOf<String, String>()
        val gists = object : GitHub("t") {
            override fun myGists(perPage: Int) = files.keys.map { Gist(it, files = mapOf(GistSync.FILE to GistFile())) }
            override fun gistFile(id: String, name: String) = files[id]
            override fun createGist(description: String, name: String, content: String): String { files["g"] = content; return "g" }
            override fun updateGist(id: String, name: String, content: String) { files[id] = content }
        }
        val sync = GistSync(gists)
        sync.sync(null, SyncSnapshot(settings = mapOf("theme_mode" to "s:Dark")))
        val (_, merged) = sync.sync(null, SyncSnapshot(settings = emptyMap()))
        assertEquals("s:Dark", merged.settings["theme_mode"])
    }
}
