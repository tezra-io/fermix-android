package io.tezra.fermix.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import java.time.LocalDate

// The chat at the twelve windows of @FermixPreviews, each from fixed state: a read thread scrolled up from two
// unseen answers, then mid-scroll with its date pill, and with an older page on its way; the thinking card
// while the daemon speaks and with a pool phrase; an answer mid-stream; a sealed answer with its code and table
// cards; answers made only of a card, a job's table and a command; the outbox's queued and failed bubbles; the
// error cards; the centred lines and a job's delivery; three messages selected, a job's report, a refused
// message of the owner's and an answer with a link; the empty chat; the composer's six lines and its palette;
// the banners. Each list is scrolled so that what its preview shows is in view at every window.

/** The indicator's clock in every preview; each card's turn started its elapsed time before it. */
private const val NOW_MONO = 100_000L

private val NO_ACTIONS =
    ChatScreenActions(
        onBack = {},
        onInstance = {},
        composer = ComposerActions(onField = {}, onSend = {}, onStop = {}, onPalette = {}),
        onPick = {},
        onClosePalette = {},
        onError = {},
        onOutbox = { _, _ -> },
        list = ListActions(onBottom = {}, onTop = {}, onListed = {}),
        info = { ShownInfo(null, null, null, null, null, null) },
        modelOf = { it.model },
        nowMono = { NOW_MONO },
        text = TextActions(copy = {}, share = {}),
    )

private val TAILNET = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

private val TODAY: LocalDate = MORNING.atZone(UTC).toLocalDate()

private val DAY = ChatItem.Day("day:$TODAY", TODAY)

/** The screen over [items], newest first, with nothing in the composer. */
private fun ui(
    items: List<ChatItem>,
    thinking: Boolean = false,
    link: Link = TAILNET,
    unseen: Int = 0,
    chosenModel: String? = null,
): ChatUi =
    ChatUi(
        state =
            ChatScreenState(
                header = ChatHeader(sample(), link, thinking),
                banner = null,
                items = items,
                name = sample().title,
                turnRuns = thinking,
                commands = COMMANDS,
                newestSeq = items.size.toULong(),
                unseen = unseen,
                older = false,
                chosenModel = chosenModel,
                zone = UTC,
                today = TODAY,
                arrived = emptyList(),
            ),
        field = TextFieldValue(""),
        palette = false,
    )

private fun said(
    key: String,
    sender: Sender,
    text: String,
    minutes: Long,
    position: GroupPosition = GroupPosition.Single,
): ChatItem.Message {
    val delivery = if (sender == Sender.User) Delivery.DELIVERED else Delivery.NONE
    return ChatItem.Message(key, ShownMessage(sender, text, wallAt(minutes), delivery, position))
}

/** The morning's first exchange, oldest last, under its day header. */
private val EARLIER =
    listOf(
        said("r4b", Sender.Agent, "They're under `backups/2026-09-27`.", minutes = 13, GroupPosition.Last),
        said("r4a", Sender.Agent, "Done. 2,184 photos are on the NAS.", minutes = 12, GroupPosition.First),
        said("r3", Sender.User, "Back up the photos folder before you clean anything up.", minutes = 10),
        said("r2", Sender.Agent, "Morning. The nightly export **failed** at 02:14; the rest ran.", minutes = 1),
        said("r1", Sender.User, "Morning! Anything break overnight?", minutes = 0),
        DAY,
    )

private val ASKED = said("r5", Sender.User, "Why did the export fail?", minutes = 39)

/** The evening before, under its own day header: enough above the morning for the thread to scroll. */
private val YESTERDAY =
    listOf(
        said("y2", Sender.Agent, "Scheduled for Friday at 09:00: renew the `fermix.dev` domain.", minutes = -899),
        said("y1", Sender.User, "Remind me to renew the domain on Friday morning.", minutes = -900),
        said("y0", Sender.Agent, "The build is green again: the flaky test now waits for the socket.", minutes = -930),
        said("y-1", Sender.User, "Is the build still red?", minutes = -931),
        ChatItem.Day("day:${TODAY.minusDays(1)}", TODAY.minusDays(1)),
    )

