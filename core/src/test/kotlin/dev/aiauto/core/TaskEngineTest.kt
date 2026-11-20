package dev.aiauto.core

import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskEngineTest {
    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config(var setup: AgentSetup? = AgentSetup("agent", 1, "env")) : EngineConfig {
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = setup
        override fun saveAgentSetup(setup: AgentSetup) { this.setup = setup }
    }

    /** Scripted Claude: each list entry is what one call does, then it behaves normally. */
    private class FakeClaude : AgentClient {
        val sessionFailures = ArrayDeque<Exception>()
        val sendFailures = ArrayDeque<Exception>()
        /** Each follow() call takes the next step: an exception to throw, or updates to emit. */
        val followSteps = ArrayDeque<Any>()
        val sent = mutableListOf<String>()
        var sessionsCreated = 0
        var setupsCreated = 0

        override fun createSetup(): AgentSetup { setupsCreated++; return AgentSetup("agent2", 1, "env2") }
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String): String {
            sessionFailures.removeFirstOrNull()?.let { throw it }
            sessionsCreated++
            return "session-${setup.agentId}"
        }
        override fun sendPrompt(sessionId: String, prompt: String) {
            sendFailures.removeFirstOrNull()?.let { throw it }
            sent += prompt
        }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            when (val step = followSteps.removeFirstOrNull()) {
                is Exception -> throw step
                is List<*> -> { onConnected(); step.forEach { onUpdate(it as AgentUpdate) } }
                else -> { onConnected(); onUpdate(AgentUpdate.Said("All done.")); onUpdate(AgentUpdate.TurnDone) }
            }
            return true
        }
        override fun close() {}
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private var spoken = mutableListOf<String>()
    private fun engine(onMessage: (Task, TaskMessage) -> Unit = { _, m -> spoken += m.text }) = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        claudeFactory = { claude },
        githubFactory = { throw GitHubException(404, "no branch") },
        onMessage = onMessage,
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
    )

    private fun dropped() = AnthropicIoException("connection reset")

    @Test fun reconnectsAfterTheStreamDrops() {
        claude.followSteps.addAll(listOf(dropped(), dropped()))
        val task = engine().start(repo, "add a test")
        val done = store.get(task.id)!!
        assertEquals(TaskStatus.Done, done.status)
        assertEquals(1, claude.sessionsCreated)
        assertEquals(1, claude.sent.size)
        assertTrue(done.messages.any { it.text == "All done." })
    }

    @Test fun recreatesAMissingAgentSetupOnce() {
        claude.sessionFailures += NotFoundException.builder().headers(Headers.builder().build()).body(JsonValue.from(mapOf<String, Any>())).build()
        val task = engine().start(repo, "fix the build")
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
        assertEquals(1, claude.setupsCreated)
        assertEquals("agent2", config.setup?.agentId)
    }

    @Test fun keepsAnUnsentPromptAndResendsItWhenTheNetworkReturns() {
        repeat(3) { claude.sendFailures += dropped() }
        val engine = engine()
        val task = engine.start(repo, "rename the screen")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertTrue(stopped.interrupted)
        assertTrue(stopped.pendingPrompt!!.contains("rename the screen"))
        assertTrue(spoken.last().contains("reconnect"))

        engine.onNetworkAvailable()
        val resumed = store.get(task.id)!!
        assertEquals(TaskStatus.Done, resumed.status)
        assertFalse(resumed.interrupted)
        assertNull(resumed.pendingPrompt)
        assertEquals(1, claude.sessionsCreated)
        assertEquals(1, claude.sent.size)
    }

    @Test fun givesUpOnAPersistentlyDeadStreamButStaysResumable() {
        repeat(20) { claude.followSteps += dropped() }
        val task = engine().start(repo, "update docs")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertTrue(stopped.interrupted)
        assertNull(stopped.pendingPrompt)
    }

    @Test fun badKeyFailsWithAClearMessage() {
        claude.sessionFailures += UnauthorizedException.builder().headers(Headers.builder().build()).body(JsonValue.from(mapOf<String, Any>())).build()
        val task = engine().start(repo, "anything")
        val failed = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, failed.status)
        assertFalse(failed.interrupted)
        assertEquals("Claude rejected your API key. Check it in Settings.", failed.lastMessage?.text)
    }

    @Test fun followUpQueuedWhileOfflineIsSentAfterRetry() {
        val engine = engine()
        val task = engine.start(repo, "first")
        claude.sendFailures.addAll(listOf(dropped(), dropped(), dropped()))
        engine.prompt(task.id, "second")
        assertEquals("second", store.get(task.id)!!.pendingPrompt)
        engine.retry(task.id)
        assertEquals(listOf("second"), claude.sent.drop(1))
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
    }

    @Test fun aNotificationCrashDoesNotStopTheTask() {
        val task = engine(onMessage = { _, _ -> error("notification channel missing") }).start(repo, "go")
        assertEquals(TaskStatus.Done, store.get(task.id)!!.status)
    }

    @Test fun resumesTasksThatWereMidTurnWhenTheAppClosed() {
        val now = 0L
        store.put(Task("t1", repo, "x", "b", sessionId = "s", status = TaskStatus.Working, createdMillis = now, updatedMillis = now))
        store.put(Task("t2", repo, "y", "b", sessionId = null, status = TaskStatus.Starting, createdMillis = now, updatedMillis = now,
            messages = listOf(TaskMessage("m", Speaker.You, "do y", now))))
        engine().resumeAll()
        assertEquals(TaskStatus.Done, store.get("t1")!!.status)
        assertEquals(TaskStatus.Done, store.get("t2")!!.status)
        assertEquals(1, claude.sessionsCreated)
    }

    @Test fun classifiesErrors() {
        assertTrue(Failures.isTransient(dropped()))
        assertTrue(Failures.isTransient(java.net.SocketTimeoutException()))
        assertTrue(Failures.isTransient(GitHubException(502, "bad gateway")))
        assertFalse(Failures.isTransient(GitHubException(401, "bad creds")))
        assertFalse(Failures.isTransient(IllegalStateException("Add a GitHub token in Settings.")))
        assertEquals("GitHub rejected your token. Update it in Settings.", Failures.describe(GitHubException(401, "x")))
        assertEquals("No internet connection.", Failures.describe(java.net.UnknownHostException("api.anthropic.com")))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(Failures::backoffMillis))
    }
}
