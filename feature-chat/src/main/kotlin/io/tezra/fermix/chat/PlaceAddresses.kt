package io.tezra.fermix.chat

import io.tezra.fermix.protocol.Place
import java.util.Locale

/**
 * A place's name goes in its `geo:` address cut to this many characters: the wire bounds no name, and a name of a
 * MiB in an intent would fail the start.
 */
internal const val MAX_PLACE_LABEL_CHARS = 256

/** The web's default ports, which the engine's reading of an address fills in when the address names none. */
private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443")

/**
 * What an address may hold as the engine reads it (Elixir's `URI.new`, RFC 3986): ASCII letters, digits and the RFC's
 * marks alone, so a space, a control, a character outside ASCII or one of `"<>\^`{|}` makes it no address. A `%` is
 * taken as it comes, its two digits unchecked, as the engine takes it.
 */
private val ADDRESS_CHARACTERS = Regex("""[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]*""")

/** RFC 3986's appendix B, which splits any address into its scheme, authority, path, query and fragment. */
private val ADDRESS_PARTS = Regex("""(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?""")

/** An authority as RFC 3986 writes it: a user before one `@`, a host (a bracketed IPv6 literal or a name), a port. */
private val AUTHORITY = Regex("""(?:([^@\[\]]*)@)?(\[[0-9A-Fa-f:.]+]|[^:@\[\]]*)(?::([0-9]*))?""")

/** RFC 3986's unreserved characters besides letters and digits: an address carries them as they are. */
private const val UNRESERVED_MARKS = "-._~"

/** A byte's eight bits, as `%XX` writes them. */
private const val BYTE_BITS = 0xFF

/** The Google Maps page a place opens as when no app answers its `geo:` address (M72 section 14). */
private const val MAPS_SEARCH = "https://www.google.com/maps/search/?api=1&query="

/**
 * The key a web address is compared by, the engine's own (M72 section 8.4, `FermixCore.ReplyBlocks`): its scheme
 * and host in lower case, its user, its port (the scheme's default when it names none), its path without a trailing
 * slash and its query, each as written, and no fragment. None for what the engine's reader refuses (ADDRESS_CHARACTERS,
 * a bracket outside an IPv6 host, a second `#`, a second `@`, a port that is no number) or an address with no scheme
 * or no host, which names no place, as the engine finds none for it. The address is read by its parts as the RFC
 * splits them, never by `java.net.URI`, whose older rules refuse a host the engine takes (one with an underscore).
 */
internal fun addressKey(url: String): String? {
    if (!ADDRESS_CHARACTERS.matches(url)) return null
    val parts = checkNotNull(ADDRESS_PARTS.matchEntire(url)) { "RFC 3986's appendix B splits every address" }.groups
    val scheme = parts[1]?.value?.takeIf(SCHEME::matches)
    val authority = parts[2]?.value
    val path = parts[3]?.value.orEmpty()
    val query = parts[4]?.value
    val fragment = parts[5]?.value
    val bracketed = listOfNotNull(path, query, fragment).any { part -> '[' in part || ']' in part }
    val read = !bracketed && fragment?.contains('#') != true
    val origin = if (scheme != null && authority != null && read) originOf(scheme.lowercase(), authority) else null
    return origin?.let { it + path.removeSuffix("/") + query?.let { asked -> "?$asked" }.orEmpty() }
}

/**
 * An address's [scheme] and [authority] as its key begins: the user as written, the host in lower case, and the port
 * with its leading zeros gone or the scheme's default; none for an authority the RFC does not write (AUTHORITY).
 */
private fun originOf(
    scheme: String,
    authority: String,
): String? {
    val parts = AUTHORITY.matchEntire(authority)?.groups ?: return null
    val user = parts[1]?.value?.let { "$it@" }.orEmpty()
    val host = parts[2]?.value.orEmpty().lowercase()
    val written = parts[3]?.value?.takeIf { it.isNotEmpty() }
    val port = written?.trimStart('0')?.ifEmpty { "0" } ?: DEFAULT_PORTS[scheme]
    return "$scheme://$user$host:$port"
}

/** The keys (addressKey) of the pages [places] name, their own and their sources'. */
internal fun placeKeys(places: List<Place>): Set<String> =
    places.flatMap { listOfNotNull(it.canonicalUrl, it.sourceUrl) }.mapNotNull(::addressKey).toSet()

/** A place's coordinates as an address writes them, latitude first, in plain decimals. */
private fun coordinatesOf(place: Place): String =
    listOf(place.location.latitude, place.location.longitude).joinToString(",") { it.toBigDecimal().toPlainString() }

/**
 * [text]'s UTF-8 bytes as an address carries them: an ASCII letter, a digit and RFC 3986's unreserved marks as they
 * are, every other byte as `%XX`, a space as `%20`, parentheses as `%28` and `%29`.
 */
private fun percentEncoded(text: String): String =
    text.encodeToByteArray().joinToString("") { byte ->
        val char = byte.toInt().toChar()
        val plain = byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED_MARKS)
        if (plain) char.toString() else "%%%02X".format(Locale.ROOT, byte.toInt() and BYTE_BITS)
    }

/**
 * The `geo:` address a place opens in the Maps app at (M72 section 14): its coordinates, and the same coordinates as
 * the query with its name as the label, so the app shows the pin there under that name. Built from the record alone,
 * never from a link in the reply; the name is cut to [MAX_PLACE_LABEL_CHARS] and percent-encoded (percentEncoded),
 * its spaces and parentheses with it, so it never ends the label early.
 */
internal fun geoAddress(place: Place): String {
    val at = coordinatesOf(place)
    return "geo:$at?q=$at(${percentEncoded(cutAtCharacter(place.name, MAX_PLACE_LABEL_CHARS))})"
}

/** The Google Maps page that searches a place's coordinates: where it opens when no app answers its `geo:` address. */
internal fun mapsAddress(place: Place): String = MAPS_SEARCH + coordinatesOf(place)

/** The page "Open website" opens: the place's own, else its source's, the first that opens (opens); none without. */
internal fun websiteOf(place: Place): String? = listOfNotNull(place.canonicalUrl, place.sourceUrl).firstOrNull(::opens)
