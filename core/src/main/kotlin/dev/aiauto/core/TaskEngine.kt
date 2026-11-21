package dev.aiauto.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    fun remove(id: String)
}

/** Credentials and one-time setup the engine needs. Returning null means "not configured yet". */
interface EngineConfig {
    fun anthropicKey(): String?
    fun githubToken(): String?
    fun agentSetup(): AgentSetup?
    fun saveAgentSetup(setup: AgentSetup)

    /** Quiet mode: only announce each turn's result, failures and pull requests, not every step. */
    fun quietMode(): Boolean = false

    /** The most Claude may spend per day across all tasks, in US dollars. Null means no limit. */
    fun dailyLimitUsd(): Double? = null
    /** What has been spent today so far. */
    fun spentTodayUsd(): Double = 0.0
    fun addSpend(usd: Double) {}

    /** Merge a task's pull request once its checks pass. */
    fun autoMerge(): Boolean = false
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
        if (task.archived) update(taskId) { it.copy(archived = false) }
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

    /**
     * Retries tasks cut off by a lost connection, without waiting for the network callback, which doesn't fire
     * when the phone stayed online but the connection was flaky or a server was down. Call it regularly.
     * Each task waits [RESUME_INTERVAL_MILLIS] between tries, and gives up after [AUTO_RESUME_MILLIS].
     */
    fun resumeInterrupted() {
        val now = clock()
        store.all()
            .filter { waitingToReconnect(it, now) && now - it.updatedMillis >= RESUME_INTERVAL_MILLIS }
            .forEach { launchTurn(it.id) }
    }

    /** True while some task is cut off and still being retried, so the app should stay awake. */
    fun isReconnecting(): Boolean {
        val now = clock()
        return store.all().any { waitingToReconnect(it, now) }
    }

    private fun waitingToReconnect(task: Task, now: Long): Boolean =
        task.status == TaskStatus.Failed && task.interrupted && !isRunning(task.id) &&
            now - (task.interruptedSinceMillis ?: task.updatedMillis) < AUTO_RESUME_MILLIS

    /** Stops a task that's working: Claude is interrupted and the task waits for a new prompt. */
    fun stop(taskId: String) {
        val wasRunning = running.remove(taskId)?.also { it.cancel() } != null
        val task = store.get(taskId) ?: return
        val active = task.status == TaskStatus.Working || task.status == TaskStatus.Starting || task.interrupted
        if (!wasRunning && !active) return
        update(taskId) { it.copy(status = TaskStatus.Stopped, interrupted = false, interruptedSinceMillis = null, pendingPrompt = null) }
        append(taskId, Speaker.Status, "Stopped. Reply to give Claude a new direction.", notify = false)
        val sessionId = task.sessionId ?: return
        scope.launch(dispatcher) {
            withClaude { claude ->
                runCatching { claude.interrupt(sessionId) }
                runCatching { claude.costUsd(sessionId) }.getOrNull()?.let { recordCost(taskId, it) }
            }
        }
    }

    /** Removes a task from the list. A running one is stopped first, and its Claude session is archived. */
    fun clear(taskId: String) {
        running.remove(taskId)?.cancel()
        val task = store.get(taskId) ?: return
        store.remove(taskId)
        seenEvents.remove(taskId)
        val sessionId = task.sessionId ?: return
        val active = task.status == TaskStatus.Working || task.status == TaskStatus.Starting || task.interrupted
        scope.launch(dispatcher) {
            withClaude { claude ->
                if (active) runCatching { claude.interrupt(sessionId) }
                runCatching { claude.archive(sessionId) }
            }
        }
    }

    /** Moves a task to the archive, keeping its chat. A task that's working is stopped first. */
    fun archive(taskId: String) {
        stop(taskId)
        update(taskId) { it.copy(archived = true) }
    }

    /** Brings an archived task back to the main list. */
    fun unarchive(taskId: String) {
        update(taskId) { it.copy(archived = false) }
    }

    /** Clears every finished task (stopped, failed, or done with no open pull request) that isn't archived. Returns how many. */
    fun clearFinished(): Int {
        val finished = store.all().filter { it.isFinished && !it.archived && !isRunning(it.id) }
        finished.forEach { clear(it.id) }
        return finished.size
    }

