package io.tezra.fermix.onboarding

import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.transport.linkCandidate

/** The link version this app pairs with (design section 13.3, step 3: `v = 2`; section 7's `v` row). */
private const val LINK_VERSION = 2

/**
 * What a scanned QR code or a pasted text is, read on the phone before any key exists (design section
 * 13.3, step 3): a link the ceremony takes, a link of an older or a newer Fermix, or one the scan refuses.
 * The camera and the paste sheet read their text into one of these, and the ceremony driver takes it
 * alike, so the two ways in run the identical ceremony with identical trust (step 2).
 */
sealed interface LinkOutcome {
    /**
     * A version-2 link with every field, its secret 32 bytes and its candidates in the LAN and tailnet
     * ranges: the ceremony's, which zeroes the secret.
     */
    class Link(
        val link: PairingLink,
    ) : LinkOutcome

    /** Not a pairing link at all: "That's not a Fermix pairing code." */
    data object NotAFermixCode : LinkOutcome

    /** A version-1 link, an older Fermix's: its "Update Fermix on [host]" screen. */
    data class OlderFermix(
        val host: String,
    ) : LinkOutcome

    /** A link of a version past 2: the "Update Fermix on this phone" screen. */
    data object NewerFermix : LinkOutcome

    /** A pairing link whose [field] is missing, repeated or malformed, or names no candidate in range. */
    data class Invalid(
        val field: String,
    ) : LinkOutcome
}

/**
 * Whether the scan refuses this with "That's not a Fermix pairing code.": a text that is no Fermix link the
 * phone acts on, neither a ceremony nor an Older or Newer Fermix screen.
 */
val LinkOutcome.refused: Boolean
    get() = this is LinkOutcome.NotAFermixCode || this is LinkOutcome.Invalid

/**
 * Reads [text] as core-protocol's [PairingLink] and checks what the phone can before any key exists: the
 * version first, then the candidates' ranges, core-transport's [linkCandidate] (the ceremony checks them
 * again). A link this returns as anything but [LinkOutcome.Link] has its secret zeroed here.
 */
fun readLink(text: String): LinkOutcome {
    val link =
        try {
            PairingLink.parse(text)
        } catch (refused: ProtocolException) {
            return outcomeOf(refused)
        }
    val outcome =
        when {
            link.version < LINK_VERSION -> {
                LinkOutcome.OlderFermix(link.name)
            }

            link.candidates.isEmpty() || link.candidates.any { linkCandidate(it) == null } -> {
                LinkOutcome.Invalid("candidates")
            }

            else -> {
                LinkOutcome.Link(link)
            }
        }
    if (outcome !is LinkOutcome.Link) link.secret.fill(0)
    return outcome
}

/** The outcome of a link [PairingLink.parse] refused, by the refusal's type. */
private fun outcomeOf(refused: ProtocolException): LinkOutcome =
    when (refused) {
        is ProtocolException.NotAPairingLink -> LinkOutcome.NotAFermixCode
        is ProtocolException.NewerLinkVersion -> LinkOutcome.NewerFermix
        is ProtocolException.MissingParameter -> LinkOutcome.Invalid(refused.name)
        is ProtocolException.MalformedParameter -> LinkOutcome.Invalid(refused.name)
        is ProtocolException.RepeatedParameter -> LinkOutcome.Invalid(refused.name)
        else -> throw IllegalStateException("a pairing link is never refused as ${refused::class.simpleName}", refused)
    }
