package io.tezra.fermix.demo

import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.transport.Candidate
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import io.tezra.fermix.protocol.Candidate as WireCandidate

/** The port every demo Fermix names in its link: a daemon's default (PROTOCOL.md "Transport"). */
const val DEMO_PORT = 4031

/** The interface a demo Fermix's route names. */
private const val INTERFACE = "utun4"

/** The tailnet addresses of the demo Fermixes: 100.100.51.10 and on, in the tailnet's 100.64.0.0/10. */
private const val ADDRESS_PREFIX = "100.100.51."
private const val FIRST_HOST_OCTET = 10

/**
 * One Fermix of the demo, as its owner would name it: its computer [host], its home's [profile] (design D11), the
 * agent's name its `hello_ack` gives ([agent], "Fermix" unless the owner named it), and its [script]. Its keys,
 * its pin and its push salt are derived from the demo's [seed] and its [index], so every run of the demo, the app
 * restarted included, is the same daemon to the phone; they are the demo's, public, and pin nothing real.
 */
class DemoFermix(
    val index: Int,
    val host: String,
    val profile: String,
    val agent: String,
    val script: FermixScript,
    private val seed: Long,
) {
    val gatewayKey: SoftwareKey = SoftwareKey.fromScalar(derived(seed, index, "gateway"))

    /** The pin its link names: no certificate's, as no TLS runs here, and only the demo answers a dial with it. */
    val tlsFingerprint: ByteArray = demoPin(seed, index)

    /** The salt its approval gives each phone's push key; the demo pushes nothing. */
    val pushSalt: ByteArray = derived(seed, index, "push")

    /** Its one route, a tailnet address no network answers: only the demo's dialer does. */
    val address: String = ADDRESS_PREFIX + (FIRST_HOST_OCTET + index)

    val route: Candidate = Candidate(address, Candidate.Scope.TAILNET, Candidate.Kind.IP)

    val wireRoute: WireCandidate = WireCandidate(address, INTERFACE, CandidateScope.TAILNET)

    /** A pairing window's one-time secret, the [window]th this Fermix opened in this run. */
    fun secret(window: Int): ByteArray = derived(seed, index, "secret", window)

    /**
     * The pairing link of window [window] as a daemon writes it (PROTOCOL.md "Pairing link", v2 with its
     * `profile`), form-encoded.
     */
    fun link(window: Int): String {
        val parameters =
            listOf(
                "v" to "2",
                "candidates" to "[\"$address\"]",
                "port" to "$DEMO_PORT",
                "tls_fp" to tlsFingerprint.toHexString(),
                "gateway_pk" to base64(gatewayKey.publicKey),
                "secret" to base64(secret(window)),
                "name" to host,
                "profile" to profile,
            )
        val query =
            parameters.joinToString(
                "&",
            ) { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "fermix://pair?$query"
    }
}

/** How many Fermixes the demo has ([demoFermixes]). */
const val DEMO_FERMIXES = 6

/**
 * The pin of the demo's Fermix [index] for [seed], its link's `tls_fp`: a digest alone, so the debug app tells a
 * demo pin from a real daemon's without making the demo's Fermixes, their keys or their scripts.
 */
fun demoPin(
    seed: Long,
    index: Int,
): ByteArray = derived(seed, index, "tls")

/** The pins of every demo Fermix for [seed], in [demoFermixes]' order. */
fun demoPins(seed: Long): List<ByteArray> = (0 until DEMO_FERMIXES).map { demoPin(seed, it) }

/** The bytes of Fermix [index]'s [purpose], the [counter]th, from the demo's [seed]: the same every run. */
private fun derived(
    seed: Long,
    index: Int,
    purpose: String,
    counter: Int = 0,
): ByteArray {
    val seedBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array()
    return sha256("fermix-demo $purpose $index $counter".encodeToByteArray() + seedBytes)
}

/** [bytes] in standard base64 with its padding, as the wire writes keys. */
internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * The demo's six Fermixes, in the order its entry offers their links: two homes on one computer, whose names
 * collide so that the second pairing reaches Name, then four more, each a chat of its own on the Chats list.
 */
fun demoFermixes(seed: Long): List<DemoFermix> =
    listOf(
        DemoFermix(index = 0, host = "suj-mbp", "fermix", "Fermix", mainScript(), seed),
        DemoFermix(index = 1, host = "suj-mbp", "fermix-dev", "Fermix", devScript(), seed),
        DemoFermix(index = 2, host = "studio", "fermix", "Fermix", studioScript(), seed),
        DemoFermix(index = 3, host = "nas", "fermix", "Fermix", nasScript(), seed),
        DemoFermix(index = 4, host = "build-box", "fermix", "Fermix", buildScript(), seed),
        DemoFermix(index = 5, host = "pi-garden", "fermix", "Basil", gardenScript(), seed),
    ).also { check(it.size == DEMO_FERMIXES) { "the demo has $DEMO_FERMIXES Fermixes" } }
