package io.tezra.fermix

import io.tezra.fermix.data.Instance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Where a test's idle sessions run, on [dispatcher], as a session's scope names the dispatcher it runs on. */
internal fun sessionScope(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher)

/** How long the app's services may take to take a record and see an upload, which they do off the main thread. */
private const val UPLOAD_SETTLE_MILLIS = 10_000L

/**
 * The app with every session's upload in flight as [upload] says, in place of Session.uploading, so that a test
 * has an upload without a daemon to send it to.
 */
class UploadingApplication : FermixApplication() {
    val upload = MutableStateFlow(false)

    override fun makeServices(): AppServices = AppServices(this, uploadingOf = { upload })

    /**
     * [record] stored and an idle session of it, in [sessions], held by the supervisor, as an approved pairing
     * hands it over; then the supervisor's uploads once they say [upload] is in flight, or not.
     */
    fun hold(
        record: Instance,
        sessions: CoroutineScope,
    ) {
        runBlocking {
            withTimeout(UPLOAD_SETTLE_MILLIS) {
                services.checked.first { it }
                services.instances.upsert(record)
                services.supervisor.adopt(record.id, idleSession(sessions)) { null }
                services.supervisor.sessions.first { record.id in it }
                services.supervisor.uploading.first { (record.id in it) == upload.value }
            }
        }
    }
}
