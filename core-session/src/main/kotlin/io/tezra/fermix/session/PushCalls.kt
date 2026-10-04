package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Platform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * `push_register{platform:"android", token}` with [token] (design section 10, "Registration"), sent once if a
 * connection is up and reconciled ([SessionEvent.Reconciled]), never queued; false otherwise, the session
 * ended among them, and the app registers at the next connection's reconciliation. Never before it: its
 * steps take an `error` that names no request for their own refusal, as a refused registration's would be.
 */
suspend fun Session.registerPush(token: String): Boolean {
    require(token.isNotBlank()) { "push_register carries a token" }
    return pushCalls.post(ClientEvent.PushRegister(platform = Platform.ANDROID, token = token))
}

/**
 * `push_unregister` (design section 7, the `push_unregister` row): this phone's notifications cannot show, so
 * the daemon clears its token. Sent as [registerPush] is, once on a reconciled connection, never queued.
 */
suspend fun Session.unregisterPush(): Boolean = pushCalls.post(ClientEvent.PushUnregister)

/** What a session sends about this phone's push token, on its dispatcher, [confined]. */
internal class PushCalls(
    private val core: SessionCore,
    private val confined: CoroutineDispatcher,
) {
    /** [event] once on the connection that is up and reconciled; false when there is none. */
    suspend fun post(event: ClientEvent): Boolean =
        withContext(confined) {
            val live = core.live?.takeIf { it.reconciled } ?: return@withContext false
            live.post(event)
            true
        }
}
