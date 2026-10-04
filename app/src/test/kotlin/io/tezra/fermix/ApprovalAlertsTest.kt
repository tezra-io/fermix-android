package io.tezra.fermix

import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.session.SessionEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val CHAT = "b".repeat(64)

private val CARD = SessionEvent.Approval("ap-1", "sandbox", "Run make test?", "make test", ttlS = 60)

/** What a notifier was handed, in order, and whether it can notify. */
private class FakeApprovalNotifier(
    var can: Boolean = true,
) : ApprovalNotifier {
    val posted = mutableListOf<Pair<String, SessionEvent.Approval>>()

    override fun canNotify(instanceId: String): Boolean = can

    override suspend fun notify(
        instanceId: String,
        approval: SessionEvent.Approval,
    ) {
        posted += instanceId to approval
    }
}

/** A notified set in memory: a put adds once, as NotifiedDao.put's IGNORE does, at the time it was put. */
private class MemoryNotified {
    val entries = mutableMapOf<NotifiedEntry, Long>()

    fun put(
        entry: NotifiedEntry,
        at: Long,
    ): Boolean = entries.putIfAbsent(entry, at) == null
}

/**
 * The approval-notifier seam (design section 8.4, D23): a card that comes while its chat is off screen is put
 * in the notified set and handed to the notifier once, its replays on later `hello`s never again; a chat on
 * screen shows the card and nothing is notified; a notifier that can post nothing leaves the set alone, so a
 * later chance is not lost. A4 posts the notification; here it is a fake.
 */
class ApprovalAlertsTest {
    @Test
    fun `a card off screen is notified once, its replay never again`() =
        runTest {
            val notifier = FakeApprovalNotifier()
            val set = MemoryNotified()
            val alerts = ApprovalAlerts({ _, _ -> false }, notifier) { 42L }
            assertTrue(alerts.arrived(CHAT, CARD, set::put))
            assertFalse(alerts.arrived(CHAT, CARD, set::put), "a replayed card alerts again")
            assertEquals(listOf(CHAT to CARD), notifier.posted)
            assertEquals(mapOf<NotifiedEntry, Long>(NotifiedEntry.Approval("ap-1") to 42L), set.entries)
        }

    @Test
    fun `a card its chat shows, or one no notification can carry, is not notified nor put`() =
        runTest {
            val notifier = FakeApprovalNotifier()
            val set = MemoryNotified()
            val shown = ApprovalAlerts({ id, profile -> id == CHAT && profile == MAIN_PROFILE }, notifier) { 0L }
            assertFalse(shown.arrived(CHAT, CARD, set::put))
            notifier.can = false
            val muted = ApprovalAlerts({ _, _ -> false }, notifier) { 0L }
            assertFalse(muted.arrived(CHAT, CARD, set::put))
            assertTrue(notifier.posted.isEmpty())
            assertTrue(set.entries.isEmpty())
        }

    @Test
    fun `the chats on screen say which chat shows, whatever its list holds`() {
        val chats = OnScreenChats()
        assertFalse(chats.isOnScreen(CHAT, MAIN_PROFILE))
        val presence = chats.presence(CHAT, MAIN_PROFILE)
        presence.report(0uL)
        assertTrue(chats.isOnScreen(CHAT, MAIN_PROFILE))
        presence.report(null)
        assertFalse(chats.isOnScreen(CHAT, MAIN_PROFILE))
    }
}