    /** Today's spend against the daily limit, for display. */
    fun spentTodayUsd(): Double = config.spentTodayUsd()

    private inline fun withClaude(block: (AgentClient) -> Unit) {
        val key = config.anthropicKey() ?: return
        runCatching { claudeFactory(key) }.getOrNull()?.use(block)
    }

    // ---- Pull requests -------------------------------------------------------------------------

    private val mergeRefusals = ConcurrentHashMap<String, String>()

    /** True while some pull request's checks or merge are worth watching, so the app should stay awake. */
    fun isWatchingPullRequests(): Boolean {
        val now = clock()
        return store.all().any { needsWatching(it, now) }
    }

    private fun needsWatching(task: Task, now: Long): Boolean {
        if (!task.hasOpenPr || isRunning(task.id)) return false
        if (now - (task.prOpenedMillis ?: task.updatedMillis) > WATCH_MILLIS) return false
        return when (task.checks?.state) {
            null, Checks.Pending, Checks.None -> true
            Checks.Passing -> config.autoMerge()
            Checks.Failing, Checks.Unknown -> false
        }
    }

    /** Refreshes state and checks for every task with an open pull request, and merges green ones when auto-merge is on. */
    fun refreshPullRequests(): Job = scope.launch(dispatcher) {
        store.all().filter { it.hasOpenPr && !isRunning(it.id) }.forEach { task ->
            currentCoroutineContext().ensureActive()
            runCatching { refreshPullRequest(task.id) }
        }
    }

    /** Fetches one task's pull request and checks, says what changed, and merges it if it's ready. */
    fun refreshPullRequest(taskId: String) {
        val task = store.get(taskId) ?: return
        val number = task.prNumber ?: return
        val token = config.githubToken() ?: return
        val github = githubFactory(token)
        val pr = github.pull(task.repo, number)
        val state = pr.prState
        val checks = if (state == PrState.Open && pr.head.sha.isNotEmpty()) github.checks(task.repo, pr.head.sha) else task.checks
        update(taskId) { it.copy(prState = state, checks = checks, githubAutoMerge = pr.hasGitHubAutoMerge, prUrl = pr.htmlUrl) }

        val now = clock()
        val willMerge = state == PrState.Open && config.autoMerge() && !pr.draft && !pr.hasGitHubAutoMerge &&
            checks != null && readyToMerge(checks, task, now)
        val beforeState = task.prState ?: PrState.Open
        when {
            state == PrState.Merged && beforeState != PrState.Merged -> append(taskId, Speaker.Status, "Pull request $number was merged.")
            state == PrState.Closed && beforeState != PrState.Closed -> append(taskId, Speaker.Status, "Pull request $number was closed without merging.")
            state == PrState.Open && checks != null && checks.state != task.checks?.state -> when (checks.state) {
                Checks.Failing -> append(taskId, Speaker.Status, "Checks failed on pull request $number: ${checks.failing.take(3).joinToString(", ")}.")
                Checks.Passing -> append(
                    taskId, Speaker.Status,
                    "Checks passed on pull request $number." + when {
                        willMerge -> " Merging it now."
                        pr.hasGitHubAutoMerge -> " GitHub will merge it."
                        else -> ""
                    },
                )
                else -> {}
            }
        }
        if (!willMerge) return
        val refusalKey = "$taskId:${pr.head.sha}"
        when (val result = github.merge(task.repo, number)) {
            MergeResult.Merged, MergeResult.AutoMergeOn -> {
                update(taskId) { it.copy(prState = PrState.Merged) }
                append(taskId, Speaker.Status, "Merged pull request $number.")
            }
            is MergeResult.Refused -> if (mergeRefusals.put(refusalKey, result.reason) == null) {
                append(taskId, Speaker.Status, "Couldn't merge pull request $number. ${result.reason}")
            }
        }
    }

    /** Merges a task's pull request now, at the user's request. Returns a sentence saying what happened. */
    fun mergeNow(taskId: String): String {
        val task = store.get(taskId) ?: return "That task is gone."
        val number = task.prNumber ?: return "This task has no pull request."
        val token = config.githubToken() ?: return "Add a GitHub token in Settings."
        val message = Projects.mergeMessage(githubFactory(token), task.repo, number)
        append(taskId, Speaker.Status, message)
        runCatching { refreshPullRequest(taskId) }
        return message
    }

