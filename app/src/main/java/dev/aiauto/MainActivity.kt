package dev.aiauto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aiauto.core.GitHub
import dev.aiauto.core.RepoRef
import dev.aiauto.ui.AiAutoTheme
import dev.aiauto.ui.HomeScreen
import dev.aiauto.ui.NewTaskScreen
import dev.aiauto.ui.SettingsScreen
import dev.aiauto.ui.SettingsState
import dev.aiauto.ui.TaskScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Home : Screen
    data object NewTask : Screen
    data object Settings : Screen
    data class Detail(val taskId: String) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val g = graph
        screen = intent.taskTarget() ?: if (g.isConfigured) Screen.Home else Screen.Settings

        setContent {
            AiAutoTheme {
                val tasks by g.tasks.tasks.collectAsStateWithLifecycle()
                val repos by g.repos.repos.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var settings by remember { mutableStateOf(settingsState(repos)) }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        tasks = tasks,
                        hasRepos = repos.isNotEmpty() && g.isConfigured,
                        onOpen = { screen = Screen.Detail(it.id) },
                        onNew = { screen = Screen.NewTask },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.NewTask -> NewTaskScreen(
                        repos = repos,
                        onBack = { screen = Screen.Home },
                        onStart = { repo, text ->
                            val task = g.engine.start(repo, text)
                            TaskService.ensureRunning(this)
                            screen = Screen.Detail(task.id)
                        },
                    )
                    is Screen.Detail -> {
                        val task = tasks.firstOrNull { it.id == s.taskId }
                        if (task == null) screen = Screen.Home
                        else TaskScreen(task, onBack = { screen = Screen.Home }) { text ->
                            g.engine.prompt(task.id, text)
                            TaskService.ensureRunning(this)
                        }
                    }
                    Screen.Settings -> SettingsScreen(
                        state = settingsState(repos).copy(busy = settings.busy, message = settings.message),
                        onBack = { screen = Screen.Home },
                        onSaveKeys = { anthropic, github ->
                            if (anthropic.isNotBlank()) {
                                g.secrets.anthropicKey = anthropic
                                g.settings.agentSetup = null // a new key means a new agent in that account
                            }
                            if (github.isNotBlank()) g.secrets.githubToken = github
                            settings = settings.copy(message = "Saved.")
                        },
                        onAddRepo = { text ->
                            val parsed = RepoRef.parse(text)
                            val token = g.secrets.githubToken
                            if (parsed == null || token == null) {
                                settings = settings.copy(message = "Enter a repo as owner/name.")
                            } else {
                                settings = settings.copy(busy = true, message = "Checking ${parsed.fullName}…")
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { GitHub(token).repo(parsed.owner, parsed.name) } }
                                    result.onSuccess { g.repos.add(it) }
                                    settings = settings.copy(
                                        busy = false,
                                        message = result.fold({ "Added ${it.fullName}." }, { it.message ?: "Couldn't reach that repo." }),
                                    )
                                }
                            }
                        },
                        onRemoveRepo = { g.repos.remove(it) },
                        onReadAloud = { g.settings.readAloudInCar = it; settings = settings.copy(readAloud = it) },
                        onQuietMode = { g.settings.quietMode = it; settings = settings.copy(quietMode = it) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.taskTarget()?.let { screen = it }
    }

    private fun settingsState(repos: List<RepoRef>) = SettingsState(
        hasAnthropicKey = graph.secrets.anthropicKey != null,
        hasGithubToken = graph.secrets.githubToken != null,
        repos = repos,
        readAloud = graph.settings.readAloudInCar,
        quietMode = graph.settings.quietMode,
    )

    private fun Intent.taskTarget(): Screen? = getStringExtra(EXTRA_TASK)?.let { Screen.Detail(it) }

    companion object {
        const val EXTRA_TASK = "task"
    }
}
