package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class AssistantTest {
    private val actions = object : AssistantActions {
        override fun context() = "none"
        override fun startTask(repo: String, request: String) = fail("not expected")
        override fun replyToTask(taskId: String, text: String) = fail("not expected")
        override fun addRepo(name: String) = fail("not expected")
    }

    @Test
    fun asksForSetupWithoutKey() {
        val assistant = Assistant(apiKey = { null }, actions = actions, clientFactory = { fail("no client without a key") })
        assertTrue("API key" in assistant.ask("hello"))
    }

    @Test
    fun stopsAtDailyLimit() {
        val assistant = Assistant(apiKey = { "k" }, actions = actions, canSpend = { false }, clientFactory = { fail("no call over the limit") })
        assertTrue("spending limit" in assistant.ask("hello"))
    }
}
