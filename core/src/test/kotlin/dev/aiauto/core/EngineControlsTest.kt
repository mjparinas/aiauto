package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stop, clear, spending limits, and pull request watching / auto-merge. */
class EngineControlsTest {
    private val repo = RepoRef("me", "app")

    private class MapStore : TaskStore {
        val map = LinkedHashMap<String, Task>()
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun put(task: Task) { map[task.id] = task }
        override fun remove(id: String) { map.remove(id) }
    }

    private class Config : EngineConfig {
        var limit: Double? = null
        var spent = 0.0
        var merge = false
        override fun anthropicKey() = "key"
        override fun githubToken() = "token"
        override fun agentSetup() = AgentSetup("agent", 1, "env")
        override fun saveAgentSetup(setup: AgentSetup) {}
        override fun dailyLimitUsd() = limit
        override fun spentTodayUsd() = spent
        override fun addSpend(usd: Double) { spent += usd }
        override fun autoMerge() = merge
    }

    private class FakeClaude : AgentClient {
        /** Updates each follow() emits, in order; after they run out, a normal finished turn. */
        val turns = ArrayDeque<List<AgentUpdate>>()
        val sent = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        val archived = mutableListOf<String>()
        val budgets = mutableListOf<Double>()
        var finalCost: Double? = null

        override fun createSetup() = AgentSetup("agent", 1, "env")
        override fun createSession(setup: AgentSetup, task: Task, githubToken: String) = "session-${task.id}"
        override fun sendPrompt(sessionId: String, prompt: String) { sent += prompt }
        override fun follow(
            sessionId: String,
            seenEventIds: MutableSet<String>,
            onConnected: () -> Unit,
            shouldStop: () -> Boolean,
            onUpdate: (AgentUpdate) -> Unit,
        ): Boolean {
            onConnected()
            (turns.removeFirstOrNull() ?: listOf(AgentUpdate.Said("Done."), AgentUpdate.TurnDone)).forEach { if (!shouldStop()) onUpdate(it) }
            return true
        }
        override fun interrupt(sessionId: String) { interrupted += sessionId }
        override fun archive(sessionId: String) { archived += sessionId }
        override fun setBudget(sessionId: String, maxUsd: Double) { budgets += maxUsd }
        override fun costUsd(sessionId: String) = finalCost
        override fun close() {}
    }

    private open class FakeGitHub : GitHub("token") {
        var branch = true
        var pr = PullRequest(7, "https://github.com/me/app/pull/7", nodeId = "PR_7", head = PullRequest.Head("abc"))
        var checksResult = ChecksSummary(Checks.Pending, total = 1)
        var autoMergeAllowed = false
        val merges = mutableListOf<Int>()
        var refuseMerge: String? = null

        override fun branchExists(repo: RepoRef, branch: String) = this.branch
        override fun findPull(repo: RepoRef, branch: String): PullRequest? = null
        override fun openPull(repo: RepoRef, branch: String, title: String, body: String) = pr
        override fun pull(repo: RepoRef, number: Int) = pr
        override fun checks(repo: RepoRef, sha: String) = checksResult
        override fun enableAutoMerge(pull: PullRequest) = autoMergeAllowed
        override fun merge(repo: RepoRef, number: Int): MergeResult {
            refuseMerge?.let { return MergeResult.Refused(it) }
            merges += number
            pr = pr.copy(merged = true, state = "closed")
            return MergeResult.Merged
        }
    }

    private val store = MapStore()
    private val config = Config()
    private val claude = FakeClaude()
    private val github = FakeGitHub()
    private val said = mutableListOf<String>()
    private var now = 1_000_000L

    private fun engine() = TaskEngine(
        store = store,
        config = config,
        scope = CoroutineScope(SupervisorJob()),
        clock = { now },
        claudeFactory = { claude },
        githubFactory = { github },
        onMessage = { _, m -> said += m.text },
        sleep = {},
        dispatcher = Dispatchers.Unconfined,
    )

