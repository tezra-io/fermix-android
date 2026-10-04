package io.tezra.fermix

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.RoomSessionStore
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.data.stagedUploads
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.push.MAX_DATA_BYTES
import io.tezra.fermix.push.PushDecision
import io.tezra.fermix.push.PushLine
import io.tezra.fermix.push.PushLog
import io.tezra.fermix.push.TrialDecrypt
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The wall clock of these tests, in Unix milliseconds. */
private const val NOW_MS = 1_790_000_000_000L
private const val MILLIS_PER_SECOND = 1_000L

/** How long, in real time, a test may run before it fails: the budget's runs in virtual time, and at once. */
private val SETTLE = 10.seconds

/**
 * A push taken to its notification (design section 10, "Lifecycle on the phone"): the trial names the
 * instance, trying only the daemons that push through FCM, the notified set alerts once per id between a push
 * and its socket row, a row read or on screen is not notified, an unknown kind, an unreadable plaintext, a
 * profile other than main, a push no key opens and a trial past its budget post the generic notification, but
 * not while no Fermix wants its pushes, a push outside the envelope's bounds costs no agreement, the app lock
 * writes no word of a message, a late approval shows as expired, an id of spaces is an id, and FCM's dropped
 * messages set each instance's full pull. Each push leaves its diagnostics lines, with no content.
 *
 * On Robolectric, for the profiles' databases and Android's notification manager; the plain Application
 * stands in for the app's, whose services these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushInboxTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val databases by lazy { ProfileDatabases(context, File(folder.root, "profiles")) }

    /** Two paired daemons: the push of the second costs the trial an agreement with the first. */
    private val studio = PairedDaemon(1, "studio")
    private val mini = PairedDaemon(2, "mini")
    private val keys = SoftKeys(mapOf(studio.record.keyAlias to studio.device, mini.record.keyAlias to mini.device))

    private var locked = false
    private var showing: String? = null
    private val lines = PushLog()

    private class Rig(
        val store: InstanceStore,
        val records: StateFlow<List<Instance>>,
        val notifications: Notifications,
        val inbox: PushInbox,
    )

    /** The inbox over [studio] and [mini], its trial agreeing through [deviceKeys] in [keystore]. */
    private suspend fun TestScope.rig(
        deviceKeys: DeviceKeyFacade = keys,
        keystore: CoroutineScope = backgroundScope,
    ): Rig {
        val store = InstanceStore(instanceDataStore(File(folder.root, "instances.json"), backgroundScope), databases)
        store.upsert(studio.record)
        store.upsert(mini.record)
        val records = store.instances.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        records.first { it.size == 2 }
        val copy = NotificationCopy.of(context.resources)
        val notifications =
            Notifications(PostedNotifications(context), records, databases, copy, { locked }) { NOW_MS }
        val parts =
            PushParts(
                store,
                TrialDecrypt(deviceKeys) { message, fault -> error("$message: $fault") },
                notifications,
                databases,
                { instanceId, _ -> instanceId == showing },
                lines,
                keystore = keystore,
            ) { NOW_MS }
        return Rig(store, records, notifications, PushInbox(parts))
    }

    private val miniId: String get() = mini.record.id

    private fun posted(): List<StatusBarNotification> = manager.activeNotifications.toList()

    /** What each push a key opened came to, past the lines every push leaves (received, decrypted, timed). */
    private fun decisions(): List<String> = decided(lines.lines.value)

    /** What each push no key opened came to, in the ring of its own. */
    private fun unopened(): List<String> = decided(lines.unopened.value)

    private fun decided(pushLines: List<PushLine>): List<String> =
        pushLines
            .filter { it.decision !in setOf(PushDecision.RECEIVED, PushDecision.DECRYPTED, PushDecision.TIMED) }
            .map { it.toString() }

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** The words of each MessagingStyle message, in order. */
    private fun Notification.messages(): List<String> =
        extras
            .getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            .orEmpty()
            .map { it.getCharSequence("text").toString() }

    @Test
    fun `a row's push posts its conversation on its instance's channel, with its shortcut, preview and chat`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))

            val shown = posted().single()
            val conversation = conversationId(miniId, MAIN_PROFILE)
            assertEquals("$conversation/messages", shown.tag)
            assertEquals(conversation, shown.notification.channelId)
            assertEquals(conversation, shown.notification.shortcutId)
            val conversationTitle = shown.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            assertEquals("mini", conversationTitle.toString())
            assertEquals(listOf("Build finished"), shown.notification.messages())
            val tap = shadowOf(shown.notification.contentIntent)
            assertEquals(chatLink(miniId, MAIN_PROFILE), tap.savedIntent.dataString)
            assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
            assertTrue(tap.flags and PendingIntent.FLAG_IMMUTABLE != 0)

            val said = lines.lines.value.map { it.toString() }
            assertEquals(listOf("received", "decrypted:$miniId", "posted:$miniId"), said.take(3))
            assertTrue(said.last().matches(Regex("timed:$miniId \\d+ ms")))
            assertFalse(said.any { "Build" in it })
        }

    @Test
    fun `a row the notified set holds, put by an earlier push or by its socket row, alerts nobody again`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))
            manager.cancelAll()
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))
            assertEquals(emptyList<StatusBarNotification>(), posted())

            val socket =
                RowAnnouncer(
                    miniId,
                    MAIN_PROFILE,
                    lazy { databases.open(miniId, MAIN_PROFILE) },
                    { _, _, _ -> false },
                    rig.notifications,
                ) { NOW_MS }
            assertEquals(Announcement.NOTIFIED, socket.announce(row(5)))
            manager.cancelAll()
            rig.inbox.received(mini.push(messageJson(5, "Tests passed")))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(listOf("posted:$miniId", "suppressed:set:$miniId", "suppressed:set:$miniId"), decisions())
        }

    @Test
    fun `the trial tries only the daemons that push through FCM, in record order`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))
            assertEquals(listOf(studio.record.keyAlias, mini.record.keyAlias), keys.agreed)

            keys.agreed.clear()
            rig.store.update(studio.record.id) { it.copy(pushPlatforms = emptyList()) }
            rig.inbox.received(mini.push(messageJson(4, "Tests passed")))
            assertEquals(listOf(mini.record.keyAlias), keys.agreed)
        }

    @Test
    fun `a row read on the computer before its push is not notified, and a frontier that empties the set cancels`() =
        runTest {
            val rig = rig()
            RoomSessionStore(
                lazy { databases.open(miniId, MAIN_PROFILE) },
                databases.stagedUploads(miniId, MAIN_PROFILE),
            ).setReadFrontier(4uL)
            rig.inbox.received(mini.push(messageJson(4, "Build finished")))
            assertEquals(emptyList<StatusBarNotification>(), posted())

            rig.inbox.received(mini.push(messageJson(6, "Tests passed")))
            assertEquals(1, posted().size)
            assertTrue(rig.notifications.frontierMoved(miniId, MAIN_PROFILE, 6uL))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(listOf("suppressed:read:$miniId", "posted:$miniId"), decisions())
        }

    @Test
    fun `a chat on screen shows its row, and its push posts nothing`() =
        runTest {
            val rig = rig()
            showing = miniId
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(listOf("suppressed:on_screen:$miniId"), decisions())
        }

    @Test
    fun `a push of a kind this app does not know, or unreadable, posts the generic notification of its instance`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push("""{"kind":"reaction","profile_id":"main","server_seq":3}"""))
            val generic = posted().single()
            assertEquals("${conversationId(miniId, MAIN_PROFILE)}/generic", generic.tag)
            assertEquals("mini", generic.notification.title())
            assertEquals("New message", generic.notification.text())

            manager.cancelAll()
            rig.inbox.received(mini.push("""{"kind":"message","profile_id":"main"}"""))
            assertEquals(1, posted().size)

            // Past what the store keeps, which would end the process before anything is posted.
            manager.cancelAll()
            rig.inbox.received(mini.push("""{"kind":"message","profile_id":"main","server_seq":9223372036854775808}"""))
            assertEquals("New message", posted().single().notification.text())
            assertEquals(
                listOf("generic:$miniId kind", "generic:$miniId field server_seq", "generic:$miniId field server_seq"),
                decisions(),
            )
        }

    @Test
    fun `a push of a profile other than main, which no chat here shows, posts its instance's generic notification`() =
        runTest {
            val rig = rig()
            rig.inbox.received(
                mini.push("""{"kind":"message","profile_id":"work","server_seq":3,"preview_text":"Build finished"}"""),
            )
            val generic = posted().single()
            assertEquals("${conversationId(miniId, MAIN_PROFILE)}/generic", generic.tag)
            assertEquals("New message", generic.notification.text())
            assertFalse("Build finished" in generic.notification.written())
            assertEquals(listOf("generic:$miniId profile"), decisions())
        }

    @Test
    fun `an approval or a failed turn whose id is only spaces is notified under that id, and throws nothing`() =
        runTest {
            val rig = rig()
            // The wire's rule for an id is a string of one character or more (protocol.schema.json), and so is data's.
            rig.inbox.received(mini.push(approvalJson(" ", NOW_MS / MILLIS_PER_SECOND + 60)))
            rig.inbox.received(mini.push(turnFailedJson("\\t")))
            val tags = posted().map { it.tag.removePrefix("${conversationId(miniId, MAIN_PROFILE)}/") }
            assertEquals(setOf("approval/ ", "turn/\t"), tags.toSet())
            assertEquals(listOf("posted:$miniId", "posted:$miniId"), decisions())
        }

    @Test
    fun `while the app lock is on the generic notification is New message alone`() =
        runTest {
            val rig = rig()
            locked = true
            rig.inbox.received(mini.push("""{"kind":"reaction","profile_id":"main","server_seq":3}"""))
            val generic = posted().single().notification
            assertEquals("New message", generic.title())
            assertNull(generic.text())
        }

    @Test
    fun `a push no key opens, or outside the envelope's bounds, posts the app's generic notification`() =
        runTest {
            val rig = rig()
            val stranger = PairedDaemon(3, "stranger")
            rig.inbox.received(stranger.push(messageJson(3, "Build finished")))
            val generic = posted().single()
            assertEquals("$APP_CHANNEL/generic", generic.tag)
            assertEquals(APP_CHANNEL, generic.notification.channelId)
            assertEquals("Fermix", generic.notification.title())
            assertNull(generic.notification.shortcutId)
            assertEquals(2, keys.agreed.size)

            keys.agreed.clear()
            rig.inbox.received(mini.push(messageJson(3, "Build finished")) + ("v" to "1"))
            rig.inbox.received(mini.push(messageJson(3, "Build finished")) + ("pad" to "x".repeat(MAX_DATA_BYTES)))
            assertEquals(emptyList<String>(), keys.agreed)
            assertEquals(
                listOf("generic unopened", "refused v", "generic unopened", "refused size", "generic unopened"),
                unopened(),
            )
            assertEquals(emptyList<String>(), decisions())
        }

    @Test
    fun `a push no key opens posts nothing while no Fermix wants its pushes, and says so`() =
        runTest {
            val rig = rig()
            rig.store.update(studio.record.id) { it.copy(notificationsEnabled = false) }
            rig.store.update(miniId) { it.copy(pushPlatforms = emptyList()) }
            rig.inbox.received(PairedDaemon(3, "stranger").push(messageJson(3, "Build finished")))
            rig.inbox.received(mini.push(messageJson(3, "Build finished")) + ("v" to "1"))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(listOf("suppressed:off unopened", "refused v", "suppressed:off unopened"), unopened())
        }

    @Test
    fun `a trial past its budget ends at the budget with the app's generic notification, and leaves its line`() =
        runTest(timeout = SETTLE) {
            val release = CountDownLatch(1)
            // A Keystore whose agreement does not answer: the trial runs on a thread of its own, as the app's.
            val thread = Executors.newSingleThreadExecutor()
            val keystore = CoroutineScope(thread.asCoroutineDispatcher() + Job())
            try {
                val rig = rig(HangingKeys(keys, release), keystore)
                locked = true
                val started = testScheduler.timeSource.markNow()
                rig.inbox.received(mini.push(messageJson(3, "Build finished")))
                assertEquals(PUSH_DECRYPT_BUDGET_MILLIS, started.elapsedNow().inWholeMilliseconds)
                val generic = posted().single()
                assertEquals("$APP_CHANNEL/generic", generic.tag)
                assertEquals("New message", generic.notification.title())
                assertNull(generic.notification.text())
                assertEquals(
                    listOf("generic trial over ${PUSH_DECRYPT_BUDGET_MILLIS}ms", "generic unopened"),
                    unopened(),
                )
            } finally {
                release.countDown()
                keystore.cancel()
                thread.shutdown()
            }
        }

    @Test
    fun `while the app lock is on a row's notification is New message alone, its words written nowhere`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push(messageJson(3, "the secret plan")))
            // The check below finds a preview wherever it is written: here, with the lock off, it is.
            assertTrue("the secret plan" in posted().single().notification.written())

            locked = true
            rig.inbox.received(mini.push(messageJson(4, "the secret plan")))
            val shown = posted().single().notification
            assertEquals("New message", shown.title())
            assertNull(shown.text())
            assertEquals(emptyList<String>(), shown.messages())
            assertFalse("the secret plan" in shown.written())

            locked = false
            databases.withDatabase(miniId, MAIN_PROFILE) { it.chat().setPreviews(false) }
            rig.inbox.received(mini.push(messageJson(5, "the secret plan")))
            val unlocked = posted().single().notification
            assertEquals("mini", unlocked.title())
            assertEquals("New message", unlocked.text())
            assertEquals(emptyList<String>(), unlocked.messages())
            assertFalse("the secret plan" in unlocked.written())
        }

    @Test
    fun `an approval's push times out at its expiry, one past it shows as expired, and each id alerts once`() =
        runTest {
            val rig = rig()
            val inAMinute = NOW_MS / MILLIS_PER_SECOND + 60
            rig.inbox.received(mini.push(approvalJson("a1", inAMinute)))
            val live = posted().single()
            assertEquals("${conversationId(miniId, MAIN_PROFILE)}/approval/a1", live.tag)
            assertEquals("mini needs your approval", live.notification.title())
            assertEquals(60_000L, live.notification.timeoutAfter)

            rig.inbox.received(mini.push(approvalJson("a2", NOW_MS / MILLIS_PER_SECOND - 1)))
            val late = posted().single { it.tag.endsWith("approval/a2") }
            assertEquals("An approval on mini expired", late.notification.title())

            databases.withDatabase(miniId, MAIN_PROFILE) { it.notified().put(NotifiedEntry.Approval("a3"), NOW_MS) }
            rig.inbox.received(mini.push(approvalJson("a1", inAMinute)))
            rig.inbox.received(mini.push(approvalJson("a3", inAMinute)))
            assertEquals(2, posted().size)
            assertEquals(
                listOf("posted:$miniId", "expired:$miniId", "suppressed:set:$miniId", "suppressed:set:$miniId"),
                decisions(),
            )
        }

    @Test
    fun `a failed turn's push is keyed by its turn, and alerts once`() =
        runTest {
            val rig = rig()
            rig.inbox.received(mini.push(turnFailedJson("t1")))
            rig.inbox.received(mini.push(turnFailedJson("t1")))
            rig.inbox.received(mini.push(turnFailedJson("t2")))
            val shown = posted().sortedBy { it.tag }
            assertEquals(listOf("turn/t1", "turn/t2"), shown.map { it.tag.substringAfter("/") })
            assertEquals("mini: the reply didn't finish", shown.first().notification.title())
            assertEquals(listOf("posted:$miniId", "suppressed:set:$miniId", "posted:$miniId"), decisions())
        }

    @Test
    fun `an instance whose notifications are off has its push opened and nothing posted`() =
        runTest {
            val rig = rig()
            rig.store.update(miniId) { it.copy(notificationsEnabled = false) }
            rig.records.first { records -> records.single { it.id == miniId }.notificationsEnabled.not() }
            rig.inbox.received(mini.push(messageJson(3, "Build finished")))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(listOf("suppressed:off:$miniId"), decisions())
        }

    @Test
    fun `messages FCM dropped make each instance's next session pull its history in full`() =
        runTest {
            val rig = rig()
            rig.inbox.deleted()
            assertEquals(
                listOf(true, true),
                rig.store.instances
                    .first()
                    .map { it.historyPullDue },
            )
            assertEquals(listOf("deleted"), decisions())
        }
}

