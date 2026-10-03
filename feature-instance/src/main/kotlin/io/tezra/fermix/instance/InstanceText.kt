package io.tezra.fermix.instance

import io.tezra.fermix.session.Diagnostic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** The key fingerprint shows the first 16 hex digits of the gateway key's SHA-256, in fours. */
private const val FINGERPRINT_DIGITS = 16
private const val FINGERPRINT_GROUP = 4

/**
 * The Instance screen's key fingerprint (design section 13.7, "key fingerprint"): of the gateway key, as the
 * canon draws it, the start of [instanceId], `sha256(gateway_pk)` in hex, in upper case and in fours.
 */
fun keyFingerprint(instanceId: String): String {
    require(instanceId.length >= FINGERPRINT_DIGITS) { "an instance's id is a SHA-256 in hex" }
    return instanceId
        .take(FINGERPRINT_DIGITS)
        .uppercase(Locale.ROOT)
        .chunked(FINGERPRINT_GROUP)
        .joinToString(" ")
}

/**
 * "Paired since…": the day [pairedAt], in Unix milliseconds, fell on in [zone], in [locale]'s medium date
 * format; the screen passes the phone's own, and a preview fixed ones.
 */
fun pairedDate(
    pairedAt: Long,
    zone: ZoneId,
    locale: Locale,
): String {
    require(pairedAt >= 0L) { "paired at $pairedAt" }
    val day = Instant.ofEpochMilli(pairedAt).atZone(zone)
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
}

/**
 * One line of the connection log (the canon's `.log`): how long after the session opened, the kind in the
 * session's own words, and the detail, which never quotes a header (Diagnostic).
 */
fun logLine(entry: Diagnostic): String {
    val at = entry.atMs.milliseconds
    val clock =
        at.toComponents { hours, minutes, seconds, _ ->
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        }
    return "+$clock  ${entry.kind.name.lowercase(Locale.ROOT)}  ${entry.detail}"
}
