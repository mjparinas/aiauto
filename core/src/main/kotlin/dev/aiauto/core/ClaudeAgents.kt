package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.agents.AgentCreateParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolsetDefaultConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAutoPolicy
import com.anthropic.models.beta.agents.BetaManagedAgentsModel
import com.anthropic.models.beta.agents.BetaManagedAgentsWebFetchToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebSearchToolConfigParams
import com.anthropic.models.beta.environments.BetaCloudConfigParams
import com.anthropic.models.beta.environments.BetaLimitedNetworkParams
import com.anthropic.models.beta.environments.EnvironmentCreateParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsAgentParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsGitHubRepositoryResourceParams
import com.anthropic.models.beta.sessions.SessionCreateParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentEvaluatedPermission
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserToolConfirmationEventParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.anthropic.core.Timeout
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionEvent
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionStatusIdleEvent
import java.time.Duration
import java.util.Optional

/** What a running session reports back to the app. */
sealed interface AgentUpdate {
    /** A short progress note, e.g. "Editing files". */
    data class Progress(val text: String) : AgentUpdate

    /** Something Claude said, already cleaned for display and speech. */
    data class Said(val text: String) : AgentUpdate

    /** Claude finished its turn and is waiting for the next prompt. */
    data object TurnDone : AgentUpdate

    /** A tool call needed approval; it was declined because nobody can review it while driving. */
    data class Declined(val toolName: String) : AgentUpdate

    data class Failed(val reason: String) : AgentUpdate
}

/** What [TaskEngine] needs from Claude. [ClaudeAgents] is the real one; tests use a fake. */
interface AgentClient : AutoCloseable {
    fun createSetup(): AgentSetup
    fun createSession(setup: AgentSetup, task: Task, githubToken: String): String
    fun sendPrompt(sessionId: String, prompt: String)
    fun follow(
        sessionId: String,
        seenEventIds: MutableSet<String>,
        onConnected: () -> Unit = {},
        shouldStop: () -> Boolean = { false },
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean
}

/**
 * Thin wrapper over Claude Managed Agents: one agent + environment created once,
 * then one session per task with the user's GitHub repo mounted.
 */
class ClaudeAgents(apiKey: String) : AgentClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // The SDK retries dropped connections, 429s and 5xx on ordinary calls. Streams are reconnected by TaskEngine.
        .maxRetries(3)
        .timeout(
            Timeout.builder()
                .connect(Duration.ofSeconds(15))
                // A stream that stays silent this long is treated as dead and reconnected.
                .read(Duration.ofMinutes(3))
                .request(Duration.ofMinutes(10))
                .build(),
        )
        .build()

