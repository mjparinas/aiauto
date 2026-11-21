package dev.aiauto.data

import android.content.Context
import dev.aiauto.core.AgentSetup
import dev.aiauto.core.RepoRef
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A small JSON-file list that publishes changes as a StateFlow. */
private class JsonListFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    val flow = MutableStateFlow(load())

    private fun load(): List<T> = runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(serializer), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun set(items: List<T>) {
        flow.value = items
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(serializer), items))
        tmp.renameTo(file)
    }
}

class FileTaskStore(context: Context) : TaskStore {
    private val list = JsonListFile(File(context.filesDir, "tasks.json"), Task.serializer())
    val tasks: StateFlow<List<Task>> = list.flow

    override fun get(id: String): Task? = list.flow.value.firstOrNull { it.id == id }
    override fun all(): List<Task> = list.flow.value

    @Synchronized
    override fun put(task: Task) {
        val others = list.flow.value.filter { it.id != task.id }
        list.set((others + task).sortedByDescending { it.updatedMillis })
    }

    @Synchronized
    fun remove(id: String) = list.set(list.flow.value.filter { it.id != id })
}

class RepoStore(context: Context) {
    private val list = JsonListFile(File(context.filesDir, "repos.json"), RepoRef.serializer())
    val repos: StateFlow<List<RepoRef>> = list.flow

    @Synchronized
    fun add(repo: RepoRef) = list.set(list.flow.value.filter { it.fullName != repo.fullName } + repo)

    @Synchronized
    fun remove(repo: RepoRef) = list.set(list.flow.value.filter { it.fullName != repo.fullName })

    fun find(fullName: String) = list.flow.value.firstOrNull { it.fullName == fullName }
}

/** Non-secret settings, plus the agent setup tied to the current API key. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var readAloudInCar: Boolean
        get() = prefs.getBoolean("read_aloud", true)
        set(value) = prefs.edit().putBoolean("read_aloud", value).apply()

    /** Name of a [dev.aiauto.ui.ThemeMode]. */
    var themeMode: String
        get() = prefs.getString("theme_mode", null) ?: "System"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** Name of a [dev.aiauto.ui.Accent]. */
    var accent: String
        get() = prefs.getString("accent", null) ?: "Clay"
        set(value) = prefs.edit().putString("accent", value).apply()

    var agentSetup: AgentSetup?
        get() = prefs.getString("agent_setup", null)?.let { runCatching { json.decodeFromString(AgentSetup.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove("agent_setup") else putString("agent_setup", json.encodeToString(AgentSetup.serializer(), value))
        }.apply()
}
