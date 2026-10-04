package io.tezra.fermix

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chat.rowWords
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.RoomSessionStore
import io.tezra.fermix.data.stagedUploads
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.push.PushLog
import io.tezra.fermix.push.TrialDecrypt
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The wall clock of these tests, in Unix milliseconds. */
private const val NOW_MS = 1_790_000_000_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * The notifications' one owner as a session reaches it (design section 10, "Lifecycle on the phone"): a row
 * the socket brings first is posted by the app itself, so the push that follows adds nothing
 * (tla/specs/mobile_push, PUSH-2); a live approval whose chat is off screen is posted through the same owner;
 * a row this phone's owner read leaves the conversation's notification before the next row's; a read that
 * leaves rows, and the app lock turned on, rebuild a notification still showing without alerting, while a
 * dismissed one stays dismissed; and an id of spaces is an id.
 *
 * On Robolectric, for the profiles' databases and Android's notification manager; the plain Application
 * stands in for the app's, whose services these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val databases by lazy { ProfileDatabases(context, File(folder.root, "profiles")) }
    private val mini = PairedDaemon(2, "mini")
    private val miniId: String get() = mini.record.id
    private val conversation: String get() = conversationId(miniId, MAIN_PROFILE)
    private val lines = PushLog()
    private var locked = false

    private class Rig(
        val notifications: Notifications,
        val socket: RowAnnouncer,
        val inbox: PushInbox,
    )

    private suspend fun TestScope.rig(): Rig {
        val store = InstanceStore(File(folder.root, "instances.json"), backgroundScope, databases)
        store.upsert(mini.record)
        val records = store.instances.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        records.first { it.isNotEmpty() }
        val copy = NotificationCopy.of(context.resources)
        val notifications = Notifications(PostedNotifications(context), records, databases, copy, { locked }) { NOW_MS }
        val socket =
            RowAnnouncer(
                miniId,
                MAIN_PROFILE,
                lazy { databases.open(miniId, MAIN_PROFILE) },
                { _, _, _ -> false },
                notifications,
            ) {
                NOW_MS
            }
        val keys = SoftKeys(mapOf(mini.record.keyAlias to mini.device))
        val trial = TrialDecrypt(keys) { message, fault -> error("$message: $fault") }
        val parts =
            PushParts(store, trial, notifications, databases, { _, _ -> false }, lines, backgroundScope) { NOW_MS }
        return Rig(notifications, socket, PushInbox(parts))
    }

    private fun posted(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.messages(): List<String> =
        extras
            .getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            .orEmpty()
            .map { it.getCharSequence("text").toString() }

    private fun Notification.alertsOnce(): Boolean = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    @Test
    fun `a socket row that comes first is posted by the app itself, alerting, and its push then adds nothing`() =
        runTest {
            val rig = rig()
            val backup = row(5, "the backup finished")
            assertEquals(Announcement.NOTIFIED, rig.socket.announce(backup))

            val shown = posted().single()
            assertEquals("$conversation/messages", shown.tag)
            assertEquals(conversation, shown.notification.channelId)
            assertEquals(conversation, shown.notification.shortcutId)
            assertEquals(listOf(rowWords(backup)), shown.notification.messages())
            assertFalse("a post that adds a row is silenced", shown.notification.alertsOnce())

            rig.inbox.received(mini.push(messageJson(5, "Tests passed")))
            assertSame(shown.notification, posted().single().notification)
            assertTrue("suppressed:set:$miniId" in lines.lines.value.map { it.toString() })
        }

    @Test
    fun `a live approval whose chat is off screen is posted through the same owner, and its replay posts nothing`() =
        runTest {
            val rig = rig()
            val alerts = ApprovalAlerts({ _, _ -> false }, rig.notifications) { NOW_MS }
            val card = SessionEvent.Approval("ap-1", "sandbox", "Run make test?", "make test", ttlS = 90)
            val put: suspend (NotifiedEntry, Long) -> Boolean = { entry, at ->
                databases.open(miniId, MAIN_PROFILE).notified().put(entry, at)
            }
            assertTrue(alerts.arrived(miniId, card, put))

            val shown = posted().single()
            assertEquals("$conversation/approval/ap-1", shown.tag)
            assertEquals(conversation, shown.notification.channelId)
            assertEquals("mini needs your approval", shown.notification.title())
            assertEquals(90 * MILLIS_PER_SECOND, shown.notification.timeoutAfter)

            manager.cancelAll()
            assertFalse(alerts.arrived(miniId, card, put))
            assertEquals(emptyList<StatusBarNotification>(), posted())
        }

    @Test
    fun `a live approval whose id is only spaces is notified under that id, and throws nothing`() =
        runTest {
            val rig = rig()
            val alerts = ApprovalAlerts({ _, _ -> false }, rig.notifications) { NOW_MS }
            // The wire's rule for an id is a string of one character or more (protocol.schema.json), and so is data's.
            val card = SessionEvent.Approval(" ", "sandbox", "Run make test?", "make test", ttlS = 90)
            val put: suspend (NotifiedEntry, Long) -> Boolean = { entry, at ->
                databases.open(miniId, MAIN_PROFILE).notified().put(entry, at)
            }
            assertTrue(alerts.arrived(miniId, card, put))
            assertEquals("$conversation/approval/ ", posted().single().tag)
        }

    @Test
    fun `the app lock turned on rewrites a showing conversation's notification with no word of it, quietly`() =
        runTest {
            val rig = rig()
            rig.socket.announce(row(5, "the secret plan"))
            assertTrue("the secret plan" in posted().single().notification.written())

            locked = true
            rig.notifications.restyled()
            val shown = posted().single().notification
            assertEquals("New message", shown.title())
            assertFalse("the secret plan" in shown.written())
            assertTrue("a rewrite that adds no row alerts again", shown.alertsOnce())

            manager.cancelAll()
            locked = false
            rig.notifications.restyled()
            assertEquals(emptyList<StatusBarNotification>(), posted())
        }

    @Test
    fun `a row this phone's owner read leaves the notification before the next row's, which lists that row alone`() =
        runTest {
            val rig = rig()
            rig.socket.announce(row(5, "the backup finished"))
            // The session stores the owner's read before it says it (core-session's Timeline): the word may lag.
            RoomSessionStore(
                lazy { databases.open(miniId, MAIN_PROFILE) },
                databases.stagedUploads(miniId, MAIN_PROFILE),
            ).setReadFrontier(5uL)
            rig.socket.announce(row(6, "the tests passed"))

            assertEquals(listOf("the tests passed"), posted().single().notification.messages())
            assertEquals(
                listOf(6uL),
                databases
                    .open(miniId, MAIN_PROFILE)
                    .notified()
                    .serverSeqs()
                    .first(),
            )
        }

    @Test
    fun `a read that leaves rows rebuilds a showing notification quietly, and a dismissed one stays dismissed`() =
        runTest {
            val rig = rig()
            listOf(5, 6, 7).forEach { rig.socket.announce(row(it, "row $it")) }
            assertEquals(listOf("row 5", "row 6", "row 7"), posted().single().notification.messages())

            assertTrue(rig.notifications.frontierMoved(miniId, MAIN_PROFILE, 5uL))
            val rebuilt = posted().single().notification
            assertEquals(listOf("row 6", "row 7"), rebuilt.messages())
            assertTrue("a rebuild that adds no row alerts again", rebuilt.alertsOnce())

            manager.cancel("$conversation/messages", POSTED_NOTIFICATION_ID)
            assertTrue(rig.notifications.frontierMoved(miniId, MAIN_PROFILE, 6uL))
            assertEquals(emptyList<StatusBarNotification>(), posted())
            assertEquals(
                listOf(7uL),
                databases
                    .open(miniId, MAIN_PROFILE)
                    .notified()
                    .serverSeqs()
                    .first(),
            )
        }
}

private fun row(
    seq: Int,
    words: String,
): TimelineRow =
    TimelineRow.Message(HistoryMessage(seq.toULong(), "assistant", words, "2026-10-02T09:00:00Z", emptyList()))
