package io.tezra.fermix

import android.util.Log
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.push.EnvelopeRead
import io.tezra.fermix.push.Opened
import io.tezra.fermix.push.PlaintextRead
import io.tezra.fermix.push.PushDecision
import io.tezra.fermix.push.PushEnvelope
import io.tezra.fermix.push.PushLine
import io.tezra.fermix.push.PushLog
import io.tezra.fermix.push.PushPlaintext
import io.tezra.fermix.push.TrialDecrypt
import io.tezra.fermix.push.readPushPlaintext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private const val TAG = "FermixPush"

/**
 * How long a push's trial may take on the Keystore, one agreement per instance, before the generic
 * notification is posted in its place: FCM gives `onMessageReceived` about ten seconds, and the notified set
 * and the post need what is left (design section 10; the spike's budget, section 15.1).
 */
internal const val PUSH_DECRYPT_BUDGET_MILLIS = 5_000L

private const val MILLIS_PER_SECOND = 1_000L

/**
 * What a push is taken with: the records, tried in their order ([instances]), the trial over the device
 * keys ([trial]), the notifications' owner, each profile's notified set and read frontier ([databases]),
 * which chat is on screen ([showing]), the diagnostics ring ([lines]), the scope the trial runs in, which
 * outlives its budget ([keystore]), and the wall clock in Unix milliseconds ([now]).
 */
data class PushParts(
    val instances: InstanceStore,
    val trial: TrialDecrypt,
    val notifications: Notifications,
    val databases: ProfileDatabases,
    val showing: ChatShowing,
    val lines: PushLog,
    val keystore: CoroutineScope,
    val now: () -> Long,
)

/**
 * The phone's side of a push (design section 10, "Lifecycle on the phone"): the envelope read within its
 * bounds, the trial within [PUSH_DECRYPT_BUDGET_MILLIS], then by the plaintext's kind a notification through
 * the notified set, which alerts once per id between a push and its socket row, whichever comes first; a row
 * read already, or a chat on screen, is not notified. A push no key opens, an unreadable plaintext and a kind
 * this app does not know post the generic notification, never nothing, unless no Fermix wants pushes, as
 * [outcome] says. Each push leaves its lines in the diagnostics ring and the log, with no content: what was
 * decided, of which instance, how long it took; the lines of a push no key opened go to a ring of their own
 * (PushLog.unopened).
 */
class PushInbox(
    private val parts: PushParts,
) {
    /** One FCM message's [data], taken to its notification. */
    suspend fun received(data: Map<String, String>) {
        val started = TimeSource.Monotonic.markNow()
        val said = mutableListOf<PushLine>()
        said.add(line(PushDecision.RECEIVED))
        val outcome = outcome(data, said)
        said.add(outcome)
        said.add(line(PushDecision.TIMED, outcome.instanceId, "${started.elapsedNow().inWholeMilliseconds} ms"))
        said.forEach { Log.i(TAG, it.toString()) }
        if (outcome.instanceId == null) parts.lines.addUnopened(said) else said.forEach(parts.lines::add)
    }

    /**
     * FCM dropped messages it held for this phone (`onDeletedMessages`): each instance's next session pulls
     * its history from the first row when its cache is empty, as a push it never saw may name any row.
     */
    suspend fun deleted() {
        parts.instances.instances.first().forEach { record ->
            parts.instances.update(record.id) { it.copy(historyPullDue = true) }
        }
        val deleted = line(PushDecision.DELETED)
        Log.i(TAG, deleted.toString())
        parts.lines.add(deleted)
    }

    /**
     * What the push came to. One no key opens posts the app's generic notification only while a Fermix wants its
     * pushes, one whose daemon pushes through FCM with its notifications showing: FCM's priority is then that
     * Fermix's to keep (onboarding gotcha 10), and with none, a push anyone who learns the token can send would
     * alert for nobody.
     */
    private suspend fun outcome(
        data: Map<String, String>,
        said: MutableList<PushLine>,
    ): PushLine {
        val records = parts.instances.instances.first()
        val envelope =
            when (val read = PushEnvelope.read(data)) {
                is EnvelopeRead.Read -> read.envelope
                is EnvelopeRead.Refused -> null.also { said.add(line(PushDecision.REFUSED, detail = read.reason)) }
            }
        val opened = envelope?.let { opened(it, records.filter { record -> record.pushReady }, said) }
        val record = opened?.instance
        record?.let { said.add(line(PushDecision.DECRYPTED, it.id)) }
        val wanted = records.any { it.pushReady && parts.notifications.canNotify(it, MAIN_PROFILE) }
        val decided =
            when {
                opened != null -> parts.decided(opened)
                wanted -> parts.generic(null, "unopened")
                else -> Decided(PushDecision.SUPPRESSED_OFF, "unopened")
            }
        return line(decided.decision, record?.id, decided.detail)
    }

    /**
     * The trial over [records], those whose daemon pushes through FCM, in their order, none when no key verified
     * or the budget ran out first, which stops the trial before its next agreement (TrialDecrypt).
     */
    private suspend fun opened(
        envelope: PushEnvelope,
        records: List<Instance>,
        said: MutableList<PushLine>,
    ): Opened? {
        val trial = parts.keystore.async { Tried(parts.trial.open(envelope, records)) }
        val tried = withTimeoutOrNull(PUSH_DECRYPT_BUDGET_MILLIS) { trial.await() }
        if (tried == null) {
            trial.cancel()
            said.add(line(PushDecision.GENERIC, detail = "trial over ${PUSH_DECRYPT_BUDGET_MILLIS}ms"))
        }
        return tried?.opened
    }

    private fun line(
        decision: PushDecision,
        instanceId: String? = null,
        detail: String? = null,
    ): PushLine = PushLine(parts.now(), decision, instanceId, detail)
}

