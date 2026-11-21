package dev.aiauto.core

/**
 * What happened while you were out of the car, read aloud when you get back in: tasks that finished, failed or
 * are waiting on you, pull requests that merged, and what Claude has cost today. Short enough to hear while
 * pulling out of a parking space.
 */
object Briefing {
    private const val MAX_NAMED = 2

    /** The briefing for tasks changed after [sinceMillis], or null when there's nothing new worth saying. */
    fun since(tasks: List<Task>, sinceMillis: Long, spentTodayUsd: Double): String? {
        val changed = tasks.filter { !it.archived && it.updatedMillis > sinceMillis }
        val waiting = tasks.filter { !it.archived && it.status == TaskStatus.NeedsInput }
        val merged = changed.filter { it.prState == PrState.Merged }
        val finished = changed.filter { it.status == TaskStatus.Done && it.prState != PrState.Merged }
        val failed = changed.filter { it.status == TaskStatus.Failed && !it.interrupted }
        val working = tasks.filter { !it.archived && it.isActive }
        if (merged.isEmpty() && finished.isEmpty() && failed.isEmpty() && waiting.isEmpty()) return null

        val parts = mutableListOf("Welcome back.")
        if (merged.isNotEmpty()) parts += sentence(merged, "pull request merged", "pull requests merged")
        if (finished.isNotEmpty()) parts += sentence(finished, "task finished", "tasks finished")
        if (failed.isNotEmpty()) parts += sentence(failed, "task failed", "tasks failed")
        if (waiting.isNotEmpty()) parts += sentence(waiting, "task needs your answer", "tasks need your answer")
        if (working.isNotEmpty()) parts += "${count(working.size)} still working."
        if (spentTodayUsd > 0) parts += "You've spent ${SpokenStatus.money(spentTodayUsd)} today."
        return parts.joinToString(" ")
    }

    private fun sentence(tasks: List<Task>, one: String, many: String): String {
        val named = tasks.take(MAX_NAMED).joinToString(" and ") { "${it.title} in ${it.repo.name}" }
        val more = tasks.size - MAX_NAMED
        val head = if (tasks.size == 1) "One $one" else "${count(tasks.size)} $many"
        return "$head: $named" + (if (more > 0) ", and $more more." else ".")
    }

    private fun count(n: Int) = when (n) {
        1 -> "One"; 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"
        else -> n.toString()
    }
}
