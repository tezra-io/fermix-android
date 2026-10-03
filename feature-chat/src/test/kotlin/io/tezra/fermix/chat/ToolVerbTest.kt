package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Section 13.5's verb map, by the daemon's tool name. */
class ToolVerbTest {
    @Test
    fun `every tool of the verb map has its verb and any other is Working`() {
        val table =
            mapOf(
                "shell" to ToolVerb.RUNNING_SHELL,
                "web_search" to ToolVerb.SEARCHING_WEB,
                "web_fetch" to ToolVerb.READING_PAGE,
                "browser" to ToolVerb.USING_BROWSER,
                "browser_click" to ToolVerb.USING_BROWSER,
                "file_read" to ToolVerb.READING_FILES,
                "file_write" to ToolVerb.EDITING_FILE,
                "file_edit" to ToolVerb.EDITING_FILE,
                "glob_search" to ToolVerb.SEARCHING_FILES,
                "content_search" to ToolVerb.SEARCHING_FILES,
                "memory_recall" to ToolVerb.CHECKING_MEMORY,
                "subagents" to ToolVerb.WORKING_WITH_HELPER,
                "claude_coding_run" to ToolVerb.CODING,
                "generate_image" to ToolVerb.MAKING_IMAGE,
                "schedule_job" to ToolVerb.SCHEDULING,
                "event_create" to ToolVerb.SCHEDULING,
                "computer_use" to ToolVerb.USING_COMPUTER,
                "mcp__linear__create_issue" to ToolVerb.WORKING,
                "" to ToolVerb.WORKING,
                "Shell" to ToolVerb.WORKING,
            )
        table.forEach { (tool, verb) -> assertEquals(verb, toolVerb(tool), tool) }
    }
}
