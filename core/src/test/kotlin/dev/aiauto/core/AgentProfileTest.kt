package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentProfileTest {
    @Test fun noRulesLeavesThePromptAlone() {
        assertEquals("base", AgentProfile().systemPrompt("base"))
        assertEquals("base", AgentProfile(instructions = "  \n ").systemPrompt("base"))
    }

    @Test fun ticksAndFreeTextBecomeOneList() {
        val prompt = AgentProfile(
            rules = listOf(StandingRule.LeaveCi.name, StandingRule.AddTests.name, "Gone"),
            instructions = "Use British spelling\n\n  Never push to main  ",
        ).systemPrompt("base")
        assertTrue(prompt.startsWith("base\n\nStanding rules"))
        val bullets = prompt.lines().filter { it.startsWith("- ") }
        assertEquals(
            listOf(StandingRule.AddTests.instruction, StandingRule.LeaveCi.instruction, "Use British spelling", "Never push to main").map { "- $it" },
            bullets,
        )
    }

    @Test fun unknownModelFallsBackToOpus() {
        assertEquals(ModelChoice.Opus, ModelChoice.of("claude-old"))
        assertEquals(ModelChoice.Haiku, ModelChoice.of("claude-haiku-5-5"))
    }

    @Test fun sessionsUseTheWebEnvironmentOnlyWithWebSearchOn() {
        assertEquals("env", AgentSetup("a", 1, "env", AgentProfile(webSearch = false), "web").sessionEnvironmentId)
        assertEquals("web", AgentSetup("a", 1, "env", AgentProfile(webSearch = true), "web").sessionEnvironmentId)
    }
}
