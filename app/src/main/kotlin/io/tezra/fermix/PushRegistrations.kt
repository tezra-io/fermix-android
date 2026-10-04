package io.tezra.fermix

import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.instance.NotificationsPolicy
import io.tezra.fermix.push.RegistrationStep
import io.tezra.fermix.push.pushWanted
import io.tezra.fermix.push.registrationStep
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.registerPush
import io.tezra.fermix.session.unregisterPush
import kotlinx.coroutines.flow.first

/**
 * What the registrations run on: the records ([instances]), the sessions the supervisor holds ([sessions]),
 * whether an instance's notifications can show ([canShow]: `POST_NOTIFICATIONS` granted, the app's
 * notifications on, its channel not blocked), FCM's token if one is held ([token], which asks FCM when none
 * is, its answer coming to [PushRegistrations.refresh]), the wall clock in Unix milliseconds
 * ([now]) and the log. A session carries `push_register` and `push_unregister` through [register] and
 * [unregister], Session.registerPush and Session.unregisterPush, which a test replaces to see what is sent.
 */
data class RegistrationParts(
    val instances: InstanceStore,
    val sessions: () -> Map<String, Session>,
    val canShow: (Instance) -> Boolean,
    val token: () -> String?,
    val now: () -> Long,
    val log: (String, Throwable?) -> Unit,
    val register: suspend (Session, String) -> Boolean = { session, fcm -> session.registerPush(fcm) },
    val unregister: suspend (Session) -> Boolean = { it.unregisterPush() },
)

/**
 * This phone's push registration with each daemon (design section 10, "Registration"), the app's
 * [NotificationsPolicy]: on each connection once it has reconciled ([reconciled]), when the app comes into
 * sight or FCM hands the token asked for ([refresh]), when FCM hands a new one ([newToken]) and when a
 * Notifications switch or
 * onboarding's question is answered ([apply]), each instance's [registrationStep] is taken over its live
 * session: `push_register` with FCM's token, its time written to the record, or `push_unregister`, its time
 * cleared, when notifications cannot show. A step its connection cannot carry now is taken at the next, and
 * one that waits for FCM's token at the token's arrival: nothing here waits for FCM, so a session's events
 * never wait on it. The token is never written to the record or to a log: a new one clears the time of every
 * record whose daemon is to push, so each is registered again.
 */
class PushRegistrations(
    internal val parts: RegistrationParts,
) : NotificationsPolicy {
    private val instances: InstanceStore get() = parts.instances

    /**
     * The switch or onboarding's question answered for [instance], the record as read back after the answer
     * was written: its own switch is what the step acts on, so a second answer written meanwhile, a quick
     * double toggle, is the one taken, and its own call takes it again.
     */
    override suspend fun apply(
        instance: Instance,
        on: Boolean,
    ) {
        parts.sessions()[instance.id]?.let { step(instance, it) }
    }

    /**
     * [instanceId]'s connection has reconciled, so `push_register` may go (Session.registerPush). The full pull
     * an `onDeletedMessages` asked for has been asked for by now when the session was opened to make it
     * ([pulledInFull]); a session opened before then leaves the flag for the next.
     */
    suspend fun reconciled(
        instanceId: String,
        session: Session,
        pulledInFull: Boolean,
    ) {
        require(instanceId.isNotBlank()) { "a connection names its instance" }
        val record = instances.instances.first().find { it.id == instanceId } ?: return
        if (record.historyPullDue && pulledInFull) instances.update(instanceId) { it.copy(historyPullDue = false) }
        step(record, session)
    }

    /**
     * In sight again, or FCM's token arrived: a permission taken or a channel blocked meanwhile unregisters,
     * an old registration renews, and one that waited for the token goes.
     */
    suspend fun refresh() = stepAll()

    /**
     * FCM's new token: every daemon that is to push is registered with it, now over a live connection or at its
     * next `hello`. One that is not keeps its time, so the `push_unregister` it is owed still goes.
     */
    suspend fun newToken() {
        instances.instances
            .first()
            .filter { pushWanted(it, parts.canShow(it)) }
            .forEach { record -> instances.update(record.id) { it.copy(fcmRegisteredAt = null) } }
        stepAll()
    }

    private suspend fun stepAll() {
        val live = parts.sessions()
        instances.instances.first().forEach { record -> live[record.id]?.let { step(record, it) } }
    }

    private suspend fun step(
        record: Instance,
        session: Session,
    ) {
        when (registrationStep(record, parts.canShow(record), parts.now())) {
            RegistrationStep.REGISTER -> register(record, session)
            RegistrationStep.UNREGISTER -> unregister(record, session)
            RegistrationStep.NONE -> Unit
        }
    }

    private suspend fun register(
        record: Instance,
        session: Session,
    ) {
        val token = parts.token()
        if (token == null) {
            parts.log("no push token for ${record.id} yet: FCM is asked, and its answer registers", null)
            return
        }
        val at = parts.now()
        if (parts.register(session, token)) instances.update(record.id) { it.copy(fcmRegisteredAt = at) }
    }

    private suspend fun unregister(
        record: Instance,
        session: Session,
    ) {
        if (parts.unregister(session)) instances.update(record.id) { it.copy(fcmRegisteredAt = null) }
    }
}
