package dev.aiauto.core

/** Where tasks live. The Android app backs this with a file; tests use a map. */
interface TaskStore {
    fun get(id: String): Task?
    fun all(): List<Task>
    fun put(task: Task)
    fun remove(id: String)
}

/** Credentials and one-time setup the engine needs. Returning null means "not configured yet". */
interface EngineConfig {
    fun anthropicKey(): String?
    fun githubToken(): String?
    fun agentSetup(): AgentSetup?
    fun saveAgentSetup(setup: AgentSetup)

    /** Quiet mode: only announce each turn's result, failures and pull requests, not every step. */
    fun quietMode(): Boolean = false

    /** The most Claude may spend per day across all tasks, in US dollars. Null means no limit. */
    fun dailyLimitUsd(): Double? = null
    /** What has been spent today so far. */
    fun spentTodayUsd(): Double = 0.0
    fun addSpend(usd: Double) {}

    /** Merge a task's pull request once its checks pass. */
    fun autoMerge(): Boolean = false
}
