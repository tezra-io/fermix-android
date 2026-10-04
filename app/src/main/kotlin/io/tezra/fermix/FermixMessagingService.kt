package io.tezra.fermix

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The prefixes of an FCM message's `notification` block in the intent FCM hands the service, the current one
 * and the one before it (firebase-messaging's NotificationParams).
 */
private val NOTIFICATION_KEY_PREFIXES = listOf("gcm.n.", "gcm.notification.")

/**
 * FCM's side of the app (design section 10): a data message is taken to its notification by the app's
 * PushInbox before [onMessageReceived] returns, as FCM holds the phone awake only until then, its trial
 * bounded by [PUSH_DECRYPT_BUDGET_MILLIS]; FCM's token, which it hands over each time the app asks for it
 * and whenever it changes ([onRegistered]), taken into the records before the call returns; and messages FCM
 * dropped ([onDeletedMessages]), which make each instance's next session pull in full, written before it returns
 * too. FCM calls each on a thread of its own, never the main one. The
 * service takes FCM's intent alone: its manifest entry is not exported. A message's `notification` block is
 * never shown: the manifest keeps Play services from showing it as the app (Firebase's notification
 * delegation, off), and [handleIntent] takes it out before Firebase's own code could.
 */
class FermixMessagingService : FirebaseMessagingService() {
    private val services: AppServices get() = (application as FermixApplication).services

    /**
     * Every intent FCM hands the service, its `notification` block taken out first. Firebase's own code would
     * show that block itself while the app is out of sight, and never call [onMessageReceived]: its title, body,
     * channel, link and image as whoever sent it chose, past the app lock and past PushInbox. A daemon sends
     * no such block (design section 10, "the app always renders"), but anyone who learns the token and holds
     * the project's sender credentials can; what is left of the message goes on as a data message, refused or
     * opened like any other, so the owner still sees the generic notification and the diagnostics a line.
     */
    override fun handleIntent(intent: Intent) {
        val shown =
            intent.extras
                ?.keySet()
                .orEmpty()
                .filter { key -> NOTIFICATION_KEY_PREFIXES.any(key::startsWith) }
        shown.forEach(intent::removeExtra)
        super.handleIntent(intent)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { services.push.received(message.data) }
    }

    /** The token is held in memory alone, never written or logged (FcmToken). */
    override fun onRegistered(token: String) {
        runBlocking { services.fcmRegistered(token) }
    }

    /**
     * FCM's token as firebase-messaging before 25.1 hands it, which it does no more while the manifest
     * enables installation-id registration: the same as [onRegistered].
     */
    @Deprecated("FCM hands the token to onRegistered", ReplaceWith("onRegistered(token)"))
    override fun onNewToken(token: String) {
        runBlocking { services.fcmRegistered(token) }
    }

    override fun onDeletedMessages() {
        runBlocking { services.push.deleted() }
    }
}

/**
 * FCM's registration token for this app while the process lives. None is held until FCM hands one to the
 * service; the first [current] that finds none asks FCM ([register], FirebaseMessaging.register), once until
 * FCM answers or [failed] says it will not. It is never written to a record, a file or a log: a process that
 * starts without it asks again, and FCM hands the same token back until it changes.
 */
class FcmToken(
    private val register: (FcmToken) -> Unit,
) {
    private val held = AtomicReference<String?>(null)
    private val asked = AtomicBoolean(false)

    /** The token held, or none, FCM then asked for it unless an ask is out: its answer comes to [registered]. */
    fun current(): String? {
        val token = held.get()
        if (token == null && !asked.getAndSet(true)) register(this)
        return token
    }

    /** FCM's ask failed: the next [current] asks again. */
    fun failed() {
        asked.set(false)
    }

    /**
     * FCM's [token], from the service; true when every daemon must be registered with it again: it replaced
     * the token this process held, or it came while none was asked for, which is how FCM tells a changed one
     * to a process that held none.
     */
    fun registered(token: String): Boolean {
        require(token.isNotBlank()) { "FCM handed a blank token" }
        val before = held.getAndSet(token)
        val unasked = !asked.getAndSet(false)
        return before != token && (before != null || unasked)
    }
}

/**
 * Asks FCM for this app's token, which comes to the service's `onRegistered`; a failure is [log]ged and
 * [token] told, and a developer build's placeholder google-services.json always fails.
 */
internal fun askFcmForToken(
    token: FcmToken,
    log: (String, Throwable?) -> Unit,
) {
    FirebaseMessaging
        .getInstance()
        .register()
        .addOnFailureListener { fault ->
            log("FCM gave no token", fault)
            token.failed()
        }
}
