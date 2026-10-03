package io.tezra.fermix.chat

import io.tezra.fermix.data.Instance
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.NetworkFacts

/** What a chat's app bar says under its title (design section 13.5). */
sealed interface ChatLine {
    /** The link's words: waiting, connecting, can't reach, updating, the path, or a state that ended. */
    data class Of(
        val link: Link,
    ) : ChatLine

    data object Thinking : ChatLine

    data object Nothing : ChatLine
}

/**
 * Section 13.5's subtitle priority: "Waiting for network…", "Connecting…", "Can't reach {host}" and
 * "Updating…" first, then "thinking…", then the path, "Tailscale · 38 ms" or "Wi-Fi · 9 ms"; a session that
 * ended says how.
 */
fun chatLine(
    link: Link,
    thinking: Boolean,
): ChatLine =
    when {
        link is Link.Up && link.caughtUp -> if (thinking) ChatLine.Thinking else ChatLine.Of(link)
        link == Link.NotOpen || link == Link.Closed -> if (thinking) ChatLine.Thinking else ChatLine.Nothing
        else -> ChatLine.Of(link)
    }

/** A chat's bar as it reads: [record]'s title, tint and host, its [link], and whether a turn is [thinking]. */
data class ChatHeader(
    val record: Instance,
    val link: Link,
    val thinking: Boolean,
)

/** The one-line banner under the app bar (design section 13.5): offline, or the computer out of reach. */
enum class Banner { OFFLINE, UNREACHABLE }

/**
 * The banner gotcha 20's two facts call for: [Banner.OFFLINE] while the phone has no network, and
 * [Banner.UNREACHABLE] while it has one and every candidate has failed for 30 s (the session's
 * [Link.CannotReach]); none otherwise, so a completed handshake clears it. The screen shows it once it has
 * held for 2 s (BANNER_DELAY_MS).
 */
fun bannerOf(
    link: Link,
    network: NetworkFacts,
): Banner? =
    when {
        !network.hasNetwork || link == Link.WaitingForNetwork -> Banner.OFFLINE
        link == Link.CannotReach -> Banner.UNREACHABLE
        else -> null
    }

/** How long a banner's condition holds before the banner shows (design section 13.5). */
const val BANNER_DELAY_MS = 2_000L
