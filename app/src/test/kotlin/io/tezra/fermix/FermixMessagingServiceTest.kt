package io.tezra.fermix

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.push.PushDecision
import io.tezra.fermix.push.RegistrationStep
import io.tezra.fermix.push.registrationStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** How long the app's services may take to take a record and write one, which they do off the main thread. */
private const val SETTLE_MILLIS = 10_000L
private const val POLL_MILLIS = 50L

/** Who FCM says sent a message: the Firebase project's sender, a placeholder here. */
private const val SENDER = "fermix@fcm.googleapis.com"

/** The action of the intent FCM hands the service a message by (FirebaseMessagingService's own). */
private const val FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"

/** The manifest's flag Firebase reads before it makes Play services the app's notification delegate. */
private const val DELEGATION_FLAG = "firebase_messaging_notification_delegation_enabled"

private const val GET_META_DATA = PackageManager.GET_META_DATA.toLong()

/** The daemon whose push these tests send, and whose device key the phone holds in software. */
private val DAEMON = PairedDaemon(1, "studio")

/**
 * A daemon of one test alone, its conversation one no other test's app writes: each test's app lives on past
 * its test on Robolectric, and its late writes land in the notification manager the next test reads.
 */
private val ATTIC = PairedDaemon(4, "attic")

/** The app with the device keys in software, in place of the Keystore, holding [DAEMON]'s and [ATTIC]'s keys. */
class SoftKeysApplication : FermixApplication() {
    override fun makeServices(): AppServices =
        AppServices(
            this,
            keys = SoftKeys(mapOf(DAEMON.record.keyAlias to DAEMON.device, ATTIC.record.keyAlias to ATTIC.device)),
        )

    /** [record] stored once the launch check has run, as an approved pairing stores it. */
    fun store(record: Instance) {
        runBlocking {
            withTimeout(SETTLE_MILLIS) {
                services.checked.first { it }
                services.instances.upsert(record)
            }
        }
    }

    /** [instanceId]'s registration written [at], as a `push_register` writes it. */
    fun stamp(
        instanceId: String,
        at: Long,
    ) {
        runBlocking {
            withTimeout(SETTLE_MILLIS) {
                services.instances.update(instanceId) { it.copy(fcmRegisteredAt = at) }
                services.instances.instances.first { records -> records.all { it.fcmRegisteredAt == at } }
            }
        }
    }

    /**
     * The records once [done] says they are, read afresh every [POLL_MILLIS] up to [SETTLE_MILLIS]: a write
     * another coroutine makes while one collection waits was seen to reach a fresh read and not the waiting
     * collector, here on Robolectric's main thread.
     */
    fun recordsOnce(done: (List<Instance>) -> Boolean): List<Instance> {
        repeat((SETTLE_MILLIS / POLL_MILLIS).toInt()) {
            val records = runBlocking { services.instances.instances.first() }
            if (done(records)) return records
            Thread.sleep(POLL_MILLIS)
        }
        error("the records never settled in ${SETTLE_MILLIS}ms")
    }
}

