package io.tezra.fermix.chat

import io.tezra.fermix.protocol.MAX_HEADER_BYTES
import io.tezra.fermix.session.MAX_ATTACHMENTS

/** Another app's share of one item, or of words (Intent.ACTION_SEND). */
private const val ACTION_SEND = "android.intent.action.SEND"

/** Another app's share of several items (Intent.ACTION_SEND_MULTIPLE). */
private const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

/**
 * The most characters of a share's words that are kept as it is read: no `msg` carries more, as every character is a
 * byte at least of its header ([MAX_HEADER_BYTES]); the composer cuts them to what one `msg` takes as they land
 * (withSharedWords).
 */
const val MAX_SHARED_CHARS = MAX_HEADER_BYTES

/** One URI a share carries: its text, and its scheme and authority as android.net.Uri parses them. */
data class SharedUri(
    val uri: String,
    val scheme: String?,
    val authority: String?,
)

/**
 * A share as the app's share entry read its intent: its [action], the [type] its sender named, the URIs it carries
 * in order ([streams]: `EXTRA_STREAM`, one or a list) and its words (`EXTRA_TEXT`). Nothing else of the intent is
 * read.
 */
data class ShareInput(
    val action: String?,
    val type: String?,
    val streams: List<SharedUri>,
    val text: String?,
)

/**
 * What lands of a share (design section 13.6, "Share into Fermix"): the [uris] the chat may read, at most
 * [MAX_ATTACHMENTS], in order; its [words], none when it has none; each URI refused, by its scheme and authority
 * alone ([refused]); and how many came past the ten ([past]), which are never looked at.
 */
data class Shared(
    val uris: List<String>,
    val words: String?,
    val refused: List<String>,
    val past: Int,
) {
    init {
        require(uris.size <= MAX_ATTACHMENTS) { "${uris.size} items land of a share" }
        require(past >= 0) { "$past past the ten" }
    }
}

/**
 * What lands of [input], none for an intent that is no share (`ACTION_SEND` or `ACTION_SEND_MULTIPLE`) or one that
 * carries nothing. Its first [MAX_ATTACHMENTS] URIs are weighed by the one check, mayRead, as they land from a share:
 * another app's `content:` URI, whose authority, without its `user@` prefix, names no provider of the app's own
 * ([own]); a `file:` URI, one of the app's own providers and any other scheme are refused. Its words are words, cut to
 * [MAX_SHARED_CHARS] on a character's edge, and never read as a link, a command or an intent. The type its sender named
 * decides nothing.
 */
fun sharedOf(
    input: ShareInput,
    own: (String) -> Boolean,
): Shared? {
    val words = input.text?.takeIf { it.isNotBlank() }?.let { cutAtCharacter(it, MAX_SHARED_CHARS) }
    val share = input.action == ACTION_SEND || input.action == ACTION_SEND_MULTIPLE
    if (!share || (input.streams.isEmpty() && words == null)) return null
    val weighed = input.streams.take(MAX_ATTACHMENTS)
    val (taken, refused) =
        weighed.partition {
            mayRead(it.scheme, it.authority, PickedFrom.SHARE, own, inCache = { false })
        }
    return Shared(
        uris = taken.map { it.uri },
        words = words,
        refused = refused.map { outsideOf(it.scheme, it.authority) },
        past = input.streams.size - weighed.size,
    )
}

/** [text]'s first [max] characters at most, never ending between a character's two halves. */
internal fun cutAtCharacter(
    text: String,
    max: Int,
): String {
    if (text.length <= max) return text
    val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
    return text.substring(0, end)
}

/** A URI as a refusal names it: its scheme and its authority, never its path, which can say what the phone holds. */
internal fun outsideOf(
    scheme: String?,
    authority: String?,
): String = "$scheme URI of ${authority.orEmpty().ifEmpty { "no authority" }}"
