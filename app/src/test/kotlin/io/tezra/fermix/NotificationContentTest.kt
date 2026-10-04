package io.tezra.fermix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

private val COPY =
    NotificationCopy(
        appName = "Fermix",
        newMessage = "New message",
        needsApproval = { "$it needs your approval" },
        approvalExpired = { "An approval on $it expired" },
        replyDidNotFinish = { "$it: the reply didn't finish" },
    )

private val CHAT = ConversationFacts("ab".repeat(32), "main", "${"ab".repeat(32)}:main", "suj-mbp", "Fermix")

private val LINES = listOf(NotifiedLine(3uL, "the backup finished", 10L), NotifiedLine(4uL, "New message", 20L))

/**
 * The notifications as the app builds them, before Android's builder copies them (design section 10;
 * section 13.9's words): the app lock's title-only rule over the previews setting and over the generic
 * notification, an approval's expiry, and which posts alert.
 */
class NotificationContentTest {
    @Test
    fun `with the lock off and previews on, a conversation's notification lists its rows' words`() {
        val content = messagesNotification(CHAT, LINES, previews = true, locked = false, copy = COPY)
        assertEquals(NotificationContent(CHAT, NotificationKey.Messages, "suj-mbp", lines = LINES), content)
    }

    @Test
    fun `with previews off it is the instance's name over New message`() {
        val content = messagesNotification(CHAT, LINES, previews = false, locked = false, copy = COPY)
        assertEquals(NotificationContent(CHAT, NotificationKey.Messages, "suj-mbp", "New message"), content)
    }

    @Test
    fun `with the lock on it is New message alone, previews on or off, and no word of a row is in it`() {
        for (previews in listOf(true, false)) {
            val content = messagesNotification(CHAT, LINES, previews, locked = true, copy = COPY)
            assertEquals(NotificationContent(CHAT, NotificationKey.Messages, "New message"), content)
            assertFalse("backup" in content.toString())
        }
    }

    @Test
    fun `an approval times out at its expiry, and one that comes at or after it says it expired`() {
        val live = approvalNotification(CHAT, "a1", expiresAtMs = 61_000L, nowMs = 1_000L, copy = COPY)
        assertEquals(
            NotificationContent(
                CHAT,
                NotificationKey.Approval("a1"),
                "suj-mbp needs your approval",
                timeoutAfterMs = 60_000L,
            ),
            live,
        )
        val late = approvalNotification(CHAT, "a1", expiresAtMs = 61_000L, nowMs = 61_000L, copy = COPY)
        assertEquals(NotificationContent(CHAT, NotificationKey.Approval("a1"), "An approval on suj-mbp expired"), late)
    }

    @Test
    fun `a failed turn is keyed by its turn, and the generic notification names its instance or the app`() {
        assertEquals(
            NotificationContent(CHAT, NotificationKey.TurnFailed("t1"), "suj-mbp: the reply didn't finish"),
            turnFailedNotification(CHAT, "t1", COPY),
        )
        assertEquals(
            NotificationContent(CHAT, NotificationKey.Generic, "suj-mbp", "New message", onlyAlertOnce = true),
            genericNotification(CHAT, locked = false, copy = COPY),
        )
        assertEquals(
            NotificationContent(null, NotificationKey.Generic, "Fermix", "New message", onlyAlertOnce = true),
            genericNotification(null, locked = false, copy = COPY),
        )
    }

    @Test
    fun `with the lock on the generic notification is New message alone, its instance unnamed`() {
        for (chat in listOf(CHAT, null)) {
            assertEquals(
                NotificationContent(chat, NotificationKey.Generic, "New message", onlyAlertOnce = true),
                genericNotification(chat, locked = true, copy = COPY),
            )
        }
    }

    @Test
    fun `a conversation's notification alerts unless it is a rebuild, and an approval or a turn needs its id`() {
        assertFalse(messagesNotification(CHAT, LINES, previews = true, locked = false, copy = COPY).onlyAlertOnce)
        assertThrows(IllegalArgumentException::class.java) {
            approvalNotification(CHAT, "", expiresAtMs = 2L, nowMs = 1L, copy = COPY)
        }
        assertThrows(IllegalArgumentException::class.java) { turnFailedNotification(CHAT, "", COPY) }
    }
}