    @Test fun tracksCostPerTaskAndAddsItToToday() {
        claude.turns += listOf(AgentUpdate.Cost(0.40), AgentUpdate.Cost(1.25), AgentUpdate.TurnDone)
        claude.finalCost = 1.30
        val task = engine().start(repo, "add a test")
        assertEquals(1.30, store.get(task.id)!!.costUsd, 1e-9)
        assertEquals(1.30, config.spent, 1e-9)
    }

    @Test fun refusesToStartWhenTodaysLimitIsUsedUp() {
        config.limit = 5.0
        config.spent = 5.0
        val task = engine().start(repo, "anything")
        val failed = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, failed.status)
        assertTrue(failed.lastMessage!!.text.contains("spending limit"))
        assertTrue(claude.sent.isEmpty())
    }

    @Test fun capsTheSessionAtWhatIsLeftToday() {
        config.limit = 10.0
        config.spent = 4.0
        engine().start(repo, "anything")
        assertEquals(listOf(6.0), claude.budgets)
    }

    @Test fun stopsMidTurnWhenTheLimitIsReached() {
        config.limit = 2.0
        claude.turns += listOf(AgentUpdate.Cost(1.0), AgentUpdate.Cost(2.1), AgentUpdate.Said("never heard"), AgentUpdate.TurnDone)
        val task = engine().start(repo, "big refactor")
        val stopped = store.get(task.id)!!
        assertEquals(TaskStatus.Failed, stopped.status)
        assertEquals(listOf("session-${task.id}"), claude.interrupted)
        assertFalse(stopped.messages.any { it.text == "never heard" })
    }

    @Test fun budgetReachedOnClaudesSideSaysWhy() {
        config.limit = 3.0
        claude.turns += listOf(AgentUpdate.BudgetReached)
        val task = engine().start(repo, "x")
        assertTrue(store.get(task.id)!!.lastMessage!!.text.startsWith("Stopped at today's $3.00 spending limit"))
    }

    @Test fun stopInterruptsAndClearArchives() {
        val engine = engine()
        val now = 0L
        store.put(Task("t1", repo, "x", "b", sessionId = "s1", status = TaskStatus.Working, createdMillis = now, updatedMillis = now))
        engine.stop("t1")
        assertEquals(TaskStatus.Stopped, store.get("t1")!!.status)
        assertEquals(listOf("s1"), claude.interrupted)
        assertTrue(store.get("t1")!!.isFinished)

        store.put(Task("t2", repo, "y", "b", sessionId = "s2", status = TaskStatus.Done, prNumber = 3, prState = PrState.Open, createdMillis = now, updatedMillis = now))
        assertEquals(1, engine.clearFinished())
        assertNull(store.get("t1"))
        assertEquals(listOf("s1"), claude.archived)
        // A done task whose pull request is still open stays, so it can be merged from the car.
        assertTrue(store.get("t2") != null)
    }

    @Test fun stopDoesNothingToAFinishedTask() {
        val engine = engine()
        store.put(Task("t", repo, "x", "b", sessionId = "s", status = TaskStatus.Done, createdMillis = 0, updatedMillis = 0))
        engine.stop("t")
        assertEquals(TaskStatus.Done, store.get("t")!!.status)
        assertTrue(claude.interrupted.isEmpty())
    }

    @Test fun opensThePrAndTurnsOnGitHubAutoMergeWhenAllowed() {
        config.merge = true
        github.autoMergeAllowed = true
        val task = engine().start(repo, "fix it")
        val done = store.get(task.id)!!
        assertEquals(7, done.prNumber)
        assertEquals(PrState.Open, done.prState)
        assertTrue(done.githubAutoMerge)
        assertTrue(said.any { it.contains("GitHub will merge it when checks pass") })
    }

    @Test fun mergesOnceChecksPassWhenGitHubAutoMergeIsUnavailable() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        assertTrue(said.last().contains("I'll merge it when checks pass"))
        assertTrue(engine.isWatchingPullRequests())

        engine.refreshPullRequest(task.id)
        assertEquals(Checks.Pending, store.get(task.id)!!.checks?.state)
        assertTrue(github.merges.isEmpty())

        github.checksResult = ChecksSummary(Checks.Passing, total = 2)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
        assertEquals(PrState.Merged, store.get(task.id)!!.prState)
        assertTrue(said.any { it == "Checks passed on pull request 7. Merging it now." })
        assertTrue(said.any { it == "Merged pull request 7." })
        assertFalse(engine.isWatchingPullRequests())
        assertTrue(store.get(task.id)!!.isFinished)
    }

    @Test fun neverMergesFailingOrUnreadableChecks() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 2)
        engine.refreshPullRequest(task.id)
        assertTrue(said.last().contains("Checks failed on pull request 7: build"))
        github.checksResult = ChecksSummary(Checks.Unknown)
        now += 60 * 60 * 1000L
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
    }

    @Test fun watchesAgainAfterAFollowUpPushesAFix() {
        config.merge = true
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Failing, failing = listOf("build"), total = 1)
        engine.refreshPullRequest(task.id)
        now += 4 * 60 * 60 * 1000L
        assertFalse(engine.isWatchingPullRequests())

        // Claude pushes a fix in a follow-up turn; the new head's checks are running.
        github.pr = github.pr.copy(head = PullRequest.Head("def"))
        github.checksResult = ChecksSummary(Checks.Pending, total = 1)
        engine.prompt(task.id, "fix the build")
        assertEquals(Checks.Pending, store.get(task.id)!!.checks?.state)
        assertTrue(engine.isWatchingPullRequests())

        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
    }

    @Test fun waitsForChecksToAppearBeforeMergingARepoWithoutCi() {
        config.merge = true
        github.checksResult = ChecksSummary(Checks.None)
        val engine = engine()
        val task = engine.start(repo, "fix it")
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
        now += 11 * 60 * 1000L
        engine.refreshPullRequest(task.id)
        assertEquals(listOf(7), github.merges)
    }

    @Test fun reportsARefusedMergeOnlyOnce() {
        config.merge = true
        github.refuseMerge = "Pull request is not mergeable"
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        val engine = engine()
        val task = engine.start(repo, "fix it")
        engine.refreshPullRequest(task.id)
        engine.refreshPullRequest(task.id)
        assertEquals(1, said.count { it.startsWith("Couldn't merge pull request 7") })
    }

    @Test fun withoutAutoMergeItOnlyReports() {
        val engine = engine()
        val task = engine.start(repo, "fix it")
        github.checksResult = ChecksSummary(Checks.Passing, total = 1)
        engine.refreshPullRequest(task.id)
        assertTrue(github.merges.isEmpty())
        assertEquals("Checks passed on pull request 7.", said.last())
        github.pr = github.pr.copy(merged = true, state = "closed")
        engine.refreshPullRequest(task.id)
        assertEquals("Pull request 7 was merged.", said.last())
    }

    @Test fun manualMergeTurnsOnAutoMergeWhenChecksAreStillRunning() {
        val gh = object : FakeGitHub() {
            override fun merge(repo: RepoRef, number: Int) = MergeResult.Refused("Required status check \"build\" is expected.")
        }
        gh.autoMergeAllowed = true
        assertTrue(Projects.mergeMessage(gh, repo, 7).contains("turned on auto-merge"))
        gh.autoMergeAllowed = false
        assertEquals("Couldn't merge pull request 7. Required status check \"build\" is expected.", Projects.mergeMessage(gh, repo, 7))
    }

    @Test fun speaksARepoSummary() {
        val status = RepoStatus(
            repo,
            CommitInfo("abc", "Add **dark** mode", "Mel", now - 2 * 60 * 60 * 1000L),
            ChecksSummary(Checks.Passing, total = 3),
            listOf(PullStatus(PullRequest(5, "u", title = "Fix signing"), ChecksSummary(Checks.Failing, listOf("build"), 2))),
        )
        assertEquals(
            "app. Main has checks passing. Last change: Add dark mode, 2 hours ago. One open pull request. Number 5, Fix signing, build failing.",
            SpokenStatus.forRepo(status, now),
        )
        assertEquals("$1.30", SpokenStatus.money(1.3))
    }
}
