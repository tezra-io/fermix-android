package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelRef
import io.tezra.fermix.protocol.ModelState
import io.tezra.fermix.transport.Candidate
import java.time.LocalDate

// The chat's cards and controls at the twelve windows of @FermixPreviews, each from fixed state: an approval
// card at 42 s and at 8 s, in the warn colour; its four receipt lines; the owner's bubble with the host's
// reaction; a message with two link previews, one with its thumbnail; the composer with the model chip at the
// default, with an override, and disabled offline; the "Model" sheet; the model lines; search in its list, with
// an older page that did not come, offline under its pinned line, and in the chat with a hit pulsing, the
// agent's and the owner's.

/** The clock every card in these previews reads. */
private const val CLOCK = 500_000L

private val TODAY: LocalDate = MORNING.atZone(UTC).toLocalDate()

private val CONNECTED = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

private val ASTRA = ModelRef("codex", "gpt-6-astra", "GPT-6 Astra")

/** The canon's thumbnail, its three colours on the diagonal. */
private fun thumbnail(): ImageBitmap {
    val image = ImageBitmap(320, 180)
    val colors = listOf(Color(0xFF5C8BA3), Color(0xFF8C7093), Color(0xFFAD7F6B))
    val paint = Paint().apply { shader = LinearGradientShader(Offset.Zero, Offset(320f, 180f), colors) }
    Canvas(image).drawRect(0f, 0f, 320f, 180f, paint)
    return image
}

private val ACTIONS =
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
        nowMono = { CLOCK },
        text = TextActions(copy = {}, share = {}),
        cards = CardActions(thumbnail = { thumbnail() }),
    )

/** The screen over [items], newest first, the daemon on GPT-6 Astra, connected unless [link] says otherwise. */
private fun screen(
    items: List<ChatItem>,
    link: Link = CONNECTED,
    model: ModelChip? = ModelChip("GPT-6 Astra", "C", overridden = false, enabled = link is Link.Up),
): ChatUi {
    val record = sample().let { it.copy(caps = it.caps?.copy(modelState = ModelState(ASTRA))) }
    return ChatUi(
        state =
            ChatScreenState(
                header = ChatHeader(record, link, thinking = false),
                banner = null,
                items = items,
                name = record.title,
                turnRuns = false,
                commands = COMMANDS,
                newestSeq = items.size.toULong(),
                unseen = 0,
                older = false,
                chosenModel = null,
                zone = UTC,
                today = TODAY,
                arrived = emptyList(),
                model = model,
            ),
        field = TextFieldValue(""),
        palette = false,
    )
}

private fun said(
    key: String,
    sender: Sender,
    text: String,
    minutes: Long,
    seq: Int? = null,
): ChatItem.Message {
    val delivery = if (sender == Sender.User) Delivery.DELIVERED else Delivery.NONE
    return ChatItem.Message(key, ShownMessage(sender, text, wallAt(minutes), delivery, seq = seq?.toULong()))
}

private val ASKED = said("r1", Sender.User, "Clean up the old exports in ~/Documents.", minutes = 30)

private val DAY = ChatItem.Day("day:$TODAY", TODAY)

/** A sandbox card with [left] seconds of its minute. */
private fun approval(
    key: String,
    left: Int,
    receipt: Receipt? = null,
) = ChatItem.Approval(
    "approval:$key",
    ShownApproval(
        key,
        CardKind.SANDBOX,
        "sandbox",
        "Allow reading ~/Documents?",
        "~/Documents/**",
        60,
        CLOCK + left * 1_000L,
        false,
        receipt,
    ),
)

@FermixPreviews
@Composable
fun ApprovalPreview() {
    FermixPreviewTheme { ChatScreen(screen(listOf(approval("ap-1", left = 42), ASKED, DAY)), ACTIONS) }
}

@FermixPreviews
@Composable
fun ApprovalWarningPreview() {
    FermixPreviewTheme { ChatScreen(screen(listOf(approval("ap-1", left = 8), ASKED, DAY)), ACTIONS) }
}

@FermixPreviews
@Composable
fun ReceiptsPreview() {
    val items =
        listOf(
            approval("ap-4", left = 60, Receipt.CLOSED),
            approval("ap-3", left = 0),
            approval("ap-2", left = 60, Receipt.DENIED),
            approval("ap-1", left = 60, Receipt.APPROVED),
            ASKED,
            DAY,
        )
    FermixPreviewTheme { ChatScreen(screen(items), ACTIONS) }
}

private val HEXDOCS =
    LinkPreviewCard(
        "https://hexdocs.pm/elixir/Task.html",
        "hexdocs.pm",
        "Task — Elixir",
        "Conveniences for spawning and awaiting tasks. Tasks are processes meant to execute one particular action.",
        imageRef = "ab".repeat(32),
    )

