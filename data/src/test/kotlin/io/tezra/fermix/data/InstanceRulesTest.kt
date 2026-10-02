package io.tezra.fermix.data

import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The instance records' rules, pure: a pairing and the merge of design section 9.2, the nickname of
 * section 13.7, and "Move to top".
 */
class InstanceRulesTest {
    private val mac = instance(gateway = 1, host = "suj-mbp", nickname = "Studio", tint = "Plum")
    private val linux = instance(gateway = 3, host = "linux-box", tint = "Sage")
    private val dev = instance(gateway = 5, host = "suj-mbp", tint = "Clay").copy(profile = "fermix-dev")

    @Test
    fun `a daemon paired for the first time is added last, and replaces nothing`() {
        val plan = planUpsert(listOf(mac, linux), dev)
        assertEquals(listOf(mac, linux, dev), plan.instances)
        assertNull(plan.replaced)
    }

    @Test
    fun `the same daemon paired again replaces its record in place, keeping its nickname and tint`() {
        val again = instance(gateway = 1, host = "suj-mbp", tint = "Ocean", keyAlias = "fermix.device.1.ffffffff")
        val plan = planUpsert(listOf(mac, linux), again)
        assertEquals(listOf(again.copy(nickname = "Studio", tint = "Plum"), linux), plan.instances)
        assertEquals(mac, plan.replaced)
    }

    @Test
    fun `the same daemon under the key alias its record holds is no pairing, and is refused`() {
        // A pairing always brings a new alias (design section 6.1), and upsert hands back the old one to delete.
        assertThrows<IllegalArgumentException> { planUpsert(listOf(mac, linux), mac.copy(label = "suj-mini")) }
    }

    @Test
    fun `a new gateway key on a host and profile this phone holds is another daemon until the owner merges it`() {
        // Two daemons on one computer can share a host and a profile; section 9.2 keeps them apart by nickname.
        val second = instance(gateway = 7, host = "suj-mbp")
        val plan = planUpsert(listOf(mac, linux), second)
        assertEquals(listOf(mac, linux, second), plan.instances)
        assertNull(plan.replaced)
    }

    @Test
    fun `a reinstalled daemon merges into the row the owner paired again, which keeps its place, nickname and tint`() {
        val reinstalled = instance(gateway = 7, host = "suj-mbp", tint = "Ocean")
        val plan = planMerge(listOf(mac, linux), mac.id, reinstalled)
        assertEquals(listOf(reinstalled.copy(nickname = "Studio", tint = "Plum"), linux), plan.instances)
        assertEquals(mac, plan.replaced)
    }

    @Test
    fun `a merge keeps a nickname the new pairing was given when the old row had none`() {
        val reinstalled = instance(gateway = 7, host = "linux-box", nickname = "Box")
        val plan = planMerge(listOf(linux), linux.id, reinstalled)
        assertEquals("Box", plan.instances.single().nickname)
        assertEquals("Sage", plan.instances.single().tint)
    }

