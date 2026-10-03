package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The slash palette and what the composer sends (design section 13.6). */
class CommandsTest {
    @Test
    fun `the palette opens for a command's word and closes at the first space`() {
        assertEquals("", paletteQuery("/"))
        assertEquals("mo", paletteQuery("/mo"))
        assertNull(paletteQuery("/model x"))
        assertNull(paletteQuery("hello /model"))
    }

    @Test
    fun `the palette filters by name and alias, in the daemon's order`() {
        assertEquals(listOf("new"), paletteCommands(COMMANDS, "n").map { it.name })
        assertEquals(listOf("model"), paletteCommands(COMMANDS, "M").map { it.name })
        assertEquals(COMMANDS, paletteCommands(COMMANDS, ""))
        assertEquals("/model ", pickedCommand(COMMANDS[4]))
    }

    @Test
    fun `a daemon's command goes as a command by its name, anything else as a message, and blank as nothing`() {
        assertEquals(ClientEvent.Command("c1", PROFILE, "model", "opus"), requestOf("/m opus", COMMANDS, "c1", PROFILE))
        assertEquals(ClientEvent.Command("c2", PROFILE, "stop", null), requestOf("/stop", COMMANDS, "c2", PROFILE))
        assertEquals(msg("c3", "/unknown thing"), requestOf("  /unknown thing ", COMMANDS, "c3", PROFILE))
        assertEquals(msg("c4", "Restart the worker"), requestOf("Restart the worker", COMMANDS, "c4", PROFILE))
        assertNull(requestOf("   ", COMMANDS, "c5", PROFILE))
        assertEquals(ClientEvent.Command("c6", PROFILE, "model", "reset"), resetModel("c6", PROFILE))
    }

    @Test
    fun `a command's arguments follow its name after a line as after a space`() {
        val reset = ClientEvent.Command("c1", PROFILE, "model", "reset")
        assertEquals(reset, requestOf("/model\nreset", COMMANDS, "c1", PROFILE))
        assertEquals(
            ClientEvent.Command("c2", PROFILE, "model", "opus"),
            requestOf("/m\topus", COMMANDS, "c2", PROFILE),
        )
        assertEquals(
            ClientEvent.Command("c3", PROFILE, "model", "reset\nnow"),
            requestOf("/model reset\nnow", COMMANDS, "c3", PROFILE),
        )
    }
}
