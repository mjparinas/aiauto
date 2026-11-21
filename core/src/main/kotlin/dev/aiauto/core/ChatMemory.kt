package dev.aiauto.core

import kotlinx.serialization.Serializable

/** One back-and-forth from a car chat: what the driver said and what Claude said back. */
@Serializable
data class ChatExchange(val said: String, val reply: String, val timeMillis: Long)

/**
 * Where the car chat keeps past exchanges, so a new drive can pick up where the last one left off.
 * The app backs this with a file; tests use a list.
 */
interface ChatMemory {
    fun recent(): List<ChatExchange>
    fun remember(exchange: ChatExchange)
    fun forget()

    companion object {
        /** How many past exchanges are kept and shown to Claude. */
        const val KEEP = 20

        /** Past exchanges older than [beforeMillis] as plain text for the system prompt, or null when there are none. */
        fun describe(exchanges: List<ChatExchange>, beforeMillis: Long, now: Long = System.currentTimeMillis()): String? {
            val earlier = exchanges.filter { it.timeMillis < beforeMillis }.takeLast(KEEP)
            if (earlier.isEmpty()) return null
            return earlier.joinToString("\n") { e ->
                "[${ago(now - e.timeMillis)}] Driver: ${e.said.take(300)}\nYou: ${e.reply.take(500)}"
            }
        }

        private fun ago(millis: Long): String {
            val minutes = millis / 60_000
            return when {
                minutes < 60 -> "${minutes.coerceAtLeast(1)} min ago"
                minutes < 48 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} days ago"
            }
        }
    }
}

/** A memory that forgets everything, for when the setting is off. */
object NoChatMemory : ChatMemory {
    override fun recent() = emptyList<ChatExchange>()
    override fun remember(exchange: ChatExchange) {}
    override fun forget() {}
}
