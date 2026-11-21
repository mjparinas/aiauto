package dev.aiauto.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import java.time.Duration

/** What the voice assistant can do in the app, beyond talking. The app wires these to [TaskEngine]. */
interface AssistantActions {
    /** A short plain-text summary of the saved repos and recent tasks, given to Claude on every turn. */
    fun context(): String

    /** Starts a coding task. Returns what to tell Claude, such as "Started task ab12cd34 in owner/repo". */
    fun startTask(repo: String, request: String): String

    /** Sends a follow-up message to an existing task. Returns what to tell Claude. */
    fun replyToTask(taskId: String, text: String): String

    /** Saves a repo from its spoken name (owner/name or just the name). Returns what to tell Claude. */
    fun addRepo(name: String): String
}

/**
 * A spoken conversation with Claude for the car's mic button. Each [ask] takes what the driver said and
 * returns what Claude says back, ready to be read aloud. Claude can also start a coding task or reply
 * to one through [AssistantActions]. Blocking: call it off the main thread.
 */
class Assistant(
    private val apiKey: () -> String?,
    private val actions: AssistantActions,
    private val canSpend: () -> Boolean = { true },
    private val onSpend: (Double) -> Unit = {},
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient,
) {
    private val history = mutableListOf<MessageParam>()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    fun ask(spoken: String): String {
        val key = apiKey() ?: return "Add your Anthropic API key in the app on your phone first."
        if (!canSpend()) return "You've reached today's spending limit, so I can't answer right now. You can raise it in Settings on your phone."
        val start = history.size
        return try {
            val claude = clientFor(key)
            history += MessageParam.ofUser(spoken)
            converse(claude)
        } catch (e: Throwable) {
            // Drop the half-finished turn so the next question starts from a clean history.
            while (history.size > start) history.removeAt(history.size - 1)
            Failures.describe(e)
        }
    }

    /** Forgets the conversation, e.g. when the driver starts over. */
    @Synchronized
    fun reset() = history.clear()

    private fun converse(claude: AnthropicClient): String {
        val spoken = StringBuilder()
        repeat(MAX_TOOL_ROUNDS) {
            val message = claude.messages().create(params())
            onSpend(costUsd(message))
            history += message.toParam()
            message.content().forEach { block -> block.text().ifPresent { spoken.appendSentence(it.text()) } }
            val stop = message.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) return spoken.ifBlank { "I can't help with that one." }.toString()
            val calls = message.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != StopReason.TOOL_USE || calls.isEmpty()) {
                trimHistory()
                return spoken.ifBlank { "Sorry, I didn't catch an answer. Try again." }.toString()
            }
            history += MessageParam.ofUserOfContentBlockParams(calls.map { ContentBlockParam.ofToolResult(run(it)) })
        }
        trimHistory()
        return spoken.ifBlank { "That took too many steps. Try asking again more simply." }.toString()
    }

    private fun run(call: ToolUseBlock): ToolResultBlockParam {
        val input = call._input().asObject().orElse(emptyMap())
        fun arg(name: String) = input[name]?.asString()?.orElse(null)?.trim().orEmpty()
        val (text, error) = runCatching {
            when (call.name()) {
                START_TASK -> actions.startTask(arg("repo"), arg("request"))
                REPLY_TO_TASK -> actions.replyToTask(arg("task_id"), arg("text"))
                ADD_REPO -> actions.addRepo(arg("name"))
                else -> throw IllegalArgumentException("Unknown tool ${call.name()}")
            }
        }.fold({ it to false }, { (it.message ?: "That didn't work.") to true })
        return ToolResultBlockParam.builder().toolUseId(call.id()).content(text).isError(error).build()
    }

    private fun params(): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(MAX_TOKENS)
        // Low effort keeps replies quick, which matters more than depth while driving.
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        .system(SYSTEM + "\n\nCurrent state of the app:\n" + actions.context())
        .addTool(startTaskTool)
        .addTool(replyTool)
        .addTool(addRepoTool)
        .messages(history.toList())
        .build()

    /** Keeps the conversation short. Only cuts right before a plain spoken question, so tool calls stay paired. */
    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        val cut = (history.size - MAX_HISTORY until history.size).firstOrNull { i ->
            history[i].role() == MessageParam.Role.USER && history[i].content().isString()
        } ?: return
        repeat(cut) { history.removeAt(0) }
    }

    private fun clientFor(key: String): AnthropicClient {
        val current = client
        if (current != null && clientKey == key) return current
        current?.close()
        return clientFactory(key).also { client = it; clientKey = key }
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_TOKENS = 4000L
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_HISTORY = 30
        private const val START_TASK = "start_coding_task"
        private const val REPLY_TO_TASK = "reply_to_task"
        private const val ADD_REPO = "add_repo"

        // List price for claude-opus-5-5, US dollars per million tokens.
        private const val INPUT_PER_MTOK = 4.0
        private const val OUTPUT_PER_MTOK = 20.0

        private val SYSTEM = """
            You are Claude, talking with the driver through their car's speakers. Everything you write is read
            aloud by text to speech, so answer in a few short spoken sentences: no markdown, lists, code, URLs,
            or emoji. Keep it brief and easy to follow while driving, and never encourage them to look at a screen.
            You can also start coding tasks in the driver's GitHub repos and reply to running tasks with the tools.
            Only start a task when the driver clearly asks for one, and say back in one sentence what you started.
            If the repo is unclear, ask which one, naming at most three.
            When the driver asks to add a repo, call add_repo with the name as they said it, then say which repo
            was added. If it didn't work, read back the reason briefly.
        """.trimIndent()

        private val startTaskTool = Tool.builder()
            .name(START_TASK)
            .description("Start a coding task: an agent works on the repo, pushes a branch and opens a pull request.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("repo", stringProp("The repo as owner/name, from the saved repos list."))
                            .putAdditionalProperty("request", stringProp("What to do, in the driver's words, made clear and complete."))
                            .build(),
                    )
                    .required(listOf("repo", "request"))
                    .build(),
            )
            .build()

        private val replyTool = Tool.builder()
            .name(REPLY_TO_TASK)
            .description("Send a message to a task that already exists, such as an answer to its question.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("task_id", stringProp("The task id from the recent tasks list."))
                            .putAdditionalProperty("text", stringProp("The message for the task."))
                            .build(),
                    )
                    .required(listOf("task_id", "text"))
                    .build(),
            )
            .build()

        private val addRepoTool = Tool.builder()
            .name(ADD_REPO)
            .description("Save one of the driver's GitHub repos so tasks can use it. Finds it among the repos their token can push to.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("name", stringProp("The repo as the driver said it: owner/name, or just the name."))
                            .build(),
                    )
                    .required(listOf("name"))
                    .build(),
            )
            .build()

        private fun stringProp(description: String) = JsonValue.from(mapOf("type" to "string", "description" to description))

        private fun defaultClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(60))
            .build()

        fun costUsd(message: Message): Double {
            val usage = message.usage()
            val input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L) + usage.cacheReadInputTokens().orElse(0L)
            return (input * INPUT_PER_MTOK + usage.outputTokens() * OUTPUT_PER_MTOK) / 1_000_000.0
        }

        private fun StringBuilder.appendSentence(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            if (isNotEmpty()) append(' ')
            append(clean)
        }
    }
}
