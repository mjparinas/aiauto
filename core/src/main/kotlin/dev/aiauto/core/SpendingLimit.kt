package dev.aiauto.core

import java.util.Locale

/** Keeps Claude within the daily spending limit and adds each session's cost to today's total. */
internal class SpendingLimit(
    private val store: TaskStore,
    private val config: EngineConfig,
    private val journal: TaskJournal,
) {
    fun spentTodayUsd(): Double = config.spentTodayUsd()

    /** The sentence said when a task stops at the limit. */
    fun limitMessage(): String {
        val limit = config.dailyLimitUsd() ?: 0.0
        return "Stopped at today's ${"$"}${"%.2f".format(Locale.US, limit)} spending limit. Raise it in Settings to keep going."
    }

    fun overLimit(): Boolean {
        val limit = config.dailyLimitUsd() ?: return false
        return config.spentTodayUsd() >= limit - 0.005
    }

    /** Throws before Claude is asked to do more work once today's limit is reached. */
    fun checkBeforeSending() {
        if (overLimit()) throw IllegalStateException(limitMessage())
    }

    /** Caps the session at what's left of today's limit, so Claude stops on its side even if the phone is off. */
    fun capSession(claude: AgentClient, task: Task) {
        val limit = config.dailyLimitUsd() ?: return
        val sessionId = task.sessionId ?: return
        val remaining = (limit - config.spentTodayUsd()).coerceAtLeast(0.0)
        runCatching { claude.setBudget(sessionId, task.costUsd + remaining) }
    }

    /** Records a session's new running total and adds the difference to today's spend. */
    @Synchronized
    fun recordCost(taskId: String, totalUsd: Double) {
        val task = store.get(taskId) ?: return
        val delta = totalUsd - task.costUsd
        if (delta <= 0.0) return
        journal.update(taskId) { it.copy(costUsd = totalUsd) }
        runCatching { config.addSpend(delta) }
    }

    /** Fetches a session's cost from Claude and records it. Never throws. */
    fun recordSessionCost(claude: AgentClient, taskId: String, sessionId: String) {
        runCatching { claude.costUsd(sessionId) }.getOrNull()?.let { recordCost(taskId, it) }
    }
}
