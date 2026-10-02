package io.tezra.fermix.data

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.StoredCursors
import kotlinx.serialization.serializer

/**
 * core-session's SessionStore over one (instance, profile)'s [database]. Each call is one statement or one
 * transaction, so it is written whole or not at all; a write that finds the cursors row missing fails. The
 * rows a session announces are persisted by the app's Announcer through [ProfileDatabase.timeline], the
 * cache this store's mutations and rebuild act on.
 */
class RoomSessionStore(
    database: ProfileDatabase,
) : SessionStore {
    private val cache = database.cache()
    private val outbox = database.outbox()

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
    override suspend fun enqueue(item: OutboxItem) {
        val request = STORED_JSON.encodeToString(serializer<ClientEvent>(), item.request)
        outbox.enqueue(item.clientMsgId, request, item.failure?.code, item.failure?.message)
    }

    override suspend fun dequeue(clientMsgId: String) = outbox.dequeue(clientMsgId)

    override suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) {
        val changed = outbox.markFailed(clientMsgId, failure.code, failure.message)
        check(changed == 1) { "$clientMsgId is not in the outbox" }
    }
}

internal fun OutboxEntity.toItem(): OutboxItem {
    val request = STORED_JSON.decodeFromString(serializer<ClientEvent>(), request)
    check((failureCode == null) == (failureMessage == null)) { "$clientMsgId's failure is stored in part" }
    val failure = failureCode?.let { code -> RequestFailure(code, checkNotNull(failureMessage)) }
    val item = OutboxItem(request, failure)
    check(item.clientMsgId == clientMsgId) { "outbox row $clientMsgId holds ${item.clientMsgId}" }
    return item
}
