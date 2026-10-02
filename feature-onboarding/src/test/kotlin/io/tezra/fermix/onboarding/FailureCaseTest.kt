package io.tezra.fermix.onboarding

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** One row of the failure table as the screen reads it: title, body, primary action, secondary actions. */
private data class Row(
    val title: String,
    val body: String?,
    val primary: String,
    val secondaries: List<String>,
)

/** FailurePreviews.kt, where each case has its preview. */
private const val FAILURE_PREVIEWS = "io.tezra.fermix.onboarding.FailurePreviewsKt"

private const val PASTE = "Paste a pairing link"
private const val HELP = "Troubleshooting"

/**
 * Design section 13.3's failure table, copied from the design by hand, every row verbatim with suj-mbp
 * as the host: the screens' copy comes from FailureCase and strings.xml, and this holds both to it.
 */
private val TABLE =
    mapOf(
        FailureCase.EXPIRED to
            Row(
                "This code has expired",
                "Pairing codes last 2 minutes. Start pairing again on your computer.",
                "Scan again",
                listOf(PASTE, HELP),
            ),
        FailureCase.DENIED to
            Row(
                "Pairing was declined",
                "Someone declined this phone on suj-mbp. If that wasn't you, check who has access to that computer.",
                "Start over",
                listOf(PASTE, HELP),
            ),
        FailureCase.RATE_LIMITED to
            Row(
                "Too many attempts",
                "suj-mbp paused pairing after several failed tries. Start a new pairing on your computer.",
                "Start over",
                listOf(PASTE, HELP),
            ),
        FailureCase.ANOTHER_PAIRING to
            Row(
                "suj-mbp is already pairing a device",
                "Finish or cancel that one first.",
                "Try again",
                listOf(PASTE, HELP),
            ),
        FailureCase.CANT_REACH to
            Row(
                "Can't reach suj-mbp",
                "Join the same Wi-Fi as your computer, or turn on Tailscale on this phone.",
                "Try again",
                listOf("Open Tailscale", PASTE, HELP),
            ),
        FailureCase.EXCLUDED_FROM_TAILSCALE to
            Row(
                "Fermix is excluded from Tailscale",
                "Turn off split tunneling for Fermix in the Tailscale app.",
                "Open Tailscale",
                listOf(PASTE, HELP),
            ),
        FailureCase.VPN_HOLDS_THE_SLOT to
            Row(
                "A VPN is on",
                "Android runs one VPN at a time, so Tailscale can't connect while it's active.",
                "Open VPN settings",
                listOf(PASTE, HELP),
            ),
        FailureCase.WRONG_MACHINE to
            Row(
                "This isn't the machine the code came from",
                "Something between this phone and suj-mbp presented a different identity. Nothing was sent.",
                "Start over",
                listOf(HELP),
            ),
        FailureCase.OLDER_FERMIX to
            Row("Update Fermix on suj-mbp", "This app needs a newer Fermix on your computer.", "OK", listOf(HELP)),
        FailureCase.NEWER_FERMIX to Row("Update Fermix on this phone", null, "Open release page", listOf(HELP)),
        FailureCase.ATTESTATION_REFUSED to
            Row(
                "This phone couldn't prove it's secure",
                "suj-mbp only pairs phones whose keys live in secure hardware, with a locked bootloader, running the " +
                    "official Fermix app. Rooted or unlocked phones, emulators and rebuilt apps can't pair.",
                "Learn more",
                listOf(HELP),
            ),
        FailureCase.ATTESTATION_UNAVAILABLE to
            Row(
                "suj-mbp couldn't verify this phone right now",
                "It couldn't reach Google's attestation service. Try again in a minute.",
                "Try again",
                listOf(PASTE, HELP),
            ),
        FailureCase.NO_SECURE_HARDWARE to
            Row(
                "This phone can't hold a Fermix key",
                "Fermix needs Android 15 or newer with hardware Curve25519 support.",
                "OK",
                listOf(HELP),
            ),
        FailureCase.LOST_MID_WAIT to
            Row(
                "Lost the connection while waiting",
                "Your approval didn't reach this phone. Start pairing again.",
                "Start over",
                listOf(PASTE, HELP),
            ),
    )

/** The fourteen rows of section 13.3's failure table, as FailureCase and strings.xml give them, on Robolectric. */
@RunWith(RobolectricTestRunner::class)
class FailureCaseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun rowOf(case: FailureCase): Row =
        Row(
            title = context.getString(case.title, HOST),
            body = case.body?.let { context.getString(it, HOST) },
            primary = context.getString(case.primary.label),
            secondaries = case.secondaries.map { context.getString(it.label) },
        )

    @Test
    fun `every row of the table is drawn verbatim`() {
        assertEquals(14, TABLE.size)
        for ((case, row) in TABLE) assertEquals("$case", row, rowOf(case))
    }

    @Test
    fun `the one case beyond the table is the daemon's protocol error, with the generic title`() {
        assertEquals(setOf(FailureCase.PROTOCOL_ERROR), FailureCase.entries.toSet() - TABLE.keys)
        assertEquals(
            Row("Something went wrong on suj-mbp", null, "Start over", listOf(PASTE, HELP)),
            rowOf(FailureCase.PROTOCOL_ERROR),
        )
    }

    @Test
    fun `the security event alone is red`() {
        assertEquals(listOf(FailureCase.WRONG_MACHINE), FailureCase.entries.filter { it.alert })
    }

    @Test
    fun `the refusals play REJECT, a failure where nothing answered does not`() {
        val quiet =
            setOf(
                FailureCase.CANT_REACH,
                FailureCase.EXCLUDED_FROM_TAILSCALE,
                FailureCase.VPN_HOLDS_THE_SLOT,
                FailureCase.NO_SECURE_HARDWARE,
                FailureCase.LOST_MID_WAIT,
                FailureCase.PROTOCOL_ERROR,
            )
        assertEquals(FailureCase.entries.toSet() - quiet, FailureCase.entries.filter { it.refusal }.toSet())
    }

    @Test
    fun `every case has a screenshot of its own, named for it`() {
        val previews =
            Class
                .forName(FAILURE_PREVIEWS)
                .declaredMethods
                .map { it.name }
                .toSet()
        for (case in FailureCase.entries) {
            val words = case.name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
            assertTrue("$case has no preview", "Failure${words}Preview" in previews)
        }
    }
}