    @Test
    fun `a merge into a row that is not there, or of a daemon another row holds, is refused`() {
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac), linux.id, instance(gateway = 7)) }
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac, linux), mac.id, linux) }
    }

    @Test
    fun `a merge under the key alias the replaced row holds is refused, since that row's alias is deleted`() {
        // A reinstalled daemon under the old row's alias, and the row's own daemon under its own alias: either
        // would hand the live Keystore key back for deletion, and the next launch would drop the row.
        val underOldAlias = instance(gateway = 7, keyAlias = mac.keyAlias)
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac, linux), mac.id, underOldAlias) }
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac, linux), mac.id, mac.copy(label = "suj-mini")) }
    }

    @Test
    fun `the row's own daemon paired again through a merge keeps the row, as an upsert would`() {
        val again = mac.copy(tint = "Ocean", keyAlias = "fermix.device.1.ffffffff")
        val plan = planMerge(listOf(mac, linux), mac.id, again)
        assertEquals(listOf(again.copy(tint = "Plum"), linux), plan.instances)
        assertEquals(mac, plan.replaced)
    }

    @Test
    fun `a pairing that brings a nickname another Fermix on this phone is titled with is refused`() {
        // mac is titled Studio and dev suj-mbp, whatever the case.
        val all = listOf(mac, linux, dev)
        assertThrows<IllegalArgumentException> { planUpsert(all, instance(gateway = 7, nickname = "studio")) }
        assertThrows<IllegalArgumentException> { planUpsert(all, instance(gateway = 7, nickname = "SUJ-MBP")) }
        val linuxAgain = linux.copy(nickname = "Studio", keyAlias = "fermix.device.3.ffffffff")
        assertThrows<IllegalArgumentException> { planUpsert(listOf(mac, linux), linuxAgain) }
        val reinstalled = instance(gateway = 7, host = "linux-box", nickname = "STUDIO")
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac, linux), linux.id, reinstalled) }
    }

    @Test
    fun `a pairing's nickname that only the row it replaces is titled with, or a row's kept nickname, is accepted`() {
        val reinstalled = instance(gateway = 7, host = "linux-box", nickname = "linux-box")
        assertEquals("linux-box", planMerge(listOf(mac, linux), linux.id, reinstalled).instances[1].nickname)
        // The row's own nickname is kept as it is, whatever the pairing brings: it is no new name.
        val macAgain = mac.copy(nickname = "Box", keyAlias = "fermix.device.1.ffffffff")
        assertEquals("Studio", planUpsert(listOf(mac, linux), macAgain).instances[0].nickname)
        val box = instance(gateway = 7, nickname = "Box")
        assertEquals("Box", planUpsert(listOf(mac, linux), box).instances[2].nickname)
    }

    @Test
    fun `a daemon of another profile never merges into a row, which would hand it the row's name`() {
        val otherProfile = instance(gateway = 7, host = "suj-mbp").copy(profile = "fermix-dev")
        assertThrows<IllegalArgumentException> { planMerge(listOf(mac, linux), mac.id, otherProfile) }
    }

    @Test
    fun `an update changes what the daemon reports and this phone's settings, in place`() {
        val lan = Candidate("192.168.1.20", Candidate.Scope.LAN, Candidate.Kind.IP)
        val reported: (Instance) -> Instance = {
            it.copy(
                host = "suj-mini",
                label = "Suj's Mini",
                profile = "fermix-home",
                candidates = listOf(lan, TAILNET_CANDIDATE),
                port = 4032,
                pushPlatforms = emptyList(),
                caps = helloAckCaps(),
                notificationsEnabled = false,
                fcmRegisteredAt = 1_790_000_000_000L,
            )
        }
        assertEquals(listOf(reported(mac), linux), updated(listOf(mac, linux), mac.id, reported))
    }

    @Test
    fun `an update that touches what a pairing set, or the owner's nickname or tint, is refused`() {
        val refused =
            mapOf<String, (Instance) -> Instance>(
                "gateway_pk" to { it.copy(gatewayPk = base64(key(9))) },
                "tls_fp" to { it.copy(tlsFp = hexOf(key(9))) },
                "device_id" to { it.copy(deviceId = "device-9") },
                "key_alias" to { it.copy(keyAlias = "fermix.device.1.ffffffff") },
                "push_salt" to { it.copy(pushSalt = base64(key(9))) },
                "nickname" to { it.copy(nickname = "Box") },
                "tint" to { it.copy(tint = "Ocean") },
            )
        refused.forEach { (field, change) ->
            val refusal = assertThrows<IllegalArgumentException> { updated(listOf(mac, linux), mac.id, change) }
            assertTrue(refusal.message.orEmpty().contains(field), "the refusal of $field: ${refusal.message}")
        }
        assertThrows<IllegalArgumentException> { updated(listOf(mac), linux.id) { it } }
    }

    @Test
    fun `a nickname is refused blank, past 40 characters, or when another Fermix on this phone has it`() {
        val others = listOf(mac, linux, dev)
        assertEquals(NicknameRefusal.BLANK, nicknameRefusal("  ", linux.id, others))
        assertEquals(NicknameRefusal.TOO_LONG, nicknameRefusal("x".repeat(41), linux.id, others))
        assertEquals(NicknameRefusal.TAKEN, nicknameRefusal("studio", linux.id, others))
        // A title is the nickname, or the label when there is none: dev is titled suj-mbp.
        assertEquals(NicknameRefusal.TAKEN, nicknameRefusal("SUJ-MBP", linux.id, others))
    }

    @Test
    fun `a nickname of 40 characters, emoji counted as one each, or the instance's own title, is accepted`() {
        val others = listOf(mac, linux, dev)
        assertNull(nicknameRefusal("x".repeat(40), linux.id, others))
        assertNull(nicknameRefusal("🖥".repeat(40), linux.id, others))
        assertNull(nicknameRefusal("Studio", mac.id, others))
        assertNull(nicknameRefusal("linux-box", linux.id, others))
    }

    @Test
    fun `a rename sets the nickname, and a reset clears it back to the gateway's name`() {
        assertEquals(listOf(mac, linux.copy(nickname = "Box")), renamed(listOf(mac, linux), linux.id, "Box"))
        assertEquals(listOf(mac.copy(nickname = null), linux), renamed(listOf(mac, linux), mac.id, null))
        assertThrows<IllegalArgumentException> { renamed(listOf(mac), linux.id, "Box") }
    }

    @Test
    fun `move to top puts the row first and keeps the others' order`() {
        assertEquals(listOf(dev, mac, linux), moved(listOf(mac, linux, dev), dev.id, 0))
        assertEquals(listOf(linux, mac, dev), moved(listOf(mac, linux, dev), mac.id, 1))
        assertThrows<IllegalArgumentException> { moved(listOf(mac, linux), dev.id, 0) }
        assertThrows<IllegalArgumentException> { moved(listOf(mac, linux), mac.id, 2) }
    }

    /** The vendored hello_ack's caps, which a record keeps as its snapshot. */
    private fun helloAckCaps() = fixtureServerEvents().filterIsInstance<ServerEvent.HelloAck>().single().caps
}
