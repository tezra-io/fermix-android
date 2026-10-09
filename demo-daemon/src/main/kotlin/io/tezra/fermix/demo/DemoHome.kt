package io.tezra.fermix.demo

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MessageKind
import io.tezra.fermix.protocol.ModelRef
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * How a request the daemon took stands: `request_status`'s answer, and a duplicate's `accepted`; and the phone
 * that sent it, by its Noise key in hex ([phone], none for the script's own), whose unpairing stops it.
 */
internal class Claim(
    val text: String,
    var state: RequestState,
    val phone: String?,
    var resultSeq: ULong? = null,
    var error: String? = null,
)

/**
 * One demo Fermix as its daemon keeps it for as long as the app's process runs, the same across every connection
 * the phone makes to it, so a reconnect finds its rows, its cursors and what is under way where they were: its
 * timeline, numbered from 1 by a counter that never goes back; the read frontier; the rows changed in place (a
 * reaction) with their mutation counter; the phones paired with it and those it forgot; each request it took; the
 * approvals waiting, the turns running and the connections open; its pairing windows; and the chat's model, which
 * a restarted app's new run of the demo does not know (it starts at the config's again).
 * Only the daemon's confined dispatcher touches it, one coroutine at a time.
 */
internal class DemoHome(
    val fermix: DemoFermix,
    private val blobs: Map<DemoBlobName, DemoBlob>,
    val random: Random,
    private val startedAtMs: Long,
) {
    private val kept = mutableListOf<HistoryMessage>()

    /** The newest row's seq: the head every `hello_ack` names. */
    var head: ULong = 0uL
        private set

    var readFrontier: ULong = 0uL
        private set

    val mutations = mutableListOf<MutationRow>()

    /** Phones paired with this Fermix, by their Noise key in hex, each with its device id. */
    val devices = mutableMapOf<String, String>()

    /** Phones that unpaired: their key is refused from then on (`4004`). */
    val forgotten = mutableSetOf<String>()

    /** The mutation counter: the newest change made in place, `hello_ack.mutation_head_seq`. */
    var mutationHead: ULong = 0uL

    val claims = mutableMapOf<String, Claim>()
    val approvals = mutableMapOf<String, Approval>()

    /** The turns running, by the request each answers. */
    val turns = mutableMapOf<String, Job>()
    val connections = mutableSetOf<DemoConnection>()

    /** The pairing windows opened in this run, and the one open now, which pairs once, since [windowOpenedAtMs]. */
    var windowsOpened = 0
        private set
    var openWindow: Int? = null
    var windowOpenedAtMs = 0L
        private set

    /** The requests a grant ran again in this run (DemoTurns.resume), which numbers the next. */
    var resumes = 0

    /** The chat's own model, when the owner picked one ([DemoModels]). */
    var model: ModelRef? = null

    /** Whether a phone has said `hello` since the demo started: what was under way begins then (DemoSession). */
    var begun = false

    /** How many sessions have said `hello`: each `hello_ack`'s session id. */
    var sessions = 0

    /** The script's last row: a phone that holds it paired in an earlier run of the demo (DemoUnderWay). */
    val scriptedHead: ULong

    init {
        fermix.script.rows.forEach { write(it.role, it.text, timeOf(it.minutesAgo), it.clientMsgId, it) }
        scriptedHead = head
        readFrontier = head - fermix.script.unread.toULong()
    }

    val rows: List<HistoryMessage> get() = kept

    /** The window open at [nowMs] for a life of [lifeMs]: none once one paired, or once its life passed. */
    fun windowAt(
        nowMs: Long,
        lifeMs: Long,
    ): Int? = openWindow?.takeIf { nowMs - windowOpenedAtMs <= lifeMs }

    /** Opens a new pairing window at [atMs] and returns its number: its secret is [DemoFermix.secret] of it. */
    fun openWindow(atMs: Long): Int {
        val window = windowsOpened++
        openWindow = window
        windowOpenedAtMs = atMs
        return window
    }

    /** The demo's start, less [minutesAgo]: a scripted row's time, in RFC 3339 UTC. */
    fun timeOf(minutesAgo: Long): String = stamp(startedAtMs - minutesAgo * MILLIS_PER_MINUTE)

    /** A row written now, at the next seq; it goes to the phones as the caller sends it. */
    fun write(
        role: String,
        text: String,
        ts: String,
        clientMsgId: String? = null,
        script: ScriptRow? = null,
    ): HistoryMessage {
        val seq = head + 1uL
        val media = script?.media.orEmpty().map { checkNotNull(blobs[it]) { "no blob $it" }.mediaRef() }
        val previews = script?.preview?.let { listOf(previewCard(it, blobs)) }
        val row =
            HistoryMessage(
                serverSeq = seq,
                role = role,
                content = text,
                ts = ts,
                mediaRefs = media,
                kind = if (media.isEmpty()) MessageKind.TEXT else MessageKind.MEDIA,
                clientMsgId = clientMsgId,
                metadata = script?.metadata,
                linkPreviews = previews,
            )
        kept += row
        head = seq
        return row
    }

    /** A row written with what is already made of it, a turn's answer or the owner's message with its media. */
    fun write(row: HistoryMessage): HistoryMessage {
        val placed = row.copy(serverSeq = head + 1uL)
        kept += placed
        head = placed.serverSeq
        return placed
    }

    /**
     * Row [serverSeq]'s metadata changed in place (a reaction): kept on the row, which every later page carries, and
     * in the mutation feed under the next mutation seq (design section 7, `mutation_seq`).
     */
    fun change(
        serverSeq: ULong,
        metadata: JsonObject,
    ) {
        val at = kept.indexOfFirst { it.serverSeq == serverSeq }
        require(at >= 0) { "no row $serverSeq to change" }
        kept[at] = kept[at].copy(metadata = metadata)
        mutationHead += 1uL
        mutations += MutationRow(serverSeq, mutationHead, metadata = metadata)
    }

    /**
     * A preview resolved for row [serverSeq], stored on it before the phones hear of it (PROTOCOL.md "Link
     * previews").
     */
    fun addPreview(
        serverSeq: ULong,
        card: LinkPreviewCard,
    ) {
        val at = kept.indexOfFirst { it.serverSeq == serverSeq }
        require(at >= 0) { "no row $serverSeq to preview" }
        kept[at] = kept[at].copy(linkPreviews = kept[at].linkPreviews.orEmpty() + card)
    }

    /**
     * A phone that holds rows past the head, from a run of the demo before the app restarted, moves the head
     * past them: the counter never goes back, so what comes next is new to it.
     */
    fun catchUp(lastServerSeq: ULong) {
        head = maxOf(head, lastServerSeq)
    }

    /** The frontier a phone reported, moved forward only and never past the head (PROTOCOL.md "Read state"). */
    fun read(reported: ULong): ULong {
        readFrontier = minOf(maxOf(readFrontier, reported), head)
        return readFrontier
    }

    /** [event] to every phone connected to this Fermix. */
    fun broadcast(event: ServerEvent.Known) {
        connections.toList().forEach { it.send(event) }
    }
}

/** [epochMs] in RFC 3339 UTC to the second, as a daemon writes `ts`. */
internal fun stamp(epochMs: Long): String = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

private fun previewCard(
    preview: ScriptPreview,
    blobs: Map<DemoBlobName, DemoBlob>,
): LinkPreviewCard =
    LinkPreviewCard(
        preview.url,
        preview.site,
        preview.title,
        preview.description,
        imageRef = checkNotNull(blobs[preview.image]) { "no blob ${preview.image}" }.ref,
    )
