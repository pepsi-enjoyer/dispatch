package com.dispatch.radio

import com.dispatch.radio.model.Agent

/**
 * Voice command parser (dispatch-88k.3).
 *
 * Priority-ordered:
 *   1. Normalize (lowercase/trim)
 *   2. Command prefixes (dispatch/new/spin up, terminate/kill/shut down, switch to/target)
 *   3. Callsign addressing at utterance start (optional comma)
 *   4. Default: send to current target
 */
sealed class Command {
    data class Dispatch(val tool: String) : Command()
    data class Terminate(val slot: Int) : Command()
    data class SetTarget(val slot: Int) : Command()
    data class SendTo(val slot: Int, val text: String) : Command()
    data class SendToTarget(val text: String) : Command()
}

object CommandParser {

    // Fuzzy alias table for tool names
    private val TOOL_ALIASES: Map<String, String> = mapOf(
        "claude code" to "claude-code",
        "claude-code" to "claude-code",
        "cloud code" to "claude-code",
        "claud code" to "claude-code",
        "copilot" to "copilot",
        "co-pilot" to "copilot",
        "co pilot" to "copilot",
        "github copilot" to "copilot"
    )

    private val DISPATCH_PREFIXES = listOf("dispatch", "new", "spin up")
    private val TERMINATE_PREFIXES = listOf("terminate", "kill", "shut down")
    private val TARGET_PREFIXES = listOf("switch to", "target")

    fun parse(transcript: String, agents: List<Agent>): Command {
        val normalized = transcript.lowercase().trim()
        if (normalized.isEmpty()) return Command.SendToTarget("")

        // Step 2a: dispatch command
        for (prefix in DISPATCH_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val remainder = normalized.removePrefix(prefix).trim()
                val tool = matchTool(remainder)
                if (tool != null) return Command.Dispatch(tool)
            }
        }

        // Step 2b: terminate command
        for (prefix in TERMINATE_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val remainder = normalized.removePrefix(prefix).trim()
                val agent = matchAgent(remainder, agents)
                if (agent != null) return Command.Terminate(agent.slot)
            }
        }

        // Step 2c: set target command
        for (prefix in TARGET_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val remainder = normalized.removePrefix(prefix).trim()
                val agent = matchAgent(remainder, agents)
                if (agent != null) return Command.SetTarget(agent.slot)
            }
        }

        // Step 3: callsign addressing at utterance start
        for (agent in agents.filter { it.status != "empty" }) {
            val callsign = agent.callsign.lowercase()
            when {
                normalized.startsWith("$callsign, ") -> {
                    val text = transcript.substring("$callsign, ".length).trim()
                    return Command.SendTo(agent.slot, text)
                }
                normalized.startsWith("$callsign ") -> {
                    val text = transcript.substring("$callsign ".length).trim()
                    return Command.SendTo(agent.slot, text)
                }
            }
        }

        // Step 4: default — send to current target
        return Command.SendToTarget(transcript.trim())
    }

    private fun matchTool(text: String): String? {
        val cleaned = text.trim()
        TOOL_ALIASES.forEach { (alias, canonical) ->
            if (cleaned == alias || cleaned.startsWith("$alias ") || cleaned.startsWith("$alias,")) {
                return canonical
            }
        }
        return null
    }

    private fun matchAgent(text: String, agents: List<Agent>): Agent? {
        val cleaned = text.trim()
        return agents.firstOrNull { agent ->
            val callsign = agent.callsign.lowercase()
            cleaned == callsign || cleaned.startsWith("$callsign ") || cleaned.startsWith("$callsign,")
        }
    }
}