    /** Creates the agent and environment. Call once per API key and store the result. */
    override fun createSetup(): AgentSetup {
        val environment = client.beta().environments().create(
            EnvironmentCreateParams.builder()
                .name("aiauto-env")
                .config(
                    BetaCloudConfigParams.builder()
                        .networking(
                            BetaLimitedNetworkParams.builder()
                                .allowPackageManagers(true)
                                .allowMcpServers(false)
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        val agent = client.beta().agents().create(
            AgentCreateParams.builder()
                .name("AIAuto coding agent")
                .model(BetaManagedAgentsModel.of(MODEL))
                .system(SYSTEM_PROMPT)
                .addTool(
                    BetaManagedAgentsAgentToolset20260401Params.builder()
                        .defaultConfig(
                            BetaManagedAgentsAgentToolsetDefaultConfigParams.builder()
                                .permissionPolicy(BetaManagedAgentsAutoPolicy.builder().build())
                                .build(),
                        )
                        .addConfig(BetaManagedAgentsWebFetchToolConfigParams.builder().enabled(false).build())
                        .addConfig(BetaManagedAgentsWebSearchToolConfigParams.builder().enabled(false).build())
                        .build(),
                )
                .build(),
        )
        return AgentSetup(agent.id(), agent.version(), environment.id())
    }

    /** Creates a session for [task] with the repo cloned at [REPO_PATH]. Returns the session id. */
    override fun createSession(setup: AgentSetup, task: Task, githubToken: String): String {
        val session = client.beta().sessions().create(
            SessionCreateParams.builder()
                .agent(
                    BetaManagedAgentsAgentParams.builder()
                        .id(setup.agentId)
                        .version(setup.agentVersion)
                        .build(),
                )
                .environmentId(setup.environmentId)
                .title("${task.repo.fullName}: ${task.title}")
                .addResource(
                    BetaManagedAgentsGitHubRepositoryResourceParams.builder()
                        .url(task.repo.url)
                        .mountPath(REPO_PATH)
                        .authorizationToken(githubToken)
                        .build(),
                )
                .build(),
        )
        return session.id()
    }

    /** Sends a spoken or typed prompt. The first prompt of a task carries the branch to use. */
    override fun sendPrompt(sessionId: String, prompt: String) {
        client.beta().sessions().events().send(
            sessionId,
            EventSendParams.builder()
                .addEvent(
                    BetaManagedAgentsUserMessageEventParams.builder()
                        .addTextContent(prompt)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Streams the session until Claude finishes its turn, fails, or [shouldStop] returns true.
     * Opens the stream first, then lists past events so nothing is reported twice after a reconnect.
     * If the turn already ended while the app wasn't listening (the phone lost signal, or the app was
     * closed), that outcome is reported straight from the history instead of waiting forever.
     *
     * Returns true when the turn is over, false when the server closed the stream mid-turn and the
     * caller should reconnect. [onConnected] runs once the stream and history are both loaded.
     */
    override fun follow(
        sessionId: String,
        seenEventIds: MutableSet<String>,
        onConnected: () -> Unit,
        shouldStop: () -> Boolean,
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean {
        client.beta().sessions().events().streamStreaming(sessionId).use { stream ->
            var lastUserMessage = -1
            var lastStop: Pair<Int, BetaManagedAgentsSessionEvent>? = null
            val confirmed = HashSet<String>()
            for ((index, past) in client.beta().sessions().events().list(sessionId).autoPager().withIndex()) {
                eventId(past._json())?.let { seenEventIds.add(it) }
                when {
                    past.isUserMessage() -> lastUserMessage = index
                    past.isUserToolConfirmation() -> confirmed.add(past.asUserToolConfirmation().toolUseId())
                    past.isSessionStatusIdle() || past.isSessionStatusTerminated() -> lastStop = index to past
                    past.isSessionStatusRunning() || past.isSessionStatusRescheduled() -> lastStop = null
                }
            }
            onConnected()
            val stop = lastStop
            if (stop != null && stop.first > lastUserMessage && catchUp(sessionId, stop.second, confirmed, onUpdate)) return true
            for (event in stream.stream()) {
                if (shouldStop()) return true
                val id = eventId(event._json())
                if (id != null && !seenEventIds.add(id)) continue
                if (handle(sessionId, event, onUpdate)) return true
            }
        }
        return false
    }

    /** Reports a stop that happened before we connected. Returns true when the turn is over. */
    private fun catchUp(
        sessionId: String,
        event: BetaManagedAgentsSessionEvent,
        confirmed: Set<String>,
        onUpdate: (AgentUpdate) -> Unit,
    ): Boolean {
        if (event.isSessionStatusTerminated()) {
            onUpdate(AgentUpdate.Failed("The session ended."))
            return true
        }
        val reason = event.asSessionStatusIdle().stopReason()
        if (reason.isRequiresAction()) {
            // Claude was waiting on a tool approval when we lost the connection. Decline it as usual.
            reason.asRequiresAction().eventIds().filter { it !in confirmed }.forEach { id ->
                decline(sessionId, id)
                onUpdate(AgentUpdate.Declined("tool"))
            }
            return false
        }
        return idleOutcome(reason, onUpdate)
    }

    /** Returns true when the turn is over. */
    private fun handle(sessionId: String, event: BetaManagedAgentsStreamSessionEvents, onUpdate: (AgentUpdate) -> Unit): Boolean {
        when {
            event.isAgentMessage() -> {
                val text = event.asAgentMessage().content()
                    .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                    .joinToString(" ")
                val cleaned = SpokenStatus.clean(text)
                if (cleaned.isNotBlank()) onUpdate(AgentUpdate.Said(cleaned))
            }
            event.isAgentToolUse() -> {
                val call = event.asAgentToolUse()
                if (call.evaluatedPermission() == ASK) {
                    decline(sessionId, call.id())
                    onUpdate(AgentUpdate.Declined(call.name()))
                } else {
                    val command = call.input()._additionalProperties()["command"]?.asString()?.orElse(null)
                    SpokenStatus.forToolUse(call.name(), command)?.let { onUpdate(AgentUpdate.Progress(it)) }
                }
            }
            event.isAgentMcpToolUse() -> {
                val call = event.asAgentMcpToolUse()
                if (call.evaluatedPermission() == ASK) {
                    decline(sessionId, call.id())
                    onUpdate(AgentUpdate.Declined(call.name()))
                }
            }
            event.isSessionStatusIdle() -> {
                val reason = event.asSessionStatusIdle().stopReason()
                return if (reason.isRequiresAction()) false else idleOutcome(reason, onUpdate)
            }
            event.isSessionStatusTerminated() -> {
                onUpdate(AgentUpdate.Failed("The session ended."))
                return true
            }
            event.isSessionError() -> {
                // Session errors are usually retried by Claude itself; the idle event that follows says whether it gave up.
                val message = SpokenStatus.clean(event.asSessionError().error().message()).take(100)
                onUpdate(AgentUpdate.Progress("Claude hit a problem and is retrying: $message"))
            }
        }
        return false
    }

    private fun idleOutcome(reason: BetaManagedAgentsSessionStatusIdleEvent.StopReason, onUpdate: (AgentUpdate) -> Unit): Boolean {
        when {
            reason.isEndTurn() -> onUpdate(AgentUpdate.TurnDone)
            reason.isRefusal() -> onUpdate(AgentUpdate.Failed("Claude declined this request."))
            reason.isBudgetReached() -> onUpdate(AgentUpdate.Failed("The session hit its spending limit."))
            else -> onUpdate(AgentUpdate.Failed("Claude stopped after repeated errors on its side. Tap Try again to continue."))
        }
        return true
    }

    private fun decline(sessionId: String, toolUseId: String) {
        client.beta().sessions().events().send(
            sessionId,
            EventSendParams.builder()
                .addEvent(
                    BetaManagedAgentsUserToolConfirmationEventParams.builder()
                        .toolUseId(toolUseId)
                        .result(BetaManagedAgentsUserToolConfirmationEventParams.Result.DENY)
                        .denyMessage("The user is driving and can't review this. Find a safer way, or explain what you need in one sentence.")
                        .build(),
                )
                .build(),
        )
    }

    private fun eventId(json: Optional<com.anthropic.core.JsonValue>): String? =
        json.flatMap { it.asObject() }.flatMap { obj -> Optional.ofNullable(obj["id"]).flatMap { it.asString() } }.orElse(null)

    override fun close() = client.close()

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val REPO_PATH = "/workspace/repo"
        private val ASK = Optional.of(BetaManagedAgentsAgentEvaluatedPermission.ASK)

        /** The first message of a task: the user's spoken request plus the branch rules. */
        fun firstPrompt(task: Task, request: String): String = """
            Repository: ${task.repo.fullName}, cloned at $REPO_PATH.
            Work on a new branch named `${task.branch}` created from `${task.repo.defaultBranch}`.
            Commit your changes and push the branch to origin. Don't open a pull request; the app does that.

            Request (spoken while driving, so it may contain transcription mistakes):
            $request
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a coding agent controlled from a phone app that the user often runs while driving.
            They speak short requests about a GitHub repository, and your updates are read aloud to them.

            How to work:
            - The repository is already cloned in /workspace/repo. Make the change they asked for, run the
              project's tests or build when it has them, commit with a clear message, and push your branch.
            - Pick the most sensible reading of an ambiguous request and say which one you chose, instead of
              stopping to ask. Ask only when no reasonable reading exists.
            - Keep the change focused on the request.

            How to talk:
            - Everything you write is read aloud. Write plain sentences: no markdown, no code blocks,
              no file paths unless they matter, no lists.
            - Send at most a short sentence when you start a major step.
            - End every turn with one or two sentences that say what you changed and whether tests passed.
        """.trimIndent()
    }
}
