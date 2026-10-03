package io.tezra.fermix.chats

import io.tezra.fermix.chat.rowWords
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.RepairNotice
import io.tezra.fermix.data.showsDevTag
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.speaksOnRow
import io.tezra.fermix.session.TimelineRow
import java.text.ParsePosition
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The agent name a row's title leaves out: the default (design section 9.2). */
private const val DEFAULT_AGENT = "Fermix"

/** A row shows the weekday for a message of the last six days, and the date for an older one. */
private const val WEEKDAY_DAYS = 6L

/** A row's second line, in the canon's priority: a link that speaks, "thinking…", "Draft: …", the last message. */
sealed interface RowLine {
    data class Speaks(
        val link: Link,
    ) : RowLine

    data object Thinking : RowLine

    data class Draft(
        val text: String,
    ) : RowLine

    data class Message(
        val text: String,
    ) : RowLine

    data object Empty : RowLine
}

/**
 * One Chats row (design section 9.4): [record]'s (instance, profile), its title with the host-owned
 * [agentName] when that is not "Fermix", the DEV tag, the link, the second line, when the last message
 * came ([time], already in the row's words), and the unread count, the notified set's size (section 10).
 */
data class ChatRow(
    val record: Instance,
    val profileId: String,
    val agentName: String?,
    val dev: Boolean,
    val link: Link,
    val line: RowLine,
    val time: String?,
    val unread: Int,
) {
    init {
        require(unread >= 0) { "an unread count of $unread" }
    }

    /** The title's agent part: the agent's name, when it is not the default. */
    val agent: String? get() = hostAgent(agentName)
}

/** The host-owned agent's [name] as a title carries it: none for the default, "Fermix" (section 9.2). */
internal fun hostAgent(name: String?): String? = name?.takeIf { it != DEFAULT_AGENT }

/** The Chats list: a row per (instance, profile), and "Re-pair this Fermix" for each Fermix a launch dropped. */
data class ChatsUi(
    val rows: List<ChatRow>,
    val repairs: List<RepairNotice>,
)

/** What one row is made of, each read on its own: the session's link, the turn, the chat's settings and rows. */
data class RowFacts(
    val link: Link,
    val thinking: Boolean,
    val chat: ChatState,
    val newest: List<TimelineRow>,
    val unread: Int,
)

/**
 * [record]'s row for [profileId] from [facts], its time as seen [now], in its zone, in [locale]'s form: a link
 * that speaks first (a trust state, a protocol error, a replaced connection), then "thinking…", the draft, and
 * the last message, as the canon orders them.
 */
fun rowOf(
    record: Instance,
    profileId: String,
    facts: RowFacts,
    now: ZonedDateTime,
    locale: Locale,
): ChatRow {
    val draft = facts.chat.draft
    val line =
        when {
            facts.link.speaksOnRow -> RowLine.Speaks(facts.link)
            facts.thinking -> RowLine.Thinking
            draft != null -> RowLine.Draft(draft)
            else -> lastText(facts.newest)?.let(RowLine::Message) ?: RowLine.Empty
        }
    return ChatRow(
        record = record,
        profileId = profileId,
        agentName = facts.chat.agentName,
        dev = showsDevTag(record.profile, record.title),
        link = facts.link,
        line = line,
        time = lastTime(facts.newest)?.let { rowTime(it, now.toInstant(), now.zone, locale) },
        unread = facts.unread,
    )
}

/** The newest row's words on one line (rowWords): the agent's markdown as its plain words, the owner's as typed. */
private fun lastText(newest: List<TimelineRow>): String? =
    newest.firstOrNull()?.let(::rowWords)?.takeIf { it.isNotBlank() }

/**
 * When the newest row with a time came: a reply's bubble carries none (TimelineRow), its message does. A
 * time the daemon wrote that is not ISO 8601 leaves the row without one, which is all it can say.
 */
private fun lastTime(newest: List<TimelineRow>): Instant? {
    val stamp = newest.firstNotNullOfOrNull { (it as? TimelineRow.Message)?.message?.ts } ?: return null
    val position = ParsePosition(0)
    DateTimeFormatter.ISO_INSTANT.parseUnresolved(stamp, position)
    val readable = position.errorIndex < 0 && position.index == stamp.length
    return if (readable) Instant.parse(stamp) else null
}

/**
 * A row's time as the canon draws it: the time of day for today, the weekday for the six days before, and
 * the date for anything older, each in [locale]'s own form.
 */
fun rowTime(
    at: Instant,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): String {
    val moment = at.atZone(zone)
    val days = ChronoUnit.DAYS.between(moment.toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0L -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(moment)
        days <= WEEKDAY_DAYS -> moment.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(moment)
    }
}
