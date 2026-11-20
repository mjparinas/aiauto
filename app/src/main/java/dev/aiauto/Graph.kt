package dev.aiauto

import android.content.Context
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.EngineConfig
import dev.aiauto.core.TaskEngine
import dev.aiauto.data.FileTaskStore
import dev.aiauto.data.RepoStore
import dev.aiauto.data.Secrets
import dev.aiauto.data.Settings
import dev.aiauto.notify.Notifier
import dev.aiauto.notify.Speaker
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
    val scope = CoroutineScope(SupervisorJob())

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

    val isConfigured: Boolean get() = secrets.anthropicKey != null && secrets.githubToken != null
}