/**
 * FCM's service over the app's services (design section 10): a data message is opened over the device
 * keys, here software keys standing in for the Keystore, and posted before `onMessageReceived` returns; a
 * message's `notification` block is never shown, whoever sent it, and Play services is never made the app's
 * notification delegate, which would show it without the app; turning the app lock on or a chat's previews off
 * rewrites the notification showing without its words; dropped messages set each record's full pull; a token
 * that replaces the one held registers every daemon again; and the registrations read whether notifications
 * can show as the notifications do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SoftKeysApplication::class)
class FermixMessagingServiceTest {
    private val app: SoftKeysApplication get() = ApplicationProvider.getApplicationContext()

    private fun service(): FermixMessagingService = Robolectric.setupService(FermixMessagingService::class.java)

    @Test
    fun `a data message is opened over the device keys and its notification posted before the service returns`() {
        app.store(DAEMON.record)
        val data = DAEMON.push(messageJson(3, "Done"))
        service().onMessageReceived(RemoteMessage.Builder(SENDER).setData(data).build())

        val shown = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(conversationId(DAEMON.record.id, MAIN_PROFILE), shown.notification.channelId)
        val said =
            app.services.pushLog.lines.value
                .map { it.toString() }
        assertEquals(listOf("received", "decrypted:${DAEMON.record.id}", "posted:${DAEMON.record.id}"), said.take(3))
    }

    @Test
    fun `a notification block is never shown as its sender wrote it, out of sight and locked, but goes to the inbox`() {
        app.store(DAEMON.record)
        runBlocking { app.services.settings.setAppLock(true) }
        // Out of sight as Firebase reads it: its own code would show the block itself and never call the app.
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setInRestrictedInputMode(true)
        val conversation = conversationId(DAEMON.record.id, MAIN_PROFILE)
        val forged =
            Intent(FCM_RECEIVE)
                .putExtra("from", SENDER)
                .putExtra("gcm.n.e", "1")
                .putExtra("gcm.n.title", "studio needs your approval")
                .putExtra("gcm.n.body", "Approve: rm -rf ~")
                .putExtra("gcm.n.android_channel_id", conversation)
                .putExtra("gcm.notification.image", "https://example.com/tracker.png")
        service().handleIntent(forged)

        val shown = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals("$APP_CHANNEL/generic", shown.tag)
        assertEquals(APP_CHANNEL, shown.notification.channelId)
        assertEquals(
            "New message",
            shown.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                .toString(),
        )
        assertNull(shown.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        val said =
            app.services.pushLog.unopened.value
                .filter { it.decision != PushDecision.TIMED }
        assertEquals(listOf("received", "refused v", "generic unopened"), said.map { it.toString() })
    }

    @Test
    fun `Play services is never the app's notification delegate, which would show a notification block as the app`() {
        val flags =
            app.packageManager
                .getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(GET_META_DATA))
                .metaData
        // Firebase reads the flag only when it is there, and makes Play services the delegate when it is not.
        assertTrue(flags.containsKey(DELEGATION_FLAG))
        assertFalse(flags.getBoolean(DELEGATION_FLAG, true))
    }

    @Test
    fun `turning the app lock on, or a chat's previews off, rewrites its showing notification without its words`() {
        app.store(ATTIC.record)
        val data = ATTIC.push(messageJson(3, "the secret plan"))
        service().onMessageReceived(RemoteMessage.Builder(SENDER).setData(data).build())
        assertTrue("the secret plan" in shownOnce { true }.written())

        runBlocking { app.services.settings.setAppLock(true) }
        val locked = shownOnce { "the secret plan" !in it.written() }
        assertEquals("New message", locked.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        runBlocking { app.services.settings.setAppLock(false) }
        shownOnce { "the secret plan" in it.written() }
        runBlocking {
            app.services.instanceParts().profiles.withDatabase(ATTIC.record.id, MAIN_PROFILE) {
                it.chat().setPreviews(false)
            }
        }
        val quiet = shownOnce { "the secret plan" !in it.written() }
        assertEquals("New message", quiet.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    /**
     * [ATTIC]'s conversation notification once [done] says it is, read every [POLL_MILLIS] up to [SETTLE_MILLIS]:
     * the services rewrite it on their own threads.
     */
    private fun shownOnce(done: (Notification) -> Boolean): Notification {
        val manager = app.getSystemService(NotificationManager::class.java)
        val tag = "${conversationId(ATTIC.record.id, MAIN_PROFILE)}/messages"
        repeat((SETTLE_MILLIS / POLL_MILLIS).toInt()) {
            val shown = manager.activeNotifications.singleOrNull { posted -> posted.tag == tag }?.notification
            if (shown != null && done(shown)) return shown
            Thread.sleep(POLL_MILLIS)
        }
        error("the notification never settled in ${SETTLE_MILLIS}ms")
    }

    @Test
    fun `the registrations take a denied permission or a blocked channel as notifications that cannot show`() {
        // A record no test stores, whose channel only this test makes.
        val record = PairedDaemon(5, "shed").record
        val canShow = app.services.registrations.parts.canShow
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(canShow(record))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(canShow(record))
        val registered = record.copy(fcmRegisteredAt = 1_000L)
        assertEquals(RegistrationStep.UNREGISTER, registrationStep(registered, canShow(registered), 2_000L))

        shadowOf(manager).setNotificationsEnabled(true)
        val conversation = conversationId(record.id, MAIN_PROFILE)
        manager.createNotificationChannel(
            NotificationChannel(conversation, "studio", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(canShow(record))
    }

    @Test
    fun `messages FCM dropped set every record's full pull`() {
        app.store(DAEMON.record)
        service().onDeletedMessages()
        assertEquals(listOf(true), app.recordsOnce { true }.map { it.historyPullDue })
    }

    @Test
    fun `a token that replaces the one held clears every record's registration, so each daemon registers again`() {
        app.store(DAEMON.record)
        val service = service()
        // Whether this first one was asked for depends on whether the app has come into sight; the next is news.
        service.onRegistered("fcm-token-of-this-phone")
        app.stamp(DAEMON.record.id, at = 1_000L)
        service.onRegistered("fcm-token-rotated")
        val records = app.recordsOnce { records -> records.all { it.fcmRegisteredAt == null } }
        assertNull(records.single().fcmRegisteredAt)
    }
}
