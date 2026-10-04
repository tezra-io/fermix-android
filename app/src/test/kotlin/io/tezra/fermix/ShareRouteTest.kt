package io.tezra.fermix

import io.tezra.fermix.chat.Shared
import io.tezra.fermix.chats.ChatRow
import io.tezra.fermix.chats.RowLine
import io.tezra.fermix.data.Instance
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Locale

/** A record as a pairing leaves it, its gateway key made of [gateway]: the share tests' paired Fermix. */
internal fun pairedForShare(gateway: Int): Instance =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { gateway.toByte() }),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(32),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = "c3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3M=",
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

private val ONE = pairedForShare(1)
private val TWO = pairedForShare(2)

private val WORDS = Shared(emptyList(), "look at this", emptyList(), past = 0)

private fun share(shortcut: String? = null) = Share(WORDS, shortcut)

private fun chatOf(record: Instance) = ChatKey(record.id, "main")

/** A shortcut id of the conversations' form that names no paired record: made up, or a record's long gone. */
private val UNKNOWN = "${"ab".repeat(32)}:main"

/** [record]'s row on the Chats list, its session's [link] as the row reads it. */
private fun row(
    record: Instance,
    link: Link,
    profile: String = "main",
) = ChatRow(record, profile, agentName = null, dev = false, link, RowLine.Speaks(link), time = null, unread = 0)

/** One row of the route: the shortcut a share named, the records paired, and where it goes. */
private data class RouteRow(
    val shortcut: String?,
    val records: List<Instance>,
    val goes: ShareRoute,
)

private val ROUTES =
    listOf(
        // A Direct Share names a conversation: its record's chat, looked up among the paired.
        RouteRow(conversationId(TWO.id, "main"), listOf(ONE, TWO), ShareRoute.Into(chatOf(TWO))),
        RouteRow(conversationId(ONE.id, "main"), listOf(ONE), ShareRoute.Into(chatOf(ONE))),
        // A shortcut that names no record asks, whatever it says; it never names a chat of its own.
        RouteRow(UNKNOWN, listOf(ONE, TWO), ShareRoute.Ask),
        RouteRow(UNKNOWN, listOf(ONE), ShareRoute.Ask),
        RouteRow(conversationId(TWO.id, "work"), listOf(ONE, TWO), ShareRoute.Ask),
        RouteRow("${TWO.id}:main/../x", listOf(ONE, TWO), ShareRoute.Ask),
        RouteRow("", listOf(ONE, TWO), ShareRoute.Ask),
        RouteRow(UNKNOWN, emptyList(), ShareRoute.NonePaired),
        // A generic share: straight to the one, asked among two, and nowhere with none.
        RouteRow(null, emptyList(), ShareRoute.NonePaired),
        RouteRow(null, listOf(ONE), ShareRoute.Into(chatOf(ONE))),
        RouteRow(null, listOf(ONE, TWO), ShareRoute.Ask),
    )

/**
 * Where a share goes (design sections 13.6 and 13.9) and the lock that comes first (sections 6.6 and 13.8): a Direct
 * Share's shortcut is looked up among the paired records, a generic share asks "Send to which Fermix?" unless one is
 * paired, and while the app lock holds the share waits behind it, lost once the app leaves still locked; the
 * records not yet read are no answer.
 */
class ShareRouteTest {
    @Test
    fun `each shortcut and pairing goes where the table says`() {
        val wrong = ROUTES.filter { shareRouteOf(it.shortcut, it.records) != it.goes }
        assertEquals(emptyList<RouteRow>(), wrong)
    }

    @Test
    fun `a share waits until the lock's setting and the records are known and the app is in sight, then goes`() {
        val waiting = ShareState.Pending(share(), asked = false, behindLock = false)
        assertEquals(waiting, shareAfter(waiting, Sight.UNKNOWN, listOf(ONE)))
        assertEquals(waiting, shareAfter(waiting, Sight.AWAY, listOf(ONE)))
        assertEquals(waiting, shareAfter(waiting, Sight.OPEN, records = null))
        assertEquals(ShareState.Landing(chatOf(ONE), WORDS), shareAfter(waiting, Sight.OPEN, listOf(ONE)))
    }