    private fun readyToMerge(checks: ChecksSummary, task: Task, now: Long): Boolean = when (checks.state) {
        Checks.Passing -> true
        // A repo without CI: give checks time to show up before treating "none" as green.
        Checks.None -> now - (task.prOpenedMillis ?: task.updatedMillis) > NO_CHECKS_GRACE_MILLIS
        else -> false
    }

    // ---- Spending ------------------------------------------------------------------------------

    private fun limitMessage(limit: Double) =
        "Stopped at today's ${"$"}${"%.2f".format(java.util.Locale.US, limit)} spending limit. Raise it in Settings to keep going."

    private fun overLimit(): Boolean {
        val limit = config.dailyLimitUsd() ?: return false
        return config.spentTodayUsd() >= limit - 0.005
    }

    /** Records a session's new running total and adds the difference to today's spend. */
    @Synchronized
    private fun recordCost(taskId: String, totalUsd: Double) {
        val task = store.get(taskId) ?: return
        val delta = totalUsd - task.costUsd
        if (delta <= 0.0) return
        update(taskId) { it.copy(costUsd = totalUsd) }
        runCatching { config.addSpend(delta) }
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
            } catch (e: Throwable) {
                // Catch everything, not just exceptions, so a task never stays stuck on "Working" with nothing running.
                // Stopped or cleared by the user while a call was in flight: don't overwrite that with an error.
                if (currentCoroutineContext().isActive) {
                    runCatching { if (Failures.isTransient(e)) interrupt(taskId, e) else fail(taskId, Failures.describe(e)) }
                }
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
        val willSend = task.sessionId == null || task.pendingPrompt != null
        config.dailyLimitUsd()?.let { if (willSend && overLimit()) throw IllegalStateException(limitMessage(it)) }
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
            append(taskId, Speaker.Status, "Claude is starting on ${task.repo.name}", notify = !config.quietMode())
        }
        val current = store.get(taskId) ?: return
        val prompt = current.pendingPrompt ?: return
        // Cap the session at what's left of today's limit, so Claude stops on its side even if the phone is off.
        config.dailyLimitUsd()?.let { limit ->
            val remaining = (limit - config.spentTodayUsd()).coerceAtLeast(0.0)
            runCatching { claude.setBudget(current.sessionId!!, current.costUsd + remaining) }
        }
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
                    var halted = false
                    claude.follow(sessionId, seen, onConnected = { failures = 0 }, shouldStop = { halted }) { update ->
                        heard = true
                        // Once the turn is over for the app (a failure, or the spending limit), ignore the rest.
                        if (!halted && handle(claude, taskId, update)) halted = true
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
        runCatching { claude.costUsd(sessionId) }.getOrNull()?.let { recordCost(taskId, it) }
        if (store.get(taskId)?.status == TaskStatus.Working) afterTurn(taskId)
    }

    /** Returns true when the turn is over. */
    private fun handle(claude: AgentClient, taskId: String, update: AgentUpdate): Boolean = when (update) {
        is AgentUpdate.Progress -> { progress(taskId, update.text); false }
        is AgentUpdate.Said -> { append(taskId, Speaker.Claude, update.text, notify = !config.quietMode()); false }
        is AgentUpdate.Declined -> { append(taskId, Speaker.Status, "Skipped a risky ${update.toolName} step that needs review", notify = !config.quietMode()); false }
        is AgentUpdate.TurnDone -> true
        is AgentUpdate.Failed -> { fail(taskId, update.reason); true }
        is AgentUpdate.BudgetReached -> { fail(taskId, limitMessage(config.dailyLimitUsd() ?: 0.0)); true }
        is AgentUpdate.Cost -> {
            recordCost(taskId, update.totalUsd)
            val limit = config.dailyLimitUsd()
            if (limit != null && overLimit()) {
                // A backstop for the session's own budget: stop as soon as the day's limit is reached.
                store.get(taskId)?.sessionId?.let { id -> runCatching { claude.interrupt(id) } }
                fail(taskId, limitMessage(limit))
                true
            } else {
                false
            }
        }
    }

