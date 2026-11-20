package dev.aiauto.core

/**
 * Turns agent activity into short lines that are safe to show and read aloud while driving:
 * one sentence, no code, no markdown.
 */
object SpokenStatus {
    private const val MAX_CHARS = 220

    fun forToolUse(toolName: String, command: String?): String? = when (toolName) {
        "bash" -> command?.let { describeCommand(it) } ?: "Running a command"
        "read" -> "Reading files"
        "write", "edit" -> "Editing files"
        "glob", "grep" -> "Searching the code"
        "web_search", "web_fetch" -> "Looking something up"
        else -> null
    }

    private fun describeCommand(command: String): String {
        val c = command.trim()
        return when {
            c.startsWith("git push") -> "Pushing changes to GitHub"
            c.startsWith("git commit") -> "Committing changes"
            c.startsWith("git checkout") || c.startsWith("git switch") -> "Creating a branch"
            c.startsWith("git") -> "Working with git"
            Regex("""\b(test|pytest|jest|gradle|npm|pnpm|yarn|cargo|go)\b""").containsMatchIn(c) -> "Building or testing"
            else -> "Running a command"
        }
    }

    /** Strips markdown and trims an agent message to something speakable. */
    fun clean(text: String): String {
        var t = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""(^|\n)\s*#{1,6}\s*"""), "$1")
            .replace(Regex("""\*\*|__|\*"""), "")
            .replace(Regex("""(^|\n)\s*[-•]\s+"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (t.length > MAX_CHARS) {
            val cut = t.lastIndexOf(". ", MAX_CHARS)
            t = if (cut > 60) t.substring(0, cut + 1) else t.substring(0, MAX_CHARS).trimEnd() + "…"
        }
        return t
    }
}