private fun row(seq: Long): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(seq.toULong(), "assistant", "the backup finished", "2026-10-02T09:00:00Z", emptyList()),
    )

private fun approvalJson(
    id: String,
    expiresAt: Long,
): String = """{"kind":"approval","profile_id":"main","approval_id":"$id","expires_at":$expiresAt}"""

private fun turnFailedJson(id: String): String =
    """{"kind":"turn_failed","profile_id":"main","turn_id":"$id","code":"timeout"}"""

/**
 * [keys], but each agreement waits for [release], at most twice [SETTLE], as a Keystore that does not answer:
 * the budget's test lets it go once it has seen the budget end the trial.
 */
private class HangingKeys(
    private val keys: DeviceKeyFacade,
    private val release: CountDownLatch,
) : DeviceKeyFacade by keys {
    override fun staticKey(alias: String): StaticKey {
        val key = keys.staticKey(alias)
        return object : StaticKey {
            override val publicKey: ByteArray get() = key.publicKey

            override fun agree(peerPublicKey: ByteArray): ByteArray {
                val released = release.await(SETTLE.inWholeMilliseconds * 2, TimeUnit.MILLISECONDS)
                check(released) { "the hanging agreement was never let go" }
                return key.agree(peerPublicKey)
            }
        }
    }
}