    /** After a turn: open or refresh the pull request, then mark the task done or waiting. */
    private suspend fun afterTurn(taskId: String) {
        val task = store.get(taskId) ?: return
        if (config.quietMode()) {
            // Intermediate messages were held back; announce only Claude's final word for this turn.
            task.messages.takeLastWhile { it.speaker != Speaker.You }.lastOrNull { it.speaker == Speaker.Claude }
                ?.let { runCatching { onMessage(task, it) } }
        }
        val token = config.githubToken()
        if (token != null && task.prNumber == null) {
            try {
                withRetries {
                    val github = githubFactory(token)
                    if (github.branchExists(task.repo, task.branch)) {
                        val pr = github.findPull(task.repo, task.branch) ?: github.openPull(
                            task.repo, task.branch, task.title, prBody(task),
                        )
                        val open = pr.prState == PrState.Open
                        // GitHub's own auto-merge keeps working with the phone off. It needs the repo setting
                        // and required checks; otherwise the app merges once it sees the checks pass.
                        val githubMerges = open && config.autoMerge() && !pr.draft && runCatching { github.enableAutoMerge(pr) }.getOrDefault(false)
                        update(taskId) {
                            it.copy(
                                prUrl = pr.htmlUrl, prNumber = pr.number, prState = pr.prState,
                                prOpenedMillis = clock(), githubAutoMerge = githubMerges || pr.hasGitHubAutoMerge,
                            )
                        }
                        append(
                            taskId, Speaker.Status,
                            when {
                                !open -> "Pull request ${pr.number} is already ${pr.prState.name.lowercase()}."
                                githubMerges -> "Pull request ${pr.number} is open. GitHub will merge it when checks pass."
                                config.autoMerge() -> "Pull request ${pr.number} is open. I'll merge it when checks pass."
                                else -> "Pull request ${pr.number} is open for review."
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                append(taskId, Speaker.Status, "Couldn't open the pull request. ${Failures.describe(e)}")
            }
        } else if (token != null && task.hasOpenPr) {
            // A follow-up turn may have pushed new commits: restart the watch window and read the new head's checks,
            // so a fix for failing checks gets watched and merged instead of staying stuck on the old result.
            update(taskId) { it.copy(prOpenedMillis = clock()) }
            try {
                refreshPullRequest(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The regular refresh tries again later.
            }
        }
        val asked = store.get(taskId)?.messages?.lastOrNull { it.speaker == Speaker.Claude }?.text?.trimEnd()?.endsWith("?") == true
        update(taskId) { it.copy(status = if (asked) TaskStatus.NeedsInput else TaskStatus.Done, interruptedSinceMillis = null) }
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
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = false, interruptedSinceMillis = null) }
    }

    /**
     * Stopped by a connection problem or a busy server. Claude may still be working, so this is resumable.
     * Only the first drop in a run is announced, so automatic retries don't repeat the message every time.
     */
    private fun interrupt(taskId: String, e: Throwable) {
        if (store.get(taskId)?.interruptedSinceMillis == null) {
            append(taskId, Speaker.Status, "${Failures.describe(e)} Claude may still be working. I'll keep trying to reconnect, or tap Try again.")
        }
        update(taskId) { it.copy(status = TaskStatus.Failed, interrupted = true, interruptedSinceMillis = it.interruptedSinceMillis ?: clock()) }
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
        if (!config.quietMode()) store.get(taskId)?.let { runCatching { onProgress(it, text) } }
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
        /** How long to wait between automatic tries for a task cut off by a lost connection. */
        private const val RESUME_INTERVAL_MILLIS = 30_000L
        /** How long to keep trying automatically before leaving it to the network callback or the Try again button. */
        private const val AUTO_RESUME_MILLIS = 2 * 60 * 60 * 1000L
        /** How long after opening a pull request the app keeps watching its checks. */
        private const val WATCH_MILLIS = 3 * 60 * 60 * 1000L
        private const val NO_CHECKS_GRACE_MILLIS = 10 * 60 * 1000L

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