/** A day, in milliseconds. */
private const val DAY_MS = 86_400_000L

/** [this] a day earlier, under its own key. */
private fun ChatItem.Message.aDayEarlier(): ChatItem.Message =
    copy(key = "d$key", message = message.copy(wallMs = message.wallMs?.minus(DAY_MS)))

/** The day before: its evening, then its morning's exchange, under its day header; enough to scroll. */
private val DAY_BEFORE: List<ChatItem> =
    YESTERDAY.dropLast(1) + EARLIER.filterIsInstance<ChatItem.Message>().map { it.aDayEarlier() } + YESTERDAY.last()

/**
 * The read thread: two unseen answers in one group under the divider, the owner's question under today's
 * header, all at the bottom where every window shows them, and the day before above.
 */
private val THREAD =
    listOf(
        said("r7", Sender.Agent, "Raised it to 120 s and re-ran it: it finished in 84 s.", 41, GroupPosition.Last),
        said("r6", Sender.Agent, "It timed out at 60 s, the job's default.", 40, GroupPosition.First),
        ChatItem.Unread,
        ASKED,
        DAY,
    ) + DAY_BEFORE

@FermixPreviews
@Composable
fun ThreadPreview() {
    // Scrolled up a little, so the pill shows and counts the two unseen while their group stays in view.
    val offset = with(LocalDensity.current) { THREAD_SCROLL.roundToPx() }
    FermixPreviewTheme {
        ChatScreen(ui(THREAD, unseen = 2), NO_ACTIONS, rememberLazyListState(0, offset))
    }
}

/**
 * How far ThreadPreview is scrolled up from the bottom: the list's 12 dp bottom padding, so the list is off its
 * bottom and the newest answer, its time included, stands whole on the dock.
 */
private val THREAD_SCROLL = 12.dp

@FermixPreviews
@Composable
fun FastScrollPreview() {
    // Mid-scroll: the date pill names the day of the topmost item in view.
    FermixPreviewTheme {
        ChatScreen(ui(THREAD, unseen = 2), NO_ACTIONS, rememberLazyListState(1), PreviewHeld(datePill = true))
    }
}

@FermixPreviews
@Composable
fun OlderPreview() {
    // The older page on its way: the skeleton above the oldest row held, the list scrolled to its top.
    val items = listOf(ASKED) + EARLIER + ChatItem.Older
    FermixPreviewTheme { ChatScreen(ui(items), NO_ACTIONS, rememberLazyListState(items.lastIndex)) }
}

private fun card(
    headings: List<String>,
    chips: List<LiveChip>,
    elapsedMs: Long,
): ChatItem.Thinking =
    ChatItem.Thinking(
        key = "card:t6",
        turnId = "t6",
        card =
            LiveCard(
                shownMono = NOW_MONO - elapsedMs,
                headings = headings,
                chips = chips,
                speaking = headings.isNotEmpty() || chips.any { it.running },
            ),
        startedMono = NOW_MONO - elapsedMs,
        seed = 7L,
    )

