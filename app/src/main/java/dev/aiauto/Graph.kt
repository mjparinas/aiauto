package dev.aiauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.EngineConfig
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
        },
        scope = scope,
        onMessage = { task, message ->
            notifier.onMessage(task, message)
            TaskService.ensureRunning(context)
        },
        onProgress = { _, text -> notifier.onProgress(text) },
    )

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