// A site label longer than the card is wide at 200 % type in the compact window, which ends it in "…".
private val GUIDE =
    LinkPreviewCard(
        "https://elixir-lang.org/getting-started/processes.html",
        "The Elixir Programming Language",
        "Processes — Elixir",
        "In Elixir, all code runs inside processes.",
    )

@FermixPreviews
@Composable
fun ReactionAndPreviewsPreview() {
    val reacted = said("r2", Sender.User, "Is there a guide for Task timeouts?", minutes = 44)
    val answer = said("r3", Sender.Agent, "Yes: hexdocs.pm/elixir/Task.html, and the guide on processes.", minutes = 44)
    val items =
        listOf(
            answer.copy(message = answer.message.copy(previews = listOf(HEXDOCS, GUIDE))),
            reacted.copy(message = reacted.message.copy(reaction = "👀")),
            DAY,
        )
    FermixPreviewTheme { ChatScreen(screen(items), ACTIONS) }
}

/**
 * The vendored fixture's own case: the host's 👍 and two link previews on the owner's message, one title alone.
 * The second leaves out the fixture's thumbnail, which at 200 % type would push the reacted bubble off the
 * expanded window; ReactionAndPreviewsPreview draws thumbnails.
 */
private val FIXTURE_PREVIEWS =
    listOf(
        LinkPreviewCard("https://example.com", "Example", "Example page"),
        LinkPreviewCard("https://example.org/post", "Example Org", "A post", "What the page says about itself."),
    )

@FermixPreviews
@Composable
fun ReactionOverPreviewsPreview() {
    // The reacted message the newest, so the chip and its clearance over the first card show in every window.
    val reacted = said("r2", Sender.User, "Two links for the notes.", minutes = 44)
    val items =
        listOf(
            reacted.copy(message = reacted.message.copy(reaction = "👍", previews = FIXTURE_PREVIEWS)),
            ASKED,
            DAY,
        )
    FermixPreviewTheme { ChatScreen(screen(items), ACTIONS) }
}

@FermixPreviews
@Composable
fun ModelChipPreview() {
    // The chip at the default, at 70 %; on the chat's own model, tonal with its dot, a pick waiting for the
    // turn; and with no connection, disabled, the line above saying why.
    val default = ModelChip("GPT-6 Astra", "C", overridden = false, enabled = true)
    val own = ModelChip("Claude Opus 5.5", "A", overridden = true, enabled = true)
    val off = default.copy(enabled = false)
    val field = TextFieldValue("")
    // In the 640 dp column, as the dock draws the composer (FermixColumn).
    FermixPreviewTheme {
        Box(modifier = Modifier.fillMaxSize().background(LocalFermixColors.current.canvas).padding(top = 24.dp)) {
            FermixColumn(ColumnWidth.Wide) {
                Column {
                    Composer(field, ComposerLook(HOST, stops = false, chip = default), ACTIONS.composer, false)
                    ComposerHint(own, switchPending = true)
                    Composer(field, ComposerLook(HOST, stops = true, chip = own), ACTIONS.composer, false)
                    ComposerHint(off, switchPending = false)
                    Composer(field, ComposerLook(HOST, stops = false, chip = off), ACTIONS.composer, false)
                }
            }
        }
    }
}

@FermixPreviews
@Composable
fun TooLongPreview() {
    // A field past what one message carries: the line above says Send sends nothing, and the field keeps its words.
    val chip = ModelChip("GPT-6 Astra", "C", overridden = false, enabled = true)
    val field = TextFieldValue("The ingest worker's log, pasted whole: ".repeat(120))
    FermixPreviewTheme {
        Box(modifier = Modifier.fillMaxSize().background(LocalFermixColors.current.canvas).padding(top = 24.dp)) {
            FermixColumn(ColumnWidth.Wide) {
                Column {
                    ComposerHint(chip, switchPending = false, tooLong = true)
                    Composer(field, ComposerLook(HOST, stops = false, chip = chip), ACTIONS.composer, false)
                }
            }
        }
    }
}

@FermixPreviews
@Composable
fun ModelSheetPreview() {
    val entries =
        listOf(
            ModelEntry("codex", "gpt-6-sol", "GPT-6 Sol"),
            ModelEntry("codex", "gpt-6-luna", "GPT-6 Luna", trait = "fast, cheaper"),
            ModelEntry(
                "anthropic",
                "claude-opus-5.5",
                "Claude Opus 5.5",
                "best quality",
                streams = false,
                active = true,
            ),
            ModelEntry("anthropic", "claude-haiku-4.5", "Claude Haiku 4.5", "fastest", streams = false),
            ModelEntry("ollama", listingUnavailable = true),
        )
    val own = ModelChip("Claude Opus 5.5", "A", overridden = true, enabled = true)
    // At most 640 dp, centred, as Material's ModalBottomSheet caps itself (BottomSheetDefaults.SheetMaxWidth).
    FermixPreviewTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ColumnWidth.Wide.width)
                        .fillMaxSize()
                        .background(LocalFermixColors.current.tonalSolid, FermixShapes.sheet)
                        .padding(top = 24.dp),
            ) {
                ModelSheetContent(ModelSheet.Listed(entries), screen(emptyList(), model = own).state, ModelActions())
            }
        }
    }
}

