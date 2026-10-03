package io.tezra.fermix

import io.tezra.fermix.data.InstanceGone
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.SessionEvent

/**
 * What the app keeps of a session's events now: `hello_ack`'s facts on the record (design section 9.2: the
 * daemon's label, host and profile, its caps and push platforms) and the host-owned agent's name on the
 * chat; the daemon's later routes; and the read frontier, which takes the rows it covers from the notified
 * set (section 10). The rest is the Chat screen's, which comes later and takes them from here then. The
 * profile's database is written through [ProfileDatabases.withDatabase], which a removal waits for.
 */
internal class SessionEvents(
    private val instances: InstanceStore,
    private val databases: ProfileDatabases,
) : EventSink {
    override suspend fun take(
        instanceId: String,
        event: SessionEvent,
    ) {
        when (event) {
            is SessionEvent.Candidates -> instances.update(instanceId) { it.copy(candidates = event.candidates) }
            is SessionEvent.ReadFrontier -> onProfile(instanceId) { it.notified().removeReadUpTo(event.readUpToSeq) }
            is SessionEvent.Server -> (event.event as? ServerEvent.HelloAck)?.let { helloAck(instanceId, it) }
            else -> Unit
        }
    }

    private suspend fun helloAck(
        instanceId: String,
        ack: ServerEvent.HelloAck,
    ) {
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
        onProfile(instanceId) { it.chat().setAgentName(agent) }
    }

    /**
     * [write] on [instanceId]'s main profile, unless a removal took its files while the event was on its way
     * (ProfileDatabases.delete): a removed instance's event has nowhere to go.
     */
    private suspend fun onProfile(
        instanceId: String,
        write: suspend (ProfileDatabase) -> Unit,
    ) {
        try {
            databases.withDatabase(instanceId, MAIN_PROFILE, write)
        } catch (expected: InstanceGone) {
            // Removed while its session's last events were taken: nothing of the instance is left to write.
        }
    }
}
