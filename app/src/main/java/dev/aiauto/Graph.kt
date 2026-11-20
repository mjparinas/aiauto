package dev.aiauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.Assistant
import dev.aiauto.core.AssistantActions
import dev.aiauto.core.EngineConfig
import dev.aiauto.core.Projects
import dev.aiauto.core.TaskEngine
import dev.aiauto.data.FileTaskStore
import dev.aiauto.data.RepoStore
import dev.aiauto.data.Secrets
import dev.aiauto.data.Settings
import dev.aiauto.notify.Notifier
import dev.aiauto.notify.Speaker
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** App-wide objects. Created once in [AiAutoApp]. */
class Graph(context: Context) {
    private val appContext = context.applicationContext
    val secrets = Secrets(context)
    val settings = Settings(context)
    val tasks = FileTaskStore(context)
    val repos = RepoStore(context)
    val speaker = Speaker(context)
    val notifier = Notifier(context, settings, speaker)
    // Anything that slips past the engine's own error handling is logged instead of crashing the app.
    val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.e("AiAuto", "Background work failed", e) })

    val engine = TaskEngine(
        store = tasks,
        config = object : EngineConfig {
            override fun anthropicKey() = secrets.anthropicKey
            override fun githubToken() = secrets.githubToken
            override fun agentSetup() = settings.agentSetup
            override fun saveAgentSetup(setup: AgentSetup) { settings.agentSetup = setup }
            override fun dailyLimitUsd() = settings.dailyLimitUsd
            override fun spentTodayUsd() = settings.spentTodayUsd
            override fun addSpend(usd: Double) = settings.addSpend(usd)
            override fun autoMerge() = settings.autoMerge
        },
        scope = scope,
        onMessage = { task, message ->
            notifier.onMessage(task, message)
            TaskService.ensureRunning(context)
        },
        onProgress = { _, text -> notifier.onProgress(text) },
    )

    /** The spoken conversation behind the car's mic button. */
    val assistant = Assistant(
        apiKey = { secrets.anthropicKey },
        actions = object : AssistantActions {
            override fun context(): String {
                val repoList = repos.repos.value.joinToString(", ") { it.fullName }.ifEmpty { "none" }
                val recent = tasks.all().take(6).joinToString("\n") { t ->
                    "- task ${t.id} in ${t.repo.fullName}: ${t.title} (${dev.aiauto.car.TasksScreen.statusLabel(t)})" +
                        (t.lastMessage?.let { ". Last said: ${it.text.take(300)}" } ?: "")
                }.ifEmpty { "none" }
                return "Saved repos: $repoList\nRecent tasks:\n$recent"
            }

            override fun startTask(repo: String, request: String): String {
                val all = repos.repos.value
                val match = all.firstOrNull { it.fullName.equals(repo, ignoreCase = true) }
                    ?: all.filter { it.name.equals(repo.substringAfter('/'), ignoreCase = true) }.singleOrNull()
                    ?: throw IllegalArgumentException("No saved repo matches \"$repo\". Saved: ${all.joinToString { it.fullName }}")
                require(request.isNotBlank()) { "The request is empty." }
                val task = engine.start(match, request)
                TaskService.ensureRunning(appContext)
                return "Started task ${task.id} in ${match.fullName}."
            }

            override fun replyToTask(taskId: String, text: String): String {
                val task = tasks.get(taskId) ?: throw IllegalArgumentException("No task with id $taskId.")
                engine.prompt(task.id, text)
                TaskService.ensureRunning(appContext)
                return "Sent to task ${task.id}."
            }
        },
        canSpend = { settings.dailyLimitUsd?.let { settings.spentTodayUsd < it } ?: true },
        onSpend = { settings.addSpend(it) },
    )

    /** Each repo's status straight from GitHub, for the project screens on the phone and in the car. */
    val projects = Projects(githubToken = { secrets.githubToken }, scope = scope)

    /** Merges a pull request from the project view, then refreshes any task that owns it. Blocking. */
    fun mergePull(repo: dev.aiauto.core.RepoRef, number: Int): String {
        val task = tasks.all().firstOrNull { it.repo.fullName == repo.fullName && it.prNumber == number }
        val message = if (task != null) engine.mergeNow(task.id) else projects.merge(repo, number)
        projects.refresh(repo)
        return message
    }

    /** Resumes tasks that stopped because the connection dropped, as soon as the phone is back online. */
    fun watchNetwork(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        engine.onNetworkAvailable()
                        if (tasks.all().any { engine.isRunning(it.id) }) TaskService.ensureRunning(context)
                    }
                }
            })
        }
    }

    val isConfigured: Boolean get() = secrets.anthropicKey != null && secrets.githubToken != null
}