@FermixPreviews
@Composable
fun ModelLinesPreview() {
    val items =
        listOf(
            ChatItem.Pill("pill:3", PillText.BackToDefault("GPT-6 Astra")),
            said("r2", Sender.User, "Summarise the recording.", minutes = 41),
            ChatItem.Pill("pill:2", PillText.Note("Computer History is off for this model")),
            ChatItem.Pill("pill:1", PillText.Switched("Claude Opus 5.5")),
            ASKED,
            DAY,
        )
    FermixPreviewTheme { ChatScreen(screen(items), ACTIONS) }
}

/** A hit on row [seq] with "timeout" marked wherever it stands in [excerpt]. */
private fun hit(
    seq: Int,
    sender: Sender,
    minutes: Long,
    excerpt: String,
) = ShownHit(seq.toULong(), sender, wallAt(minutes), excerpt, marksOf(excerpt, listOf("timeout")))

/** The canon's hits for "timeout", newest first. */
private val HITS =
    listOf(
        hit(
            9,
            Sender.Agent,
            41,
            "I raised the timeout to 300 s and re-ran it. The report is in reports/2026-09-27.pdf.",
        ),
        hit(7, Sender.Agent, 13, "report_export timed out after 120 s… the timeout was the default."),
        hit(5, Sender.User, -4 * 24 * 60, "Is there a guide for Task timeouts?"),
        hit(3, Sender.Agent, -15 * 24 * 60, "Raised the connect timeout for the staging database to 15 s."),
    )

private val SEARCHED = SearchUi(query = "timeout", hits = HITS)

@FermixPreviews
@Composable
fun SearchListPreview() {
    FermixPreviewTheme { ChatScreen(screen(listOf(ASKED, DAY)).copy(search = SEARCHED), ACTIONS) }
}

@FermixPreviews
@Composable
fun SearchFailedPreview() {
    // The older page did not come: under the hits, the line saying so and its "Try again", underlined.
    FermixPreviewTheme { ChatScreen(screen(listOf(ASKED, DAY)).copy(search = SEARCHED.copy(failed = true)), ACTIONS) }
}

@FermixPreviews
@Composable
fun SearchOfflinePreview() {
    val offline =
        screen(
            listOf(ASKED, DAY),
            link = Link.WaitingForNetwork,
        ).copy(search = SEARCHED.copy(cachedOnly = true))
    FermixPreviewTheme { ChatScreen(offline, ACTIONS) }
}

/** The chat search steps through: the canon's thread, the owner's question about timeouts the newest. */
private val STEPPED =
    listOf(
        said("r10", Sender.User, "Is there a guide for Task timeouts?", minutes = 44, seq = 10),
        said(
            "r9",
            Sender.Agent,
            "I raised the timeout to 300 s and re-ran it. The report is in reports/2026-09-27.pdf.",
            minutes = 41,
            seq = 9,
        ),
        said(
            "r8",
            Sender.Agent,
            "The nightly-report job stopped at the export step: report_export timed out after 120 s.",
            minutes = 13,
            seq = 8,
        ),
        said("r7", Sender.User, "Why did last night's report job fail?", minutes = 12, seq = 7),
        DAY,
    )

@FermixPreviews
@Composable
fun SearchInChatPreview() {
    // Stepping: the newest of four hits, its bubble pulsing in the chat.
    val stepping = SEARCHED.copy(mode = SearchMode.IN_CHAT, step = 0)
    FermixPreviewTheme {
        val held = PreviewHeld(pulsing = Jump(9uL, 1))
        ChatScreen(screen(STEPPED).copy(search = stepping), ACTIONS, rememberLazyListState(), held)
    }
}

@FermixPreviews
@Composable
fun SearchInChatOwnPreview() {
    // Stepping to the owner's own words: the query washed in the bubble's ink, the ring round its 6 dp corner.
    val own = hit(10, Sender.User, 44, "Is there a guide for Task timeouts?")
    val stepping = SEARCHED.copy(hits = listOf(own) + HITS, mode = SearchMode.IN_CHAT, step = 0)
    FermixPreviewTheme {
        val held = PreviewHeld(pulsing = Jump(10uL, 1))
        ChatScreen(screen(STEPPED).copy(search = stepping), ACTIONS, rememberLazyListState(), held)
    }
}
