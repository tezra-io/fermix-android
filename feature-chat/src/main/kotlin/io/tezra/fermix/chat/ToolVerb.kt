package io.tezra.fermix.chat

/** A tool chip's verb (design section 13.5, "Tool chips"), which the screen words from its strings. */
enum class ToolVerb {
    RUNNING_SHELL,
    SEARCHING_WEB,
    READING_PAGE,
    USING_BROWSER,
    READING_FILES,
    EDITING_FILE,
    SEARCHING_FILES,
    CHECKING_MEMORY,
    WORKING_WITH_HELPER,
    CODING,
    MAKING_IMAGE,
    SCHEDULING,
    USING_COMPUTER,
    WORKING,
}

/** The tools the verb map names whole. */
private val BY_NAME: Map<String, ToolVerb> =
    mapOf(
        "shell" to ToolVerb.RUNNING_SHELL,
        "web_search" to ToolVerb.SEARCHING_WEB,
        "web_fetch" to ToolVerb.READING_PAGE,
        "file_read" to ToolVerb.READING_FILES,
        "glob_search" to ToolVerb.SEARCHING_FILES,
        "content_search" to ToolVerb.SEARCHING_FILES,
        "subagents" to ToolVerb.WORKING_WITH_HELPER,
        "generate_image" to ToolVerb.MAKING_IMAGE,
        "schedule_job" to ToolVerb.SCHEDULING,
        "computer_use" to ToolVerb.USING_COMPUTER,
    )

/** The families the verb map names by a part of their names, tried in this order after [BY_NAME]. */
private val BY_FAMILY: List<Pair<(String) -> Boolean, ToolVerb>> =
    listOf(
        { tool: String -> tool.startsWith("browser") } to ToolVerb.USING_BROWSER,
        { tool: String -> tool.startsWith("file_") } to ToolVerb.EDITING_FILE,
        { tool: String -> tool.startsWith("memory_") } to ToolVerb.CHECKING_MEMORY,
        { tool: String -> tool.endsWith("_coding_run") } to ToolVerb.CODING,
        { tool: String -> tool.startsWith("event_") } to ToolVerb.SCHEDULING,
    )

/**
 * Section 13.5's verb map, by the daemon's tool name: `shell` runs a shell; `web_search`, `web_fetch` and
 * `browser*` search the web, read a page and use the browser; `file_read` reads files, any other `file_*`
 * edits one, and `glob_search` and `content_search` search files; `memory_*` checks memory; `subagents`
 * works with a helper and `*_coding_run` codes; `generate_image` makes an image; `schedule_job` and
 * `event_*` schedule; `computer_use` uses the computer. Any other tool is "Working": the chip never shows a
 * tool's own name.
 */
fun toolVerb(tool: String): ToolVerb =
    BY_NAME[tool] ?: BY_FAMILY.firstOrNull { (matches, _) -> matches(tool) }?.second ?: ToolVerb.WORKING