@FermixPreviews
@Composable
fun ThinkingPreview() {
    val thinking =
        card(
            headings = listOf("Reading the export job's config", "Checking last night's log"),
            chips =
                listOf(
                    LiveChip("file_read", running = false, status = "ok"),
                    LiveChip("content_search", running = false, status = "ok"),
                    LiveChip("shell", running = true, status = null),
                ),
            elapsedMs = 12_000L,
        )
    FermixPreviewTheme { ChatScreen(ui(listOf(thinking, ASKED) + EARLIER, thinking = true), NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun PhrasePreview() {
    // 20 s in, past the opening "Thinking", with nothing from the daemon: a phrase from the first pool.
    val thinking = card(headings = emptyList(), chips = emptyList(), elapsedMs = 20_000L)
    FermixPreviewTheme { ChatScreen(ui(listOf(thinking, ASKED) + EARLIER, thinking = true), NO_ACTIONS) }
}

private const val STREAMING =
    "Here's a retry around the upload, three tries with a pause between:\n\n" +
        "```kotlin\nsuspend fun upload(file: File) {\n    repeat(3) { attempt ->"

@FermixPreviews
@Composable
fun StreamingPreview() {
    val answer =
        ChatItem.Message(
            "turn:t6:0",
            ShownMessage(Sender.Agent, STREAMING, wallMs = null, delivery = Delivery.NONE, streaming = true),
        )
    FermixPreviewTheme { ChatScreen(ui(listOf(answer, ASKED) + EARLIER, thinking = true), NO_ACTIONS) }
}

private val TWENTY_LINES =
    listOf(
        "suspend fun export(job: Job, timeout: Duration = 120.seconds): Report {",
        "    val started = clock.now()",
        "    val rows = mutableListOf<Row>()",
        "    withTimeout(timeout) {",
        "        source.pages(job.query).collect { page ->",
        "            rows += page.rows",
        "            progress.report(rows.size)",
        "        }",
        "    }",
        "    val file = exports.resolve(\"${'$'}{job.name}.csv\")",
        "    file.bufferedWriter().use { out ->",
        "        out.write(job.header)",
        "        rows.forEach { out.write(it.csv()) }",
        "    }",
        "    return Report(",
        "        rows = rows.size,",
        "        took = clock.now() - started,",
        "        file = file,",
        "    )",
        "}",
    ).joinToString("\n")

private const val TABLE =
    "| Job | Last run | Took | Rows | Status |\n" +
        "|---|---|---|---|---|\n" +
        "| nightly-export | 02:14 | 60 s | 0 | timed out |\n" +
        "| photo-backup | 09:12 | 41 s | 2184 | ok |\n" +
        "| log-rotate | 03:00 | 2 s | 14 | ok |\n"

@FermixPreviews
@Composable
fun SealedPreview() {
    // Scrolled to the question's top, so the code card's head and its first lines are in view at every window.
    val text = "The export now reads in pages and writes as it goes:\n\n```kotlin\n$TWENTY_LINES\n```\n\n$TABLE"
    val items = listOf(said("r6", Sender.Agent, text, minutes = 42), ASKED)
    FermixPreviewTheme { ChatScreen(ui(items), NO_ACTIONS, rememberLazyListState(items.lastIndex)) }
}

@FermixPreviews
@Composable
fun CardsPreview() {
    // Answers made only of a card: a command's fence, and a job's table, which wears its tag and the time.
    val command = said("r6", Sender.Agent, "```sh\nsystemctl --user restart fermix-export\n```", minutes = 42)
    FermixPreviewTheme { ChatScreen(ui(listOf(REPORT, command, ASKED) + EARLIER), NO_ACTIONS) }
}

/** A job's report, only a table: its tag above the card and its time below it lie on the canvas. */
private val REPORT =
    ChatItem.Message("r7", ShownMessage(Sender.Agent, TABLE, wallAt(60), Delivery.NONE, job = "nightly-export"))

@FermixPreviews
@Composable
fun OutboxPreview() {
    val running = card(headings = emptyList(), chips = emptyList(), elapsedMs = 3_000L)
    val queued =
        ChatItem.Message(
            "out:m9",
            ShownMessage(Sender.User, "And check the disk too.", wallAt(40), Delivery.QUEUED, editable = true),
        )
    val notSent =
        ShownError(ErrorLine.NOT_SENT, ErrorAction.RETRY_SENDING, Sender.User, "rate_limited", msg("m10", ""))
    val refused = ChatItem.Error("refused:m10", notSent)
    val items = listOf(refused, FAILED, queued, running, ASKED) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items, thinking = true), NO_ACTIONS) }
}

