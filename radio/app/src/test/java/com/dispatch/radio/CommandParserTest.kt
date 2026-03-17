package com.dispatch.radio

import com.dispatch.radio.model.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val agents = listOf(
        Agent(slot = 1, callsign = "Alpha", tool = "claude-code", status = "busy", task = "bd-a1"),
        Agent(slot = 2, callsign = "Bravo", tool = "claude-code", status = "idle", task = null),
        Agent(slot = 3, callsign = "Charlie", tool = "copilot", status = "idle", task = null),
    )

    @Test
    fun `dispatch claude code`() {
        val cmd = CommandParser.parse("dispatch claude code", agents)
        assertEquals(Command.Dispatch("claude-code"), cmd)
    }

    @Test
    fun `new copilot`() {
        val cmd = CommandParser.parse("new copilot", agents)
        assertEquals(Command.Dispatch("copilot"), cmd)
    }

    @Test
    fun `spin up claude code`() {
        val cmd = CommandParser.parse("spin up claude code", agents)
        assertEquals(Command.Dispatch("claude-code"), cmd)
    }

    @Test
    fun `fuzzy alias cloud code`() {
        val cmd = CommandParser.parse("dispatch cloud code", agents)
        assertEquals(Command.Dispatch("claude-code"), cmd)
    }

    @Test
    fun `terminate alpha`() {
        val cmd = CommandParser.parse("terminate alpha", agents)
        assertEquals(Command.Terminate(1), cmd)
    }

    @Test
    fun `kill bravo`() {
        val cmd = CommandParser.parse("kill bravo", agents)
        assertEquals(Command.Terminate(2), cmd)
    }

    @Test
    fun `shut down charlie`() {
        val cmd = CommandParser.parse("shut down charlie", agents)
        assertEquals(Command.Terminate(3), cmd)
    }

    @Test
    fun `switch to bravo`() {
        val cmd = CommandParser.parse("switch to bravo", agents)
        assertEquals(Command.SetTarget(2), cmd)
    }

    @Test
    fun `target charlie`() {
        val cmd = CommandParser.parse("target charlie", agents)
        assertEquals(Command.SetTarget(3), cmd)
    }

    @Test
    fun `alpha with comma addressing`() {
        val cmd = CommandParser.parse("Alpha, can you refactor the auth module", agents)
        assertTrue(cmd is Command.SendTo)
        cmd as Command.SendTo
        assertEquals(1, cmd.slot)
        assertEquals("can you refactor the auth module", cmd.text)
    }

    @Test
    fun `alpha without comma addressing`() {
        val cmd = CommandParser.parse("Alpha refactor the auth module", agents)
        assertTrue(cmd is Command.SendTo)
        cmd as Command.SendTo
        assertEquals(1, cmd.slot)
        assertEquals("refactor the auth module", cmd.text)
    }

    @Test
    fun `bravo with comma addressing`() {
        val cmd = CommandParser.parse("Bravo, write tests for the payment module", agents)
        assertTrue(cmd is Command.SendTo)
        cmd as Command.SendTo
        assertEquals(2, cmd.slot)
        assertEquals("write tests for the payment module", cmd.text)
    }

    @Test
    fun `default send to target for unmatched prompt`() {
        val cmd = CommandParser.parse("refactor the database layer", agents)
        assertEquals(Command.SendToTarget("refactor the database layer"), cmd)
    }

    @Test
    fun `empty transcript returns SendToTarget with empty string`() {
        val cmd = CommandParser.parse("", agents)
        assertEquals(Command.SendToTarget(""), cmd)
    }

    @Test
    fun `prompt starting with alpha but not an agent address`() {
        // "alpha" without a space after is not treated as addressing
        val cmd = CommandParser.parse("alphabetically sort this list", agents)
        assertEquals(Command.SendToTarget("alphabetically sort this list"), cmd)
    }
}
