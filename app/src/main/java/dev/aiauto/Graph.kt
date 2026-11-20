package dev.aiauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.aiauto.core.AgentSetup
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
