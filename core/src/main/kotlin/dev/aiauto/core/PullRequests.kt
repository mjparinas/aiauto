package dev.aiauto.core

import java.util.concurrent.ConcurrentHashMap

/** Opens each task's pull request, follows its checks, and merges it when auto-merge is on. */
internal class PullRequests(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val journal: TaskJournal,
    private val clock: () -> Long,
    private val githubFactory: (String) -> GitHub,
) {
    private val mergeRefusals = ConcurrentHashMap<String, String>()

    /** True when [task]'s pull request checks or merge are still worth watching. */
    fun needsWatching(task: Task, now: Long): Boolean {
        if (!task.hasOpenPr) return false
        if (now - (task.prOpenedMillis ?: task.updatedMillis) > WATCH_MILLIS) return false
        return when (task.checks?.state) {
            null, Checks.Pending, Checks.None -> true
            Checks.Passing -> config.autoMerge()
            Checks.Failing, Checks.Unknown -> false
        }
    }

    /**
     * Opens a pull request for the task's branch if Claude pushed it, or picks up one that already exists.
     * Does nothing when there's no GitHub token or the branch isn't there. Safe to repeat.
     */
    fun open(taskId: String) {
        val task = store.get(taskId) ?: return
        val token = config.githubToken() ?: return
        val github = githubFactory(token)
        if (!github.branchExists(task.repo, task.branch)) return
        val pr = github.findPull(task.repo, task.branch) ?: github.openPull(task.repo, task.branch, task.title, body(task))
        val open = pr.prState == PrState.Open
        // GitHub's own auto-merge keeps working with the phone off. It needs the repo setting
        // and required checks; otherwise the app merges once it sees the checks pass.
        val githubMerges = open && config.autoMerge() && !pr.draft && runCatching { github.enableAutoMerge(pr) }.getOrDefault(false)
        journal.update(taskId) {
            it.copy(
                prUrl = pr.htmlUrl, prNumber = pr.number, prState = pr.prState,
                prOpenedMillis = clock(), githubAutoMerge = githubMerges || pr.hasGitHubAutoMerge,
            )
        }
        journal.status(
            taskId,
            when {
                !open -> "Pull request ${pr.number} is already ${pr.prState.name.lowercase()}."
                githubMerges -> "Pull request ${pr.number} is open. GitHub will merge it when checks pass."
                config.autoMerge() -> "Pull request ${pr.number} is open. I'll merge it when checks pass."
                else -> "Pull request ${pr.number} is open for review."
            },
        )
    }

    /**
     * A follow-up turn may have pushed new commits: restart the watch window and read the new head's checks,
     * so a fix for failing checks gets watched and merged instead of staying stuck on the old result.
     */
    fun rewatch(taskId: String) {
        journal.update(taskId) { it.copy(prOpenedMillis = clock()) }
        // On failure the regular refresh tries again later.
        runCatching { refresh(taskId) }
    }

    /** Fetches one task's pull request and checks, says what changed, and merges it if it's ready. */
    fun refresh(taskId: String) {
        val task = store.get(taskId) ?: return
        val number = task.prNumber ?: return
        val token = config.githubToken() ?: return
        val github = githubFactory(token)
        val pr = github.pull(task.repo, number)
        val state = pr.prState
        val checks = if (state == PrState.Open && pr.head.sha.isNotEmpty()) github.checks(task.repo, pr.head.sha) else task.checks
        journal.update(taskId) { it.copy(prState = state, checks = checks, githubAutoMerge = pr.hasGitHubAutoMerge, prUrl = pr.htmlUrl) }

        val willMerge = state == PrState.Open && config.autoMerge() && !pr.draft && !pr.hasGitHubAutoMerge &&
            checks != null && readyToMerge(checks, task, clock())
        val beforeState = task.prState ?: PrState.Open
        when {
            state == PrState.Merged && beforeState != PrState.Merged -> journal.status(taskId, "Pull request $number was merged.")
            state == PrState.Closed && beforeState != PrState.Closed -> journal.status(taskId, "Pull request $number was closed without merging.")
            state == PrState.Open && checks != null && checks.state != task.checks?.state -> when (checks.state) {
                Checks.Failing -> journal.status(taskId, "Checks failed on pull request $number: ${checks.failing.take(3).joinToString(", ")}.")
                Checks.Passing -> journal.status(
                    taskId,
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
                journal.update(taskId) { it.copy(prState = PrState.Merged) }
                journal.status(taskId, "Merged pull request $number.")
            }
            is MergeResult.Refused -> if (mergeRefusals.put(refusalKey, result.reason) == null) {
                journal.status(taskId, "Couldn't merge pull request $number. ${result.reason}")
            }
        }
    }

    /** Merges a task's pull request now, at the user's request. Returns a sentence saying what happened. */
    fun mergeNow(taskId: String): String {
        val task = store.get(taskId) ?: return "That task is gone."
        val number = task.prNumber ?: return "This task has no pull request."
        val token = config.githubToken() ?: return "Add a GitHub token in Settings."
        val message = Projects.mergeMessage(githubFactory(token), task.repo, number)
        journal.status(taskId, message)
        runCatching { refresh(taskId) }
        return message
    }

    private fun readyToMerge(checks: ChecksSummary, task: Task, now: Long): Boolean = when (checks.state) {
        Checks.Passing -> true
        // A repo without CI: give checks time to show up before treating "none" as green.
        Checks.None -> now - (task.prOpenedMillis ?: task.updatedMillis) > NO_CHECKS_GRACE_MILLIS
        else -> false
    }

    private fun body(task: Task): String = buildString {
        appendLine("Requested by voice from AIAuto.")
        appendLine()
        task.messages.filter { it.speaker == Speaker.You }.forEach { appendLine("> ${it.text}") }
        task.messages.lastOrNull { it.speaker == Speaker.Claude }?.let {
            appendLine()
            appendLine(it.text)
        }
    }

    private companion object {
        /** How long after opening a pull request the app keeps watching its checks. */
        const val WATCH_MILLIS = 3 * 60 * 60 * 1000L
        const val NO_CHECKS_GRACE_MILLIS = 10 * 60 * 1000L
    }
}
