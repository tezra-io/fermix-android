package io.tezra.fermix.demo

import kotlin.random.Random

/** The most of the owner's words an answer quotes or an approval's card names, in UTF-16 units. */
private const val QUOTED_CHARS = 80

/** One reply in [TOOL_ODDS] runs a tool on its card, and one in [REACTION_ODDS] reacts to the owner's message. */
private const val TOOL_ODDS = 2
private const val REACTION_ODDS = 3

/** Words in the owner's message that ask for something only the owner may allow: the reply asks first. */
private val ASKS_FIRST = Regex("\\b(restart|delete|remove|install|deploy)\\b", RegexOption.IGNORE_CASE)

/** The tools a reply's card may run (feature-chat's previews' chips). */
private val TOOLS = listOf("file_read", "content_search", "shell")

/** The link a reply names, and its preview's words. */
internal const val GUIDE_URL = "https://hexdocs.pm/elixir/Task.html"

/** What the agent says as the turn that raised an approval ends, before the owner answers it. */
internal const val ASKED = "That needs your OK first, so I've asked for it."

/**
 * How a reply plays (design section 8.2): its card's headings, the tool it runs, the approval it raises before it
 * can go on, its words, and whether the agent reacts to the owner's message. A reply that asks ends with [ASKED],
 * and its [answer] is the words of the turn that runs once the owner grants it.
 */
internal class ReplyPlan(
    val headings: List<String>,
    val tool: String?,
    val ask: AskCard?,
    val answer: String,
    val react: Boolean,
)

/**
 * The reply to the owner's [words], [media] when they sent a photo or a file, chosen by [random], the Fermix's
 * seeded one, so that two runs of the demo answer the same messages alike. The answers show what the chat draws:
 * a list with bold and inline code, a fenced command, a table, a quote of the owner's words and a link.
 */
internal fun replyPlan(
    random: Random,
    requestId: String,
    words: String,
    media: Boolean,
): ReplyPlan {
    val quoted = quoteOf(words).ifBlank { "your message" }
    val answers = answers(quoted)
    val answer = if (media) "Got it: it's in `uploads/` on the computer." else answers[random.nextInt(answers.size)]
    val tool = if (random.nextInt(TOOL_ODDS) == 0) TOOLS[random.nextInt(TOOLS.size)] else null
    val asks = ASKS_FIRST.find(words)?.value?.lowercase()
    val card = asks?.let { verb -> askCard(requestId, quoted, verb) }
    return ReplyPlan(
        headings = listOf("Reading what you asked", "Writing the answer"),
        tool = tool,
        ask = card,
        answer = if (card != null) "Done: “$quoted”." else answer,
        react = random.nextInt(REACTION_ODDS) == 0,
    )
}

/** The sandbox card a reply raises for [verb], the owner's word that asks for it, naming what was [quoted]. */
private fun askCard(
    requestId: String,
    quoted: String,
    verb: String,
): AskCard = AskCard("sandbox-$requestId", "sandbox", "Allow this: “$quoted”?", quoted, "commands + $verb")

/** The first [QUOTED_CHARS] units of [words], never ending on the first half of a surrogate pair. */
internal fun quoteOf(words: String): String {
    if (words.length <= QUOTED_CHARS) return words
    val cut = if (words[QUOTED_CHARS - 1].isHighSurrogate()) QUOTED_CHARS - 1 else QUOTED_CHARS
    return words.take(cut)
}

internal fun answers(quoted: String): List<String> =
    listOf(
        "Here's what I found:\n\n- the export took **41 s**\n- it read 1,284 rows\n" +
            "- it wrote them to `exports/nightly.csv`\n\nNothing else needs you.",
        "A quick way to check is:\n\n```sh\njournalctl --user -u fermix-export --since today\n```\n\n" +
            "It prints only today's lines.",
        "| Step | Took |\n|---|---|\n| read | 41 s |\n| write | 12 s |\n| upload | 31 s |\n\n" +
            "The upload is the slow part.",
        "> $quoted\n\nNoted. I'll keep that in mind for the next run.",
        "The [Task guide]($GUIDE_URL) says an awaited task waits five seconds unless told otherwise; " +
            "the export now passes 120 s.",
    )
