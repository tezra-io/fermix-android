package io.tezra.fermix.data

import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The instance record on disk (design sections 9.1 and 12.4): public data, field for field the design's,
 * never a secret; and what a pairing and a `hello_ack` give it reads back as it was written.
 */
class InstanceRecordTest {
    /** The vendored pairing link, its first, without a route: its `secret` is the one-time pairing PSK. */
    private val link =
        PairingLink.parse(
            Json
                .parseToJsonElement(vendored("fixtures/pairing_links.jsonl").lines().first { it.isNotBlank() })
                .jsonObject
                .getValue("uri")
                .jsonPrimitive.content,
        )

    /** The vendored first hello_ack, whose caps the record keeps as its snapshot. */
    private val helloAck = fixtureServerEvents().filterIsInstance<ServerEvent.HelloAck>().first()

    /** The record a pairing through [link] makes, with the caps of the first hello_ack after it. */
    private val paired =
        instance(gateway = 1).copy(
            gatewayPk = base64(link.gatewayPublicKey),
            tlsFp = hexOf(link.tlsFingerprint),
            host = link.name,
            label = link.name,
            port = link.port,
            candidates =
                link.candidates.map { host ->
                    Candidate.Host(host, Candidate.Scope.TAILNET, Candidate.Kind.NAME)
                },
            caps = helloAck.caps,
        )

    @Test
    fun `the record's fields are sections 9_1's, 13_7's This phone and 10's full pull, and none is a secret`() {
        val names = serializer<Instance>().descriptor.elementNames.toList()
        val expected =
            listOf(
                "gateway_pk",
                "tls_fp",
                "host",
                "profile",
                "label",
                "nickname",
                "tint",
                "candidates",
                "port",
                "device_id",
                "key_alias",
                "push_salt",
                "push_platforms",
                "caps",
                "notifications_enabled",
                "fcm_registered_at",
                "history_pull_due",
                "device_name",
                "paired_at",
                "last_candidate",
                "picture_at",
                "eyes",
                "route",
            )
        assertEquals(expected, names)
        val secretName = Regex("secret|psk|private|token|password", RegexOption.IGNORE_CASE)
        names.forEach { name -> assertFalse(secretName.containsMatchIn(name), name) }
    }

    @Test
    fun `the records written to disk hold no pairing secret, private key or push token`() =
        runTest {
            // The noise vectors' phone static private key, as a pairing's handshake would hold it in software.
            val vectors = Json.parseToJsonElement(vendored("noise_vectors.json")).jsonObject
            val phonePrivateKey =
                vectors
                    .getValue("vectors")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("init_static_private")
                    .jsonPrimitive.content
            val fcmToken = "dQw4w9WgXcQ:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx"
            val text = written(Instances(listOf(paired.copy(fcmRegisteredAt = 1_790_000_000_000L))))
            val secrets = listOf(base64(link.secret), hexOf(link.secret), phonePrivateKey, fcmToken)
            secrets.forEach { secret -> assertFalse(text.contains(secret), "the records hold $secret") }
            assertTrue(text.contains(base64(link.gatewayPublicKey)), "the records do not hold the gateway key")
        }

    @Test
    fun `a record reads back as written, caps, candidates, nickname, last candidate, picture and eyes included`() =
        runTest {
            val named =
                paired.copy(
                    nickname = "Mini",
                    tint = "Teal",
                    deviceName = "Pixel 9 Pro",
                    pairedAt = 1_790_000_000_000L,
                    lastCandidate = paired.candidates.last(),
                    pictureAt = 1_790_000_100_000L,
                    eyes = "Sleepy",
                )
            val records = Instances(listOf(named, instance(gateway = 3)))
            val text = written(records)
            assertEquals(records, InstancesSerializer.readFrom(Buffer().write(text.encodeToByteArray())))
        }

