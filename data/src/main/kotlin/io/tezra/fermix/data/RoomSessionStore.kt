package io.tezra.fermix.data

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.RowEdits
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.session.UploadMarks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer

/**
 * core-session's SessionStore over one (instance, profile)'s database, [opened] at the store's first call:
 * opening one makes its folder, which the main thread a pairing starts on never does. Each call is one
 * statement or one transaction, so it is written whole or not at all; a write that finds the cursors row
 * missing fails. The rows a session announces are persisted by the app's Announcer through
 * [ProfileDatabase.timeline], the cache this store's mutations, rebuild and row edits ([RoomRowEdits]) act on,
 * and an upload's marks ([RoomUploadMarks]) land on the outbox row. An item that leaves the outbox lets go of
 * its [staged] files that no other item names, on [io]: a run again of a refused item takes its attachments
 * over before the refused one leaves.
 */
class RoomSessionStore(
    opened: Lazy<ProfileDatabase>,
    private val staged: StagedUploads,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionStore,
    RowEdits by RoomRowEdits(opened),
    UploadMarks by RoomUploadMarks(opened) {
    constructor(database: ProfileDatabase, staged: StagedUploads) : this(lazyOf(database), staged)

    private val cache by lazy { opened.value.cache() }
    private val outbox by lazy { opened.value.outbox() }
    private val uploads by lazy { opened.value.uploads() }

    override suspend fun cursors(): StoredCursors {
        val row = checkNotNull(cache.cursors()) { "the cursors row is missing" }
        return StoredCursors(
            lastServerSeq = row.lastServerSeq.toSeq(),
            readUpToSeq = row.readUpToSeq.toSeq(),
            lastMutationSeq = row.lastMutationSeq.toSeq(),
            announcedUpToSeq = row.announcedUpToSeq.toSeq(),
            lastUnannouncedSeq = row.lastUnannouncedSeq.toSeq(),
        )
    }

    override suspend fun setServerCursor(
        seq: ULong,
        announcedUpToSeq: ULong,
        lastUnannouncedSeq: ULong,
    ) = requireOneRow(cache.setServerCursor(seq.toColumn(), announcedUpToSeq.toColumn(), lastUnannouncedSeq.toColumn()))

    override suspend fun setReadFrontier(seq: ULong) = requireOneRow(cache.setReadFrontier(seq.toColumn()))

    override suspend fun applyMutations(
        rows: List<MutationRow>,
        lastMutationSeq: ULong,
    ) = cache.applyMutations(rows, lastMutationSeq.toColumn())

    override suspend fun rebuildCache(mutationHeadSeq: ULong) = cache.rebuildCache(mutationHeadSeq.toColumn())

    override suspend fun outbox(): List<OutboxItem> = outbox.items().map { it.toItem() }

    /** Enqueues [item] last; an id the outbox holds already is refused, as core-session never enqueues one. */
    override suspend fun enqueue(item: OutboxItem) = outbox.enqueue(item.toEntity())

    override suspend fun dequeue(clientMsgId: String) {
        val sources = uploads.attachmentsOf(clientMsgId)?.let(::sourcesOf).orEmpty()
        outbox.dequeue(clientMsgId)
        releaseStaged(uploads, staged, io, sources)
    }

    override suspend fun markWritten(clientMsgId: String): Boolean = outbox.markWritten(clientMsgId) == 1

    override suspend fun withdraw(clientMsgId: String): Boolean {
        val sources = uploads.attachmentsOf(clientMsgId)?.let(::sourcesOf).orEmpty()
        val withdrawn = outbox.withdraw(clientMsgId) == 1
        if (withdrawn) releaseStaged(uploads, staged, io, sources)
        return withdrawn
    }

    override suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) {
        val changed = outbox.markFailed(clientMsgId, failure.code, failure.message)
        check(changed == 1) { "$clientMsgId is not in the outbox" }
    }
}

/** [this] as its outbox row; its position is the enqueue's to give. */
private fun OutboxItem.toEntity(): OutboxEntity =
    OutboxEntity(
        clientMsgId = clientMsgId,
        position = 0L,
        request = STORED_JSON.encodeToString(serializer<ClientEvent>(), request),
        failureCode = failure?.code,
        failureMessage = failure?.message,
        written = written,
        attachments = encodeAttachments(attachments),
        uploadStarts = uploadStarts,
    )

/** The staged files of [sources] that no row left in the outbox names, let go of on [io]. */
private suspend fun releaseStaged(
    uploads: UploadsDao,
    staged: StagedUploads,
    io: CoroutineDispatcher,
    sources: List<String>,
) {
    if (sources.isEmpty()) return
    val named = uploads.attachments().flatMap(::sourcesOf).toSet()
    withContext(io) { staged.release(sources.filterNot { it in named }) }
}

internal fun OutboxEntity.toItem(): OutboxItem {
    val request = STORED_JSON.decodeFromString(serializer<ClientEvent>(), request)
    check((failureCode == null) == (failureMessage == null)) { "$clientMsgId's failure is stored in part" }
    val failure = failureCode?.let { code -> RequestFailure(code, checkNotNull(failureMessage)) }
    val item = OutboxItem(request, failure, written, decodeAttachments(attachments), uploadStarts)
    check(item.clientMsgId == clientMsgId) { "outbox row $clientMsgId holds ${item.clientMsgId}" }
    return item
}

/** A reaction, a link preview or a transcript on the row [opened]'s cache holds (RowEdits), each one transaction. */
internal class RoomRowEdits(
    opened: Lazy<ProfileDatabase>,
) : RowEdits {
    private val edits by lazy { opened.value.rowEdits() }

    override suspend fun applyReaction(
        clientMsgId: String,
        emoji: String,
    ): Boolean = edits.applyReaction(clientMsgId, emoji)

    override suspend fun addLinkPreview(
        serverSeq: ULong,
        card: LinkPreviewCard,
    ): Boolean = edits.addLinkPreview(serverSeq.toColumn(), card)

    override suspend fun applyTranscript(
        clientMsgId: String,
        text: String,
    ): Boolean = edits.applyTranscript(clientMsgId, text)
}

/** An upload's marks on the outbox row of [opened] (UploadMarks), each one statement or transaction. */
internal class RoomUploadMarks(
    opened: Lazy<ProfileDatabase>,
) : UploadMarks {
    private val uploads by lazy { opened.value.uploads() }

    override suspend fun markUploaded(
        clientMsgId: String,
        attachId: String,
    ): Boolean = uploads.markUploaded(clientMsgId, attachId)

    override suspend fun setUploadStarts(
        clientMsgId: String,
        starts: Int,
    ): Boolean = uploads.setUploadStarts(clientMsgId, starts) == 1
}
