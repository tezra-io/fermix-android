package io.tezra.fermix

import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.transport.Candidate

/**
 * What the app keeps of a session's events: `hello_ack`'s facts on the record (design section 9.2: the
 * daemon's label, host and profile, its caps and push platforms) and the host-owned agent's name on the
 * chat; the daemon's later routes, and the one the last `hello` went over; an older page's rows, for the
 * chat's cache; the read frontier, which takes the rows it covers from the notified set (section 10); and
 * every event folded into what its chat shows besides its rows ([folds]), whose turns end with the session;
 * an approval card that comes while its chat is off screen, handed to its notification ([alerts]), which
 * goes once the card is over; and, once
 * a connection has reconciled, the push registration it may carry ([registrations]) and the notified set's
 * expired approvals and failed turns gone ([notifications], which also cancels a conversation's notification
 * once the read frontier leaves its set empty). The profile's database is written through
 * [ProfileDatabases.withDatabase], which a removal waits for; an event that comes after its Fermix's removal
 * keeps nothing there, and says so.
 */
internal class SessionEvents(
    private val instances: InstanceStore,
    private val databases: ProfileDatabases,
    private val folds: ChatFolds,
    private val alerts: ApprovalAlerts,
    private val notifications: Notifications,
    private val registrations: PushRegistrations,
) : EventSink {
    override suspend fun take(
        instanceId: String,
        session: Session,
        event: SessionEvent,
    ): Boolean {
        folds.take(instanceId, session, event)
        if (event is SessionEvent.Reconciled) registrations.reconciled(instanceId, session, event.pulledInFull)
        return kept(instanceId, event)
    }

    override fun ended(instanceId: String) = folds.ended(instanceId)

    override fun removed(instanceId: String) = folds.forget(instanceId)

    private suspend fun kept(
        instanceId: String,
        event: SessionEvent,
    ): Boolean =
        when (event) {
            is SessionEvent.Candidates -> {
                instances.update(instanceId) { it.copy(candidates = event.candidates) }
            }

            is SessionEvent.ReadFrontier -> {
                notifications.frontierMoved(instanceId, MAIN_PROFILE, event.readUpToSeq)
            }

            is SessionEvent.Reconciled -> {
                notifications.reconciled(instanceId)
            }

            is SessionEvent.OlderLoaded -> {
                onProfile(instanceId) { it.timeline().persistAll(event.rows) }
            }

            is SessionEvent.Server -> {
                (event.event as? ServerEvent.HelloAck)?.let { helloAck(instanceId, it) } ?: true
            }

            is SessionEvent.Approval -> {
                onProfile(instanceId) { profile ->
                    alerts.arrived(instanceId, event) { entry, at -> profile.notified().put(entry, at) }
                }
            }

            is SessionEvent.ApprovalResolved -> {
                approvalOver(instanceId, event.approvalId)
            }

            is SessionEvent.ApprovalClosedWhileAway -> {
                approvalOver(instanceId, event.approvalId)
            }

            else -> {
                true
            }
        }

    override suspend fun reached(
        instanceId: String,
        candidate: Candidate,
    ) {
        instances.update(instanceId) { it.copy(lastCandidate = candidate) }
    }

    private suspend fun helloAck(
        instanceId: String,
        ack: ServerEvent.HelloAck,
    ): Boolean {
        val recorded =
            instances.update(instanceId) { record ->
                val named = ack.instance
                record.copy(
                    host = named?.host ?: record.host,
                    label = named?.label ?: record.label,
                    profile = named?.profile ?: record.profile,
                    caps = ack.caps,
                    pushPlatforms = ack.caps.push ?: record.pushPlatforms,
                )
            }
        val agent = ack.profiles.find { it.id == MAIN_PROFILE }?.name
        return recorded && onProfile(instanceId) { it.chat().setAgentName(agent) }
    }

    /**
     * [approvalId]'s card is over, answered, denied, expired or gone while the phone was away: its notification
     * goes with it, cleared by its id (design section 7, "deduplicated and cleared by id"). An id the notified
     * set holds stays there, so a push of it that comes late alerts nobody.
     */
    private fun approvalOver(
        instanceId: String,
        approvalId: String,
    ): Boolean {
        notifications.posted.cancel(conversationId(instanceId, MAIN_PROFILE), NotificationKey.Approval(approvalId))
        return true
    }

    /**
     * [write] on [instanceId]'s main profile; false when a removal took its files while the event was on its
     * way (ProfileDatabases.delete): a removed instance's event has nowhere to go.
     */
    private suspend fun onProfile(
        instanceId: String,
        write: suspend (ProfileDatabase) -> Unit,
    ): Boolean = databases.withDatabase(instanceId, MAIN_PROFILE, write) is Use.Ran
}
