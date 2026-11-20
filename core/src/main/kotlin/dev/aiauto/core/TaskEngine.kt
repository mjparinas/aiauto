package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 */
class TaskEngine(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val claudeFactory: (String) -> ClaudeAgents = ::ClaudeAgents,
    private val githubFactory: (String) -> GitHub = { GitHub(it) },
    /** Called for each new line, so the app can notify and read it aloud. */
    private val onMessage: (Task, TaskMessage) -> Unit = { _, _ -> },
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
        launchTurn(id) { claude, current ->
            val setup = config.agentSetup() ?: claude.createSetup().also(config::saveAgentSetup)
            val sessionId = claude.createSession(setup, current, requireNotNull(config.githubToken()) { "Add a GitHub token in Settings." })
            update(id) { it.copy(sessionId = sessionId) }
            append(id, Speaker.Status, "Claude is starting on ${repo.name}")
            claude.sendPrompt(sessionId, ClaudeAgents.firstPrompt(current, request))
        }
        return store.get(id)!!
    }

    /** Adds a follow-up prompt to an existing task. */
    fun prompt(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        append(taskId, Speaker.You, text)
        val sessionId = task.sessionId
        if (sessionId == null) {
            fail(taskId, "This task never got a session. Start a new task.")
            return
        }
        launchTurn(taskId) { claude, _ -> claude.sendPrompt(sessionId, text) }
    }

    /** Re-attaches to tasks that were mid-turn when the app was closed. */
    fun resumeAll() {
        store.all()
            .filter { it.sessionId != null && (it.status == TaskStatus.Working || it.status == TaskStatus.Starting) }
            .forEach { task -> launchTurn(task.id) { _, _ -> } }
    }

    fun stop(taskId: String) {
        running.remove(taskId)?.cancel()
    }

    private fun launchTurn(taskId: String, begin: (ClaudeAgents, Task) -> Unit) {
        running.remove(taskId)?.cancel()
        update(taskId) { it.copy(status = TaskStatus.Working) }
        running[taskId] = scope.launch(Dispatchers.IO) {
            val key = config.anthropicKey()
            if (key == null) {
                fail(taskId, "Add your Claude API key in Settings.")
                return@launch
            }
            val claude = claudeFactory(key)
            try {
                begin(claude, store.get(taskId)!!)
                followUntilDone(claude, taskId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(taskId, e.message ?: e.javaClass.simpleName)
            } finally {
                claude.close()
                running.remove(taskId)
            }
        }
    }

    private suspend fun followUntilDone(claude: ClaudeAgents, taskId: String) {
        val sessionId = store.get(taskId)?.sessionId ?: return
        val seen = seenEvents.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }
        var attempts = 0
        var finished = false
        while (!finished && scope.isActive) {
            try {
                runInterruptible {
                    claude.follow(sessionId, seen) { update ->
                        if (handle(taskId, update)) finished = true
                    }
                }
                finished = true
            } catch (e: java.io.IOException) {
                // Phones drop connections in tunnels and parking garages. Reconnect with backoff.
                if (++attempts > 6) throw e
                delay(2_000L * attempts)
            }
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
    private fun afterTurn(taskId: String) {
        val task = store.get(taskId) ?: return
        val token = config.githubToken()
        if (token != null) {
            runCatching {
                val github = githubFactory(token)
                if (task.prNumber == null && github.branchExists(task.repo, task.branch)) {
                    val pr = github.findPull(task.repo, task.branch) ?: github.openPull(
                        task.repo, task.branch, task.title, prBody(task),
                    )
                    update(taskId) { it.copy(prUrl = pr.htmlUrl, prNumber = pr.number) }
                    append(taskId, Speaker.Status, "Pull request ${pr.number} is open for review")
                }
            }.onFailure { append(taskId, Speaker.Status, "Couldn't open the pull request: ${it.message}") }
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
        update(taskId) { it.copy(status = TaskStatus.Failed) }
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
    }

    private fun append(taskId: String, speaker: Speaker, text: String, notify: Boolean = true, idPrefix: String = "m-") {
        val message = TaskMessage(idPrefix + UUID.randomUUID().toString().take(8), speaker, text, clock())
        update(taskId) { it.copy(messages = it.messages + message) }
        if (notify && speaker != Speaker.You) store.get(taskId)?.let { onMessage(it, message) }
    }

    @Synchronized
    private fun update(taskId: String, transform: (Task) -> Task) {
        val task = store.get(taskId) ?: return
        store.put(transform(task).copy(updatedMillis = clock()))
    }

    companion object {
        private const val PROGRESS = "p-"

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