/** The owner's message the host refused: "Not sent. Tap to retry sending." under it, on the canvas. */
private val FAILED =
    ChatItem.Message(
        "out:m10",
        ShownMessage(Sender.User, "Then empty the trash.", wallAt(41), Delivery.FAILED, request = msg("m10", "")),
    )

@FermixPreviews
@Composable
fun ErrorsPreview() {
    val items =
        listOf(
            ChatItem.Error("end:t7", errorOf(MODEL_UNAVAILABLE, null)),
            said("r6", Sender.User, "Summarise the export log.", minutes = 41),
            ChatItem.Error("end:t6", errorOf("turn_failed", msg("m6", "Summarise the export log."))),
            ASKED,
        ) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items, chosenModel = "Claude Opus 5.5"), NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun LinesPreview() {
    val delivery =
        ChatItem.Message(
            "r6",
            ShownMessage(
                Sender.Agent,
                "Exported 1,284 rows to `exports/2026-09-27.csv`.",
                wallAt(45),
                Delivery.NONE,
                job = "nightly-export",
            ),
        )
    val items =
        listOf(
            delivery,
            ChatItem.Pill("pill:2", PillText.Switched("Claude Opus 5.5")),
            ChatItem.Pill(
                "pill:1",
                PillText.Notice("Compacted the conversation to keep it within the model's window."),
            ),
            ASKED,
        ) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items), NO_ACTIONS) }
}

/** An answer with a link (the M51 update's 1.3: the ink, always underlined), under the owner's question. */
private val LINKED =
    said(
        "r6",
        Sender.Agent,
        "The guide is [Task timeouts](https://hexdocs.pm/elixir/Task.html); the export uses its default.",
        minutes = 41,
    )

@FermixPreviews
@Composable
fun SelectingPreview() {
    // Multi-select over the answer, a refused message of the owner's and a job's report: each row washed in the
    // selection across the column, the owner's bubble, the ink itself, kept as it is with the wash beside it; the
    // answer's link in the ink; and what a row draws on its canvas, the refusal's line, the job's tag and the card's
    // time, in the ink while it lies on the wash. The owner's question above stays unselected.
    val items = listOf(REPORT, FAILED, LINKED, ASKED) + EARLIER
    val held = PreviewHeld(selected = setOf(REPORT.key, FAILED.key, LINKED.key))
    FermixPreviewTheme { ChatScreen(ui(items), NO_ACTIONS, held = held) }
}

@FermixPreviews
@Composable
fun EmptyPreview() {
    FermixPreviewTheme { ChatScreen(ui(emptyList()), NO_ACTIONS) }
}

private val SEVEN_LINES =
    listOf(
        "Before tonight's run:",
        "1. raise the export timeout to 120 s",
        "2. write the rows as they come",
        "3. keep the last 7 exports",
        "4. delete anything older",
        "5. tell me what you removed",
        "6. and re-run it once now",
    ).joinToString("\n")

@FermixPreviews
@Composable
fun DraftPreview() {
    val draft = ui(EARLIER).copy(field = TextFieldValue(SEVEN_LINES, TextRange(SEVEN_LINES.length)))
    FermixPreviewTheme { ChatScreen(draft, NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun PalettePreview() {
    val palette = ui(EARLIER).copy(field = TextFieldValue("/", TextRange(1)), palette = true)
    FermixPreviewTheme { ChatScreen(palette, NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun BannersPreview() {
    FermixPreviewTheme {
        Column {
            ChatBar(
                ChatHeader(sample(), Link.WaitingForNetwork, thinking = false),
                onBack = {},
                onTitle = {},
                onSearch = {},
            )
            BannerLine(Banner.OFFLINE, HOST, onUnreachable = {})
            Spacer(modifier = Modifier.height(24.dp))
            ChatBar(ChatHeader(sample(), Link.CannotReach, thinking = false), onBack = {}, onTitle = {}, onSearch = {})
            BannerLine(Banner.UNREACHABLE, HOST, onUnreachable = {})
        }
    }
}
