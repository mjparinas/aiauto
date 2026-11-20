package dev.aiauto.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** Where tasks live. The Android app backs this with a file; tests use a map. */
interface TaskStore {
    fun get(id: String): Task?
    fun all(): List<Task>
    fun put(task: Task)
}

/** Credentials and one-time setup the engine needs. Returning null means "not configured yet". */
interface EngineConfig {
    fun anthropicKey(): String?
    fun githubToken(): String?
    fun agentSetup(): AgentSetup?
    fun saveAgentSetup(setup: AgentSetup)
}

/**
 * Runs tasks: creates a session per task, sends prompts, follows the event stream,
 * and opens a pull request when Claude pushes its branch.
 *
 * Every step can be repeated safely, so a task that was cut off (lost signal, app killed, Claude busy)
 * picks up where it left off: a session is created only once, a prompt is sent only until it is
 * accepted, and the stream is followed again from the session's history.
 */
class TaskEngine(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val claudeFactory: (String) -> AgentClient = ::ClaudeAgents,
    private val githubFactory: (String) -> GitHub = { GitHub(it) },
    /** Called for each new line, so the app can notify and read it aloud. */
    private val onMessage: (Task, TaskMessage) -> Unit = { _, _ -> },
    /** Called for each new progress note, so the app can read it aloud without posting a notification. */
    private val onProgress: (Task, String) -> Unit = { _, _ -> },
    /** Waits between retries. Tests pass a no-op. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val running = ConcurrentHashMap<String, Job>()
    private val seenEvents = ConcurrentHashMap<String, MutableSet<String>>()

    fun isRunning(taskId: String) = running[taskId]?.isActive == true

    /** Starts a new task on [repo] from a spoken or typed [request]. Returns the new task. */
    fun start(repo: RepoRef, request: String): Task {
        val now = clock()
        val id = UUID.randomUUID().toString().take(8)
        val task = Task(
            id = id,
            repo = repo,
            title = titleFrom(request),
            branch = "claude/${slug(request)}-$id",
            createdMillis = now,
            updatedMillis = now,
        )
        store.put(task)
        append(id, Speaker.You, request)
        launchTurn(id)
        return store.get(id) ?: task
    }

    /** Adds a follow-up prompt to an existing task. */
    fun prompt(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        append(taskId, Speaker.You, text)
        // Without a session the prompt joins the first message; otherwise it queues behind any unsent one.
        if (task.sessionId != null) {
            update(taskId) { it.copy(pendingPrompt = listOfNotNull(it.pendingPrompt, text).joinToString("\n\n")) }
        }
        launchTurn(taskId)
    }

    /** Picks a stopped task back up: creates its session if it never got one, resends unsent prompts, and reattaches. */
    fun retry(taskId: String) {
        if (isRunning(taskId) || store.get(taskId) == null) return
        progress(taskId, "Trying again")
        launchTurn(taskId)
    }

    /** Re-attaches to tasks that were mid-turn when the app was closed, and to tasks cut off by a lost connection. */
    fun resumeAll() {
        store.all()
            .filter { it.status == TaskStatus.Working || it.status == TaskStatus.Starting || it.interrupted }
            .forEach { retry(it.id) }
    }

    /** Call when the phone gets a working network again. Resumes tasks that stopped because it was lost. */
    fun onNetworkAvailable() {
        store.all().filter { it.status == TaskStatus.Failed && it.interrupted }.forEach { retry(it.id) }
    }

    fun stop(taskId: String) {
        running.remove(taskId)?.cancel()
    }

    private fun launchTurn(taskId: String) {
        running.remove(taskId)?.cancel()
        update(taskId) { it.copy(status = TaskStatus.Working, interrupted = false) }
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val key = config.anthropicKey()
            if (key == null) {
                fail(taskId, "Add your Claude API key in Settings.")
                return@launch
            }
            var claude: AgentClient? = null
            try {
                val client = claudeFactory(key).also { claude = it }
                withRetries { prepare(client, taskId) }
                followUntilDone(client, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Failures.isTransient(e)) interrupt(taskId, e) else fail(taskId, Failures.describe(e))
            } finally {
                runCatching { claude?.close() }
                running.remove(taskId, currentCoroutineContext()[Job])
            }
        }
        running[taskId] = job
        job.start()
    }

    /** Makes sure the task has a session and that Claude has every prompt. Safe to repeat. */
    private fun prepare(claude: AgentClient, taskId: String) {
        val task = store.get(taskId) ?: return
        if (task.sessionId == null) {
            val token = config.githubToken() ?: throw IllegalStateException("Add a GitHub token in Settings.")
            val sessionId = try {
                claude.createSession(agentSetup(claude), task, token)
            } catch (e: Exception) {
                // The saved agent or environment is gone (deleted, or the key now points at another workspace).
                if (!Failures.isMissing(e)) throw e
                claude.createSession(claude.createSetup().also(config::saveAgentSetup), task, token)
            }
            val request = task.messages.filter { it.speaker == Speaker.You }.joinToString("\n") { it.text }
            update(taskId) { it.copy(sessionId = sessionId, pendingPrompt = ClaudeAgents.firstPrompt(it, request)) }
            append(taskId, Speaker.Status, "Claude is starting on ${task.repo.name}")
        }
        val current = store.get(taskId) ?: return
        val prompt = current.pendingPrompt ?: return
        claude.sendPrompt(current.sessionId!!, prompt)
        update(taskId) { if (it.pendingPrompt == prompt) it.copy(pendingPrompt = null) else it }
    }

    private fun agentSetup(claude: AgentClient): AgentSetup =
        config.agentSetup() ?: claude.createSetup().also(config::saveAgentSetup)

    /** Runs [block], retrying short outages with backoff before giving up. */
    private suspend fun withRetries(block: () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                return runInterruptible { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Failures.isTransient(e) || ++attempt >= SEND_ATTEMPTS) throw e
                sleep(Failures.backoffMillis(attempt))
            }
        }
    }

    private suspend fun followUntilDone(claude: AgentClient, taskId: String) {
        val sessionId = store.get(taskId)?.sessionId ?: return
        val seen = seenEvents.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }
        var failures = 0
        var quiet = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var heard = false
            val over = try {
                runInterruptible {
                    claude.follow(sessionId, seen, onConnected = { failures = 0 }) { update ->
                        heard = true
                        handle(taskId, update)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Phones drop connections in tunnels and parking garages. Reconnect with backoff;
                // the session keeps running on Claude's side and its history fills the gap.
                if (!Failures.isTransient(e) || ++failures > MAX_RECONNECTS) throw e
                if (failures == 2) progress(taskId, "Connection dropped. Reconnecting")
                sleep(Failures.backoffMillis(failures))
                continue
            }
            if (over) break
            // The server closed the stream mid-turn. Reconnect, but don't spin forever on a silent session.
            quiet = if (heard) 0 else quiet + 1
            if (quiet > MAX_QUIET_RECONNECTS) throw IllegalStateException("Claude stopped sending updates. Tap Try again to check on it.")
            sleep(QUIET_RECONNECT_MILLIS)
        }
        if (store.get(taskId)?.status == TaskStatus.Working) afterTurn(taskId)
    }

    /** Returns true when the turn is over. */
    private fun handle(taskId: String, update: AgentUpdate): Boolean = when (update) {
        is AgentUpdate.Progress -> { progress(taskId, update.text); false }
        is AgentUpdate.Said -> { append(taskId, Speaker.Claude, update.text); false }
        is AgentUpdate.Declined -> { append(taskId, Speaker.Status, "Skipped a risky ${update.toolName} step that needs review"); false }
        is AgentUpdate.TurnDone -> true
        is AgentUpdate.Failed -> { fail(taskId, update.reason); true }
    }

    /** After a turn: open or refresh the pull request, then mark the task done or waiting. */
    private suspend fun afterTurn(taskId: String) {
        val task = store.get(taskId) ?: return
        val token = config.githubToken()
        if (token != null && task.prNumber == null) {
            try {
                withRetries {
                    val github = githubFactory(token)
                    if (github.branchExists(task.repo, task.branch)) {
                        val pr = github.findPull(task.repo, task.branch) ?: github.openPull(
                            task.repo, task.branch, task.title, prBody(task),
                        )
                        update(taskId) { it.copy(prUrl = pr.htmlUrl, prNumber = pr.number) }
                        append(taskId, Speaker.Status, "Pull request ${pr.number} is open for review")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(taskId, Speaker.Status, "Couldn't open the pull request. ${Failures.describe(e)}")
            }
        }
        val asked = store.get(taskId)?.messages?.lastOrNull { it.speaker == Speaker.Claude }?.text?.trimEnd()?.endsWith("?") == true
        update(taskId) { it.copy(status = if (asked) TaskStatus.NeedsInput else TaskStatus.Done) }
    }

    private fun prBody(task: Task): String = buildString {
        appendLine("Requested by voice from AIAuto.")
        appendLine()
        task.messages.filter { it.speaker == Speaker.You }.forEach { appendLine("> ${it.text}") }
        task.messages.lastOrNull { it.speaker == Speaker.Claude }?.let {
            appendLine()
            appendLine(it.text)
        }
    }

    private fun fail(taskId: String, reason: String) {
        append(taskId, Speaker.Status, reason)
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = false) }
    }

    /** Stopped by a connection problem or a busy server. Claude may still be working, so this is resumable. */
    private fun interrupt(taskId: String, e: Exception) {
        append(taskId, Speaker.Status, "${Failures.describe(e)} Claude may still be working. I'll reconnect when the connection is back, or tap Try again.")
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = true) }
    }

    /** Progress notes replace the previous one so the conversation doesn't fill with "Editing files". */
    private fun progress(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        val last = task.messages.lastOrNull()
        if (last?.speaker == Speaker.Status && last.text == text) return
        if (last?.speaker == Speaker.Status && last.id.startsWith(PROGRESS)) {
            update(taskId) { it.copy(messages = it.messages.dropLast(1)) }
        }
        append(taskId, Speaker.Status, text, notify = false, idPrefix = PROGRESS)
        store.get(taskId)?.let { runCatching { onProgress(it, text) } }
    }

    private fun append(taskId: String, speaker: Speaker, text: String, notify: Boolean = true, idPrefix: String = "m-") {
        val message = TaskMessage(idPrefix + UUID.randomUUID().toString().take(8), speaker, text, clock())
        update(taskId) { it.copy(messages = it.messages + message) }
        // A notification or speech problem must never stop the task itself.
        if (notify && speaker != Speaker.You) store.get(taskId)?.let { runCatching { onMessage(it, message) } }
    }

    @Synchronized
    private fun update(taskId: String, transform: (Task) -> Task) {
        val task = store.get(taskId) ?: return
        store.put(transform(task).copy(updatedMillis = clock()))
    }

    companion object {
        private const val PROGRESS = "p-"
        private const val SEND_ATTEMPTS = 3
        private const val MAX_RECONNECTS = 8
        private const val MAX_QUIET_RECONNECTS = 30
        private const val QUIET_RECONNECT_MILLIS = 1_000L

        fun titleFrom(request: String): String {
            val words = request.trim().split(Regex("\\s+"))
            val title = words.take(8).joinToString(" ").trimEnd('.', ',', '!', '?')
            return title.replaceFirstChar { it.uppercase() } + if (words.size > 8) "…" else ""
        }

        fun slug(request: String): String =
            request.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .split('-').filter { it.isNotEmpty() }.take(5).joinToString("-").ifEmpty { "task" }
    }
}