/** The trial's answer, which a budget that ran out is told apart from. */
private class Tried(
    val opened: Opened?,
)

/** What an opened push came to, and why, naming a field or a reason and never a value. */
private class Decided(
    val decision: PushDecision,
    val detail: String? = null,
)

/**
 * [opened]'s plaintext by its kind, of the instance whose key verified it, as the trial read its record:
 * nothing while that instance's notifications are off or cannot show; generic when it is unreadable, of a
 * kind this app does not know, or of a profile other than main, which no chat here shows; nothing while its
 * chat is on screen.
 */
private suspend fun PushParts.decided(opened: Opened): Decided {
    val record = opened.instance
    val read = readPushPlaintext(opened.padded())
    val profileId = (read as? PlaintextRead.Read)?.plaintext?.let(::profileOf)
    return when {
        !notifications.canNotify(record, MAIN_PROFILE) -> Decided(PushDecision.SUPPRESSED_OFF)
        read is PlaintextRead.Unreadable -> generic(record, read.reason)
        profileId == null -> generic(record, "kind")
        profileId != MAIN_PROFILE -> generic(record, "profile")
        showing.isOnScreen(record.id, MAIN_PROFILE) -> Decided(PushDecision.SUPPRESSED_ON_SCREEN)
        else -> Decided(kept(record, read.plaintext))
    }
}

private fun profileOf(plaintext: PushPlaintext): String? =
    when (plaintext) {
        is PushPlaintext.Message -> plaintext.profileId
        is PushPlaintext.Approval -> plaintext.profileId
        is PushPlaintext.TurnFailed -> plaintext.profileId
        PushPlaintext.Unknown -> null
    }

private suspend fun PushParts.kept(
    record: Instance,
    plaintext: PushPlaintext,
): PushDecision =
    when (plaintext) {
        is PushPlaintext.Message -> message(record, plaintext)
        is PushPlaintext.Approval -> approval(record, plaintext)
        is PushPlaintext.TurnFailed -> turnFailed(record, plaintext)
        PushPlaintext.Unknown -> error("a push of no known kind is generic before its kind is kept")
    }

/**
 * The generic notification, of [record] when its key opened the push, else the app's, title-only while the app
 * lock is on; [detail] says why.
 */
private suspend fun PushParts.generic(
    record: Instance?,
    detail: String,
): Decided {
    val chat = record?.let { notifications.facts(it) }
    notifications.posted.post(genericNotification(chat, notifications.locked(), notifications.copy))
    return Decided(PushDecision.GENERIC, detail)
}

/**
 * A row's push: read already (at or below the read frontier), in the notified set already (its socket row or
 * an earlier push came first), or put now and its conversation rebuilt, this row with the preview it carried.
 */
private suspend fun PushParts.message(
    record: Instance,
    message: PushPlaintext.Message,
): PushDecision {
    val seq = message.serverSeq
    val decision =
        onProfile(record.id) { profile ->
            when {
                seq <= profile.readFrontier().first() -> PushDecision.SUPPRESSED_READ
                !profile.notified().put(NotifiedEntry.Row(seq), now()) -> PushDecision.SUPPRESSED_SET
                else -> PushDecision.POSTED
            }
        } ?: PushDecision.SUPPRESSED_OFF
    val pushed = message.previewText?.let { seq to it }
    val shown = decision != PushDecision.POSTED || notifications.postMessages(record, MAIN_PROFILE, pushed)
    return if (shown) decision else PushDecision.SUPPRESSED_OFF
}

/**
 * An approval's push: once per id between it and the session's card, timing out at its expiry, or, arriving
 * at or after it, shown as expired rather than dropped (onboarding gotcha 18).
 */
private suspend fun PushParts.approval(
    record: Instance,
    approval: PushPlaintext.Approval,
): PushDecision {
    val added = onProfile(record.id) { it.notified().put(NotifiedEntry.Approval(approval.approvalId), now()) }
    if (added != true) return PushDecision.SUPPRESSED_SET
    val nowMs = now()
    val expiresAtMs = approval.expiresAt.coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
    val chat = notifications.facts(record)
    notifications.posted.post(approvalNotification(chat, approval.approvalId, expiresAtMs, nowMs, notifications.copy))
    return if (nowMs >= expiresAtMs) PushDecision.EXPIRED else PushDecision.POSTED
}

/** A failed turn's push: once per turn id. */
private suspend fun PushParts.turnFailed(
    record: Instance,
    failed: PushPlaintext.TurnFailed,
): PushDecision {
    val added = onProfile(record.id) { it.notified().put(NotifiedEntry.TurnFailed(failed.turnId), now()) }
    if (added != true) return PushDecision.SUPPRESSED_SET
    notifications.posted.post(turnFailedNotification(notifications.facts(record), failed.turnId, notifications.copy))
    return PushDecision.POSTED
}

/** [block] on [instanceId]'s main profile, none once a removal took it. */
private suspend fun <T : Any> PushParts.onProfile(
    instanceId: String,
    block: suspend (ProfileDatabase) -> T,
): T? =
    when (val used = databases.withDatabase(instanceId, MAIN_PROFILE, block)) {
        is Use.Ran -> used.value
        Use.Gone -> null
    }