    @Test
    fun `the lock comes first, the share waits behind it and goes once it is passed`() {
        val waiting = ShareState.Pending(share(), asked = false, behindLock = false)
        val behind = shareAfter(waiting, Sight.LOCKED, listOf(ONE, TWO))
        assertEquals(ShareState.Pending(share(), asked = false, behindLock = true), behind)
        val asking = ShareState.Pending(share(), asked = true, behindLock = false)
        assertEquals(asking, shareAfter(behind, Sight.OPEN, listOf(ONE, TWO)))
        val shortcut = ShareState.Pending(share(conversationId(TWO.id, "main")), asked = false, behindLock = true)
        assertEquals(ShareState.Landing(chatOf(TWO), WORDS), shareAfter(shortcut, Sight.OPEN, listOf(ONE, TWO)))
    }

    @Test
    fun `a share is lost once the app leaves with the lock not passed`() {
        val behind = ShareState.Pending(share(), asked = false, behindLock = true)
        assertEquals(ShareState.None, shareAfter(behind, Sight.AWAY, listOf(ONE)))
        // The sheet was up, the app left and locked again as it came back, and left again without the unlock.
        val asking = ShareState.Pending(share(), asked = true, behindLock = false)
        val relocked = shareAfter(asking, Sight.LOCKED, listOf(ONE, TWO))
        assertEquals(ShareState.Pending(share(), asked = true, behindLock = true), relocked)
        assertEquals(ShareState.None, shareAfter(relocked, Sight.AWAY, listOf(ONE, TWO)))
    }

    @Test
    fun `an open sheet stays put while the app is away unlocked, and a share with none paired goes nowhere`() {
        val asking = ShareState.Pending(share(), asked = true, behindLock = false)
        assertEquals(asking, shareAfter(asking, Sight.OPEN, listOf(ONE, TWO)))
        assertEquals(asking, shareAfter(asking, Sight.AWAY, listOf(ONE, TWO)))
        val waiting = ShareState.Pending(share(), asked = false, behindLock = false)
        assertEquals(ShareState.None, shareAfter(waiting, Sight.OPEN, emptyList()))
    }

    @Test
    fun `a Fermix in a trust state takes no share, on the sheet, by a shortcut or as the one paired`() {
        val three = pairedForShare(3)
        val rows =
            listOf(
                row(ONE, Link.Revoked),
                row(TWO, Link.Connecting),
                row(three, Link.IdentityChanged),
                row(TWO, Link.Connecting, profile = "work"),
            )
        assertEquals(listOf(TWO), shareTargetsOf(rows))
        assertEquals(null, shareTargetsOf(null))
        // "Send to which Fermix?" lists the rows of those it may go to, each profile's, as the Chats list draws them.
        assertEquals(listOf(rows[1], rows[3]), shareRowsOf(rows))
        // The one Fermix still paired takes a generic share; a shortcut naming one in a trust state asks.
        assertEquals(ShareRoute.Into(chatOf(TWO)), shareRouteOf(null, listOf(TWO)))
        assertEquals(ShareRoute.Ask, shareRouteOf(conversationId(ONE.id, "main"), listOf(TWO)))
        // With every paired Fermix in a trust state, a share goes nowhere.
        val none = checkNotNull(shareTargetsOf(listOf(row(ONE, Link.Revoked))))
        assertEquals(ShareRoute.NonePaired, shareRouteOf(null, none))
    }

    @Test
    fun `a share landing, or none, is left as it is whatever the lock does`() {
        val landing = ShareState.Landing(chatOf(ONE), WORDS)
        for (sight in Sight.entries) {
            assertEquals(landing, shareAfter(landing, sight, listOf(ONE)))
            assertEquals(ShareState.None, shareAfter(ShareState.None, sight, listOf(ONE)))
        }
    }
}