    // The owner, 2026-10-11: eight brighter tints in place of the six. A record a build before them wrote names one of
    // the six, and DataStore would hand a refused one to every reader as corruption, never replace it: each old name
    // reads as the new one of its hue, and a record with no eyes, written before them, reads Open.
    @Test
    fun `a record under one of the six old tint names reads as the new one of its hue, and with no eyes as Open`() =
        runTest {
            val hues =
                mapOf("Slate" to "Slate", "Sage" to "Olive", "Clay" to "Coral", "Plum" to "Iris", "Ocean" to "Sky")
                    .plus("Sand" to "Amber")
            for ((old, new) in hues) {
                val text = written(Instances(listOf(instance(gateway = 1, tint = new))))
                val stored =
                    text.replace("\"tint\":\"$new\"", "\"tint\":\"$old\"").replace(",\"eyes\":\"Open\"", "")
                assertTrue("\"$old\"" in stored && "eyes" !in stored, stored)
                val read = InstancesSerializer.readFrom(Buffer().write(stored.encodeToByteArray()))
                assertEquals(new, read.instances.single().tint, old)
                assertEquals("Open", read.instances.single().eyes, old)
                assertTrue("\"tint\":\"$new\"" in written(read), "$old is written back as $new")
            }
        }

    @Test
    fun `a record stored under a new name reads as itself`() =
        runTest {
            for (name in TINT_NAMES) {
                val records = Instances(listOf(instance(gateway = 1, tint = name)))
                val text = written(records)
                assertEquals(records, InstancesSerializer.readFrom(Buffer().write(text.encodeToByteArray())))
            }
        }

    @Test
    fun `a record holds its route, and its last candidate may be the route`() =
        runTest {
            val routed = paired.copy(route = ROUTE, lastCandidate = ROUTE)
            val records = Instances(listOf(routed, instance(gateway = 3)))
            val text = written(records)
            assertTrue("\"node_id\":\"${ROUTE.nodeId}\"" in text, text)
            assertEquals(records, InstancesSerializer.readFrom(Buffer().write(text.encodeToByteArray())))
        }

    @Test
    fun `a record written before the route reads with none`() =
        runTest {
            val text = written(Instances(listOf(instance(gateway = 1))))
            assertTrue("route" !in text, text)
            val read = InstancesSerializer.readFrom(Buffer().write(text.encodeToByteArray()))
            assertEquals(null, read.instances.single().route)
        }

    @Test
    fun `a record is keyed by its id, so a second record with the same gateway key is refused`() {
        val duplicate = instance(gateway = 1, host = "other")
        assertThrows<IllegalArgumentException> { Instances(listOf(instance(gateway = 1), duplicate)) }
    }

    @Test
    fun `two records under one key alias are refused, since deleting one's alias would cut the other off`() {
        val sharing = instance(gateway = 3, host = "linux-box", keyAlias = instance(gateway = 1).keyAlias)
        assertThrows<IllegalArgumentException> { Instances(listOf(instance(gateway = 1), sharing)) }
    }

    @Test
    fun `a repair notice is keyed by an instance id, once, and never names a record that is here`() =
        runTest {
            val dropped = RepairNotice(instance(gateway = 3).id, "suj-mbp")
            val records = Instances(listOf(instance(gateway = 1)), listOf(dropped))
            val text = written(records)
            assertEquals(records, InstancesSerializer.readFrom(Buffer().write(text.encodeToByteArray())))
            assertThrows<IllegalArgumentException> { RepairNotice("suj-mbp", "suj-mbp") }
            assertThrows<IllegalArgumentException> { Instances(repairNotices = listOf(dropped, dropped)) }
            val here = RepairNotice(instance(gateway = 1).id, "suj-mbp")
            assertThrows<IllegalArgumentException> { Instances(listOf(instance(gateway = 1)), listOf(here)) }
        }

    private suspend fun written(records: Instances): String {
        val output = Buffer()
        InstancesSerializer.writeTo(records, output)
        return output.readUtf8()
    }
}
