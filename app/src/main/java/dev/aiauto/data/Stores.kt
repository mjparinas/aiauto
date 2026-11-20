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

    private fun load(): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), file.readText()) }
            .getOrElse {
                // Keep the unreadable file for inspection instead of overwriting it on the next save.
                runCatching { file.renameTo(File(file.parentFile, file.name + ".bad")) }
                emptyList()
            }
    }

    @Synchronized
    fun set(items: List<T>) {
        flow.value = items
        // A full disk shouldn't crash the app or stop a task; the list stays correct in memory.
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(serializer), items))
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true).also { tmp.delete() }
        }
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
    override fun remove(id: String) = list.set(list.flow.value.filter { it.id != id })
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

    /** Daily spending cap in US dollars, or null for none. New installs start at [DEFAULT_DAILY_LIMIT]. */
    var dailyLimitUsd: Double?
        get() = if (prefs.contains("daily_limit")) prefs.getFloat("daily_limit", 0f).toDouble().takeIf { it > 0 } else DEFAULT_DAILY_LIMIT
        set(value) = prefs.edit().putFloat("daily_limit", (value ?: 0.0).toFloat()).apply()

    /** Merge a task's pull request once its checks pass. */
    var autoMerge: Boolean
        get() = prefs.getBoolean("auto_merge", true)
        set(value) = prefs.edit().putBoolean("auto_merge", value).apply()

    /** What Claude has cost today, by the phone's local date. Resets at midnight. */
    @get:Synchronized
    val spentTodayUsd: Double
        get() = if (prefs.getString("spend_day", null) == today()) prefs.getFloat("spend_usd", 0f).toDouble() else 0.0

    @Synchronized
    fun addSpend(usd: Double) {
        val total = spentTodayUsd + usd
        prefs.edit().putString("spend_day", today()).putFloat("spend_usd", total.toFloat()).apply()
    }

    private fun today() = java.time.LocalDate.now().toString()

    /**
     * Builds before this fix read the API's cents as dollars, so saved costs are 100 times too high.
     * Scales today's total and every task's cost down once.
     */
    @Synchronized
    fun fixCostUnits(tasks: FileTaskStore) {
        if (prefs.getInt("cost_units", 1) >= 2) return
        if (prefs.contains("spend_usd")) prefs.edit().putFloat("spend_usd", prefs.getFloat("spend_usd", 0f) / 100f).apply()
        tasks.all().filter { it.costUsd > 0 }.forEach { tasks.put(it.copy(costUsd = it.costUsd / 100)) }
        prefs.edit().putInt("cost_units", 2).apply()
    }

    var agentSetup: AgentSetup?
        get() = prefs.getString("agent_setup", null)?.let { runCatching { json.decodeFromString(AgentSetup.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove("agent_setup") else putString("agent_setup", json.encodeToString(AgentSetup.serializer(), value))
        }.apply()

    companion object {
        const val DEFAULT_DAILY_LIMIT = 20.0
    }
}
