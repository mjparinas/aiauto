package dev.aiauto.core

import kotlinx.serialization.Serializable

/** A GitHub repository the user registered in the app. */
@Serializable
data class RepoRef(
    val owner: String,
    val name: String,
    val defaultBranch: String = "main",
) {
    val fullName: String get() = "$owner/$name"
    val url: String get() = "https://github.com/$owner/$name"

    companion object {
        private val pattern = Regex("""^(?:https?://github\.com/)?([\w.-]+)/([\w.-]+?)(?:\.git)?/?$""")

        /** Accepts `owner/name` or a github.com URL. Returns null when the text isn't a repo. */
        fun parse(text: String): RepoRef? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            // "." and ".." would turn the API path into a different GitHub endpoint.
            if (match.groupValues.drop(1).any { it.all { c -> c == '.' } }) return null
            return RepoRef(match.groupValues[1], match.groupValues[2])
        }
    }
}

@Serializable
enum class TaskStatus { Starting, Working, NeedsInput, Done, Failed, Stopped }

/** Where a pull request stands on GitHub. */
@Serializable
enum class PrState { Open, Merged, Closed }

/**
 * The combined result of a commit's checks (GitHub Actions and other status checks).
 * [Unknown] means the token can't read them, which is never treated as passing.
 */
@Serializable
enum class Checks { None, Pending, Passing, Failing, Unknown }

@Serializable
data class ChecksSummary(
    val state: Checks,
    val failing: List<String> = emptyList(),
    val total: Int = 0,
)

@Serializable
enum class Speaker { You, Claude, Status }

/** One line in a task's conversation, shown on the phone and read aloud in the car. */
@Serializable
data class TaskMessage(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val timeMillis: Long,
)

/** A unit of work: one Managed Agents session working on one repo. */
@Serializable
data class Task(
    val id: String,
    val repo: RepoRef,
    val title: String,
    val branch: String,
    val sessionId: String? = null,
    val status: TaskStatus = TaskStatus.Starting,
    val prUrl: String? = null,
    val prNumber: Int? = null,
    val messages: List<TaskMessage> = emptyList(),
    val createdMillis: Long,
    val updatedMillis: Long,
    /** A prompt that hasn't reached Claude yet. It's sent again when the task resumes. */
    val pendingPrompt: String? = null,
    /** Stopped because the connection dropped, not because of Claude. Resumes when the network is back. */
    val interrupted: Boolean = false,
    /** When the connection first dropped in this run of trouble. Cleared once a turn finishes or the task stops for good. */
    val interruptedSinceMillis: Long? = null,
    /** Live pull request state, refreshed from GitHub. Null until a pull request exists. */
    val prState: PrState? = null,
    val checks: ChecksSummary? = null,
    /** GitHub's own auto-merge is on for the pull request, so GitHub merges it once required checks pass. */
    val githubAutoMerge: Boolean = false,
    val prOpenedMillis: Long? = null,
    /** What this task's Claude session has cost so far, in US dollars at list price. */
    val costUsd: Double = 0.0,
    /** Put away by the user: shown on the Archived tab instead of the main list. */
    val archived: Boolean = false,
) {
    /** A pull request exists and hasn't been merged or closed yet. */
    val hasOpenPr: Boolean get() = prNumber != null && (prState ?: PrState.Open) == PrState.Open

    /** Nothing left to do or watch: safe to clear from the list. */
    val isFinished: Boolean
        get() = status == TaskStatus.Stopped ||
            (status == TaskStatus.Failed && !interrupted) ||
            (status == TaskStatus.Done && !hasOpenPr)

    val lastMessage: TaskMessage? get() = messages.lastOrNull()
}

/** IDs of the agent and environment created once per API key. */
@Serializable
data class AgentSetup(
    val agentId: String,
    val agentVersion: Int,
    val environmentId: String,
)
