package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceFeaturesTest {
    @Test fun parsesCommands() {
        assertEquals(VoiceCommand.Merge, VoiceCommand.parse("Merge it."))
        assertEquals(VoiceCommand.Merge, VoiceCommand.parse("okay merge the PR please"))
        assertEquals(VoiceCommand.Status, VoiceCommand.parse("What's the status?"))
        assertEquals(VoiceCommand.Stop, VoiceCommand.parse("stop"))
        assertEquals(VoiceCommand.FixBuild, VoiceCommand.parse("fix it"))
        assertEquals(VoiceCommand.Yes, VoiceCommand.parse("Yes, do it"))
        assertEquals(VoiceCommand.No, VoiceCommand.parse("no"))
        assertNull(VoiceCommand.parse("merge the two settings screens into one"))
        assertNull(VoiceCommand.parse("add a status bar to the home screen"))
    }

    /** Answers GitHub API calls from a map of "METHOD path" to JSON, and records each call. */
    private class FakeGitHub {
        val routes = ConcurrentHashMap<String, String>()
        val calls = CopyOnWriteArrayList<String>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val key = req.method + " " + req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
            calls += key
            val body = routes[key]
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message("")
                .body((body ?: """{"message":"Not Found"}""").toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private val repo = RepoRef("me", "app")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeGitHub()
    private val store = object : TaskStore {
        val map = ConcurrentHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
    }
    private val engine = TaskEngine(
        store = store,
        config = object : EngineConfig {
            override fun anthropicKey() = "key"
            override fun githubToken() = "token"
            override fun agentSetup() = null
            override fun saveAgentSetup(setup: AgentSetup) {}
        },
        scope = scope,
        githubFactory = { GitHub(it, fake.client) },
        ciPollMillis = 10,
    )

    @AfterTest fun tearDown() = scope.cancel()

    private fun task() = Task("t1", repo, "Add a toggle", "claude/x", sessionId = "s1", status = TaskStatus.Done, prNumber = 7, createdMillis = 0, updatedMillis = 0)
        .also(store::put)

    private fun lastLine() = store.get("t1")!!.messages.lastOrNull()?.text

    private fun waitFor(what: String, check: () -> Boolean) {
        repeat(300) { if (check()) return; Thread.sleep(10) }
        throw AssertionError("Timed out waiting for $what. Last line: ${lastLine()}. Calls: $fake.calls")
    }

    private fun pr(sha: String = "abc", mergeable: Boolean = true) =
        """{"number":7,"html_url":"u","state":"open","mergeable":$mergeable,"head":{"sha":"$sha"}}"""

    private fun runs(vararg r: String) = """{"workflow_runs":[${r.joinToString(",")}]}"""

    @Test fun mergesAfterConfirmation() {
        task()
        fake.routes["GET /repos/me/app/pulls/7"] = pr()
        fake.routes["GET /repos/me/app/actions/runs?head_sha=abc&per_page=50"] =
            runs("""{"id":1,"name":"Build","status":"completed","conclusion":"success"}""")
        fake.routes["PUT /repos/me/app/pulls/7/merge"] = """{"merged":true}"""

        engine.prompt("t1", "merge it")
        waitFor("confirmation") { lastLine().orEmpty().contains("Say yes") }
        assertEquals("The Build check passed. Say yes to merge PR 7 into main", lastLine())
        assertEquals(PendingAction.Merge, store.get("t1")!!.pending)

        engine.prompt("t1", "yes")
        waitFor("merge") { lastLine().orEmpty().startsWith("Merged") }
        assertTrue("PUT /repos/me/app/pulls/7/merge" in fake.calls)
        assertNull(store.get("t1")!!.pending)
    }

    @Test fun declinesToMergeWhenCiFails() {
        task()
        fake.routes["GET /repos/me/app/pulls/7"] = pr()
        fake.routes["GET /repos/me/app/actions/runs?head_sha=abc&per_page=50"] =
            runs("""{"id":5,"name":"Build","status":"completed","conclusion":"failure","html_url":"https://x/5"}""")
        fake.routes["GET /repos/me/app/actions/runs/5/jobs"] =
            """{"jobs":[{"name":"test","conclusion":"failure","steps":[{"name":"Checkout","conclusion":"success"},{"name":"Run tests","conclusion":"failure"}]}]}"""

        engine.prompt("t1", "merge it")
        waitFor("refusal") { lastLine().orEmpty().contains("won't merge") }
        assertEquals("The Build check failed in the Run tests step, so I won't merge PR 7. Say yes to have Claude fix it", lastLine())
        assertTrue(store.get("t1")!!.ciFailure!!.contains("Failing step: Run tests"))
        assertTrue(fake.calls.none { it.startsWith("PUT") })

        engine.prompt("t1", "no")
        assertEquals("Okay, leaving it as is", lastLine())
        assertNull(store.get("t1")!!.pending)
    }

    @Test fun refusesToMergeWithConflicts() {
        task()
        fake.routes["GET /repos/me/app/pulls/7"] = pr(mergeable = false)
        engine.prompt("t1", "merge")
        waitFor("conflict note") { lastLine().orEmpty().contains("conflicts") }
    }

    @Test fun announcesCiResultOncePerCommit() {
        task()
        fake.routes["GET /repos/me/app/pulls/7"] = pr()
        val runsPath = "GET /repos/me/app/actions/runs?head_sha=abc&per_page=50"
        fake.routes[runsPath] = runs("""{"id":1,"name":"Build","status":"in_progress"}""")
        engine.watchCi("t1")
        waitFor("a pending poll") { fake.calls.count { it == runsPath } >= 2 }
        assertTrue(engine.isBusy("t1"))

        fake.routes[runsPath] = runs(
            """{"id":1,"name":"Build","status":"completed","conclusion":"failure"}""",
            """{"id":2,"name":"Build","status":"completed","conclusion":"success"}""",
        )
        waitFor("announcement") { lastLine().orEmpty().contains("passed") }
        assertEquals("The Build check passed on PR 7. Say merge to merge it", lastLine())
        assertEquals("abc", store.get("t1")!!.ciSha)
        waitFor("watcher to stop") { !engine.isBusy("t1") }

        val count = store.get("t1")!!.messages.size
        engine.watchCi("t1")
        waitFor("watcher to stop again") { !engine.isBusy("t1") }
        assertEquals(count, store.get("t1")!!.messages.size)
    }

    @Test fun reportsStatusLocally() {
        task()
        engine.prompt("t1", "status")
        assertEquals("PR 7 is open for review", lastLine())
        assertTrue(fake.calls.isEmpty())
    }
}
