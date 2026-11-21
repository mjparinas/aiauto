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
    private val ciPollMillis: Long = 30_000,
    private val ciTimeoutMillis: Long = 45 * 60_000,
) {
    private val running = ConcurrentHashMap<String, Job>()
    private val ciWatchers = ConcurrentHashMap<String, Job>()
    private val seenEvents = ConcurrentHashMap<String, MutableSet<String>>()

    fun isRunning(taskId: String) = running[taskId]?.isActive == true

    /** True while Claude is working or the app is waiting on CI for this task. */
    fun isBusy(taskId: String) = isRunning(taskId) || ciWatchers[taskId]?.isActive == true

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

    /**
     * Adds a follow-up prompt to an existing task. Short commands like "status", "stop", "merge it"
     * and yes or no answers to the app's own questions are handled here without a Claude turn.
     */
    fun prompt(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
        append(taskId, Speaker.You, text)
        val command = VoiceCommand.parse(text)
        val pending = task.pending
        if (pending != null) update(taskId) { it.copy(pending = null) }
        when {
            command == VoiceCommand.Yes && pending == PendingAction.Merge -> return background(taskId, "merge") { merge(taskId) }
            command == VoiceCommand.Yes && pending == PendingAction.FixBuild -> return fixBuild(taskId)
            command == VoiceCommand.FixBuild && task.ciFailure != null -> return fixBuild(taskId)
            command == VoiceCommand.No && pending != null -> return say(taskId, "Okay, leaving it as is")
            command == VoiceCommand.Stop -> return stopTurn(taskId)
            command == VoiceCommand.Status -> return say(taskId, statusLine(task))
            command == VoiceCommand.Merge -> return background(taskId, "check the pull request") { askToMerge(taskId) }
        }
        sendToClaude(taskId, text)
    }

    private fun sendToClaude(taskId: String, text: String) {
        val task = store.get(taskId) ?: return
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

    private fun stopTurn(taskId: String) {
        val task = store.get(taskId) ?: return
        if (!isRunning(taskId)) return say(taskId, "Claude isn't working on this right now")
        stop(taskId)
        update(taskId) { it.copy(status = TaskStatus.NeedsInput) }
        say(taskId, "Stopped Claude. Reply to give it new directions")
        val sessionId = task.sessionId ?: return
        val key = config.anthropicKey() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { claudeFactory(key).use { it.interrupt(sessionId) } }
        }
    }

    private fun statusLine(task: Task): String {
        val pr = task.prNumber?.let { "PR $it" }
        return when {
            isRunning(task.id) -> {
                val last = task.messages.lastOrNull { it.speaker != Speaker.You }?.text?.trimEnd('.')
                "Claude is working on it" + (last?.let { ". Latest: $it" } ?: "")
            }
            ciWatchers[task.id]?.isActive == true -> "Waiting for checks to finish on $pr"
            task.status == TaskStatus.Failed -> "This task failed: " + (task.messages.lastOrNull { it.speaker == Speaker.Status }?.text ?: "no details")
            task.ciFailure != null -> "Checks failed on $pr. Say fix it to have Claude look into it"
            pr != null -> "$pr is open for review"
            task.status == TaskStatus.NeedsInput -> "Claude is waiting for your answer"
            else -> "Claude finished, and there's no pull request"
        }
    }

    /** Checks the pull request and CI, then asks the driver to confirm the merge. */
    private fun askToMerge(taskId: String) {
        val task = store.get(taskId) ?: return
        val number = task.prNumber ?: return say(taskId, "There's no pull request to merge yet")
        if (isRunning(taskId)) return say(taskId, "Claude is still working. Say merge once it's done")
        val github = githubFactory(requireNotNull(config.githubToken()) { "Add a GitHub token in Settings." })
        val pr = github.pull(task.repo, number)
        when {
            pr.merged -> return say(taskId, "PR $number is already merged")
            pr.state != "open" -> return say(taskId, "PR $number is closed")
            pr.mergeable == false -> return say(taskId, "PR $number has conflicts with ${task.repo.defaultBranch}, so it can't be merged as is")
        }
        val ci = pr.head?.sha?.let { github.ci(task.repo, it) } ?: CiResult(CiState.None)
        when (ci.state) {
            CiState.Pending -> say(taskId, "Checks are still running on PR $number. Ask again when they're done")
            CiState.Failed -> {
                update(taskId) { it.copy(pending = PendingAction.FixBuild, ciFailure = ci.details, status = TaskStatus.NeedsInput) }
                say(taskId, "${ci.summary}, so I won't merge PR $number. Say yes to have Claude fix it")
            }
            CiState.Passed, CiState.None -> {
                update(taskId) { it.copy(pending = PendingAction.Merge, status = TaskStatus.NeedsInput) }
                val checks = if (ci.state == CiState.Passed) "${ci.summary}. " else "No checks ran. "
                say(taskId, checks + "Say yes to merge PR $number into ${task.repo.defaultBranch}")
            }
        }
    }

    private fun merge(taskId: String) {
        val task = store.get(taskId) ?: return
        val number = task.prNumber ?: return
        githubFactory(requireNotNull(config.githubToken()) { "Add a GitHub token in Settings." }).merge(task.repo, number)
        update(taskId) { it.copy(status = TaskStatus.Done) }
        say(taskId, "Merged PR $number into ${task.repo.defaultBranch}")
    }

    private fun fixBuild(taskId: String) {
        val failure = store.get(taskId)?.ciFailure ?: return
        update(taskId) { it.copy(ciFailure = null) }
        sendToClaude(taskId, ClaudeAgents.fixBuildPrompt(failure))
    }

    /** Runs a GitHub call off the caller's thread and reports a failure as a spoken line. */
    private fun background(taskId: String, what: String, block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { say(taskId, "Couldn't $what: ${it.message}") }
        }
    }

    private fun say(taskId: String, text: String) = append(taskId, Speaker.Status, text)

    /**
     * Polls GitHub Actions for the pull request's latest commit and announces the result once.
     * Gives up quietly when the repo has no workflows.
     */
    internal fun watchCi(taskId: String) {
        ciWatchers.remove(taskId)?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val deadline = clock() + ciTimeoutMillis
            var emptyPolls = 0
            while (isActive && clock() < deadline) {
                delay(ciPollMillis)
                val task = store.get(taskId) ?: return@launch
                val number = task.prNumber ?: return@launch
                val github = githubFactory(config.githubToken() ?: return@launch)
                val (sha, ci) = runCatching {
                    val sha = github.pull(task.repo, number).head?.sha
                    sha?.let { it to github.ci(task.repo, it) }
                }.getOrNull() ?: continue
                if (sha == task.ciSha) return@launch
                when (ci.state) {
                    CiState.None -> if (++emptyPolls >= 4) return@launch
                    CiState.Pending -> emptyPolls = 0
                    CiState.Passed -> {
                        update(taskId) { it.copy(ciSha = sha, ciFailure = null) }
                        say(taskId, "${ci.summary} on PR $number. Say merge to merge it")
                        return@launch
                    }
                    CiState.Failed -> {
                        update(taskId) { it.copy(ciSha = sha, ciFailure = ci.details, pending = PendingAction.FixBuild, status = TaskStatus.NeedsInput) }
                        say(taskId, "${ci.summary} on PR $number. Say yes to have Claude fix it")
                        return@launch
                    }
                }
            }
        }
        ciWatchers[taskId] = job
        job.invokeOnCompletion { ciWatchers.remove(taskId, job) }
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
        if (store.get(taskId)?.prNumber != null) watchCi(taskId)
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
