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
            return RepoRef(match.groupValues[1], match.groupValues[2])
        }
    }
}

@Serializable
enum class TaskStatus { Starting, Working, NeedsInput, Done, Failed }

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
    /** Hidden from the task lists after the user swipes it away. */
    val archived: Boolean = false,
) {
    val lastMessage: TaskMessage? get() = messages.lastOrNull()
}

/** IDs of the agent and environment created once per API key. */
@Serializable
data class AgentSetup(
    val agentId: String,
    val agentVersion: Int,
    val environmentId: String,
)
