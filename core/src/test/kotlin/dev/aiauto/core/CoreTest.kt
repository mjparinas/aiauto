package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun picksTheNextPatchVersion() {
        assertEquals("v0.4.5", GitHub.nextVersion(listOf("v0.4.4", "v0.10.0-beta", "v0.3.9", "latest")))
        assertEquals("v0.10.1", GitHub.nextVersion(listOf("v0.9.9", "v0.10.0")))
        assertEquals("1.2.4", GitHub.nextVersion(listOf("1.2.3")))
        assertEquals("v0.1.0", GitHub.nextVersion(emptyList()))
    }

    @Test fun releasesMergedPullRequestsWhenAutoReleaseIsOn() {
        val posted = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                request.method == "POST" && path.endsWith("/releases") -> {
                    val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
                    posted += buffer.readUtf8()
                    """{"tag_name":"v0.4.5","html_url":"https://github.com/o/r/releases/v0.4.5"}"""
                }
                path.endsWith("/pulls/7") -> """{"number":7,"html_url":"u","state":"closed","merged":true,"merged_at":"2026-01-02T00:00:00Z","merge_commit_sha":"abc"}"""
                path.endsWith("/pulls/8") -> """{"number":8,"html_url":"u","state":"open"}"""
                path.endsWith("/tags") -> """[{"name":"v0.4.4"},{"name":"v0.4.3"}]"""
                else -> error("unexpected $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (request.method == "POST") 201 else 200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tasks = mutableMapOf<String, Task>()
        val store = object : TaskStore {
            override fun get(id: String) = tasks[id]
            override fun all() = tasks.values.toList()
            override fun put(task: Task) { tasks[task.id] = task }
        }
        var since: Long? = null
        val config = object : EngineConfig {
            override fun anthropicKey() = "k"
            override fun githubToken() = "t"
            override fun agentSetup() = null
            override fun saveAgentSetup(setup: AgentSetup) {}
            override fun autoReleaseSince() = since
        }
        val repo = RepoRef("o", "r")
        store.put(Task("a", repo, "A", "claude/a", prNumber = 7, createdMillis = 0, updatedMillis = 0))
        store.put(Task("b", repo, "B", "claude/b", prNumber = 8, createdMillis = 0, updatedMillis = 0))
        val engine = TaskEngine(store, config, CoroutineScope(Dispatchers.Unconfined), githubFactory = { GitHub(it, http) })

        engine.checkMerges()
        assertTrue(posted.isEmpty(), "nothing happens while the toggle is off")

        since = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        engine.checkMerges()
        engine.checkMerges()
        assertEquals(1, posted.size)
        assertTrue(posted[0].contains("\"tag_name\":\"v0.4.5\"") && posted[0].contains("\"target_commitish\":\"abc\""))
        assertEquals("v0.4.5", tasks["a"]!!.releaseTag)
        assertEquals("Pull request 7 was merged. Released v0.4.5", tasks["a"]!!.messages.last().text)
        assertNull(tasks["b"]!!.releaseTag)
    }

    @Test fun skipsPullRequestsMergedBeforeAutoReleaseWasOn() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"number":7,"html_url":"u","state":"closed","merged":true,"merged_at":"2025-12-01T00:00:00Z"}""".toResponseBody()).build()
        }.build()
        val tasks = mutableMapOf<String, Task>()
        val store = object : TaskStore {
            override fun get(id: String) = tasks[id]
            override fun all() = tasks.values.toList()
            override fun put(task: Task) { tasks[task.id] = task }
        }
        val config = object : EngineConfig {
            override fun anthropicKey() = "k"
            override fun githubToken() = "t"
            override fun agentSetup() = null
            override fun saveAgentSetup(setup: AgentSetup) {}
            override fun autoReleaseSince() = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        }
        store.put(Task("a", RepoRef("o", "r"), "A", "claude/a", prNumber = 7, createdMillis = 0, updatedMillis = 0))
        TaskEngine(store, config, CoroutineScope(Dispatchers.Unconfined), githubFactory = { GitHub(it, http) }).checkMerges()
        assertTrue(tasks["a"]!!.prMerged)
        assertNull(tasks["a"]!!.releaseTag)
    }

    @Test fun makesTitlesAndBranches() {
        assertEquals("Add a dark mode toggle to settings", TaskEngine.titleFrom("add a dark mode toggle to settings"))
        assertEquals("add-a-dark-mode-toggle", TaskEngine.slug("Add a dark-mode toggle, to settings!"))
        assertEquals("task", TaskEngine.slug("!!!"))
    }
}
