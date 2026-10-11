package io.tezra.fermix.chat

import io.tezra.fermix.session.TimelineRow

/**
 * Where the pictures of the turns a chat remembers stand (the owner, 2026-10-10; design sections 8.2 and 13.5): a
 * row of the agent's with media that belongs to a remembered turn and is no bubble's own takes the key of the card it
 * came under, so the card morphs into it and opens again below it under the next key. The engine writes a turn's
 * picture as an answer to its request, `in_reply_to` its `client_msg_id`, with no metadata (channels/mobile.ex's
 * persist_media, unlike a text row, which also names its `metadata.turn_id`): a picture belongs to the turn whose
 * request it answers, or else to the turn its metadata names. A card's
 * place, its slot, is the bubble it becomes: the picture of a slot is the first bubble's that opened after the row
 * was kept, else the card's that shows or is to come. The first picture of slot s of turn T is keyed
 * `card:T:s`, the next `card:T:s.1`, and the card, or the bubble it became, at a slot with n pictures `card:T:s.n`.
 */
internal class TurnMedia(
    private val keys: Map<ULong, String>,
    private val counts: Map<Pair<String, Int>, Int>,
) {
    /** The key of row [seq], when it is a picture of a remembered turn. */
    fun keyOf(seq: ULong): String? = keys[seq]

    /** How many pictures stand at [turnId]'s [slot]. */
    fun countAt(
        turnId: String,
        slot: Int,
    ): Int = counts[turnId to slot] ?: 0

    companion object {
        val NONE = TurnMedia(emptyMap(), emptyMap())
    }
}

/** The pictures of [live]'s turns among the rows, [ascending] by seq, each keyed as [TurnMedia] says. */
internal fun turnMedia(
    ascending: List<TimelineRow>,
    live: ChatLive,
): TurnMedia {
    val turns = live.turns.associateBy { it.turnId }
    val requests = live.turns.mapNotNull { turn -> turn.clientMsgId?.let { it to turn } }.toMap()
    val pictures = ascending.mapNotNull { row -> pictureTurn(row, requests, turns)?.let { row.serverSeq to it } }
    val keys = mutableMapOf<ULong, String>()
    val counts = mutableMapOf<Pair<String, Int>, Int>()
    for ((seq, turn) in pictures.filter { (seq, turn) -> turn.bubbles.none { it.sealedSeq == seq } }) {
        val slot = slotOf(turn, seq)
        val before = counts[turn.turnId to slot] ?: 0
        keys[seq] = slotKey(turn.turnId, slot, before)
        counts[turn.turnId to slot] = before + 1
    }
    return TurnMedia(keys, counts)
}

/**
 * The remembered turn a row of the agent's with media belongs to: the one whose request it answers ([requests], by
 * `client_msg_id`), else the one its metadata names ([turns]); none for any other row.
 */
private fun pictureTurn(
    row: TimelineRow,
    requests: Map<String, LiveTurn>,
    turns: Map<String, LiveTurn>,
): LiveTurn? {
    val message = (row as? TimelineRow.Message)?.message
    if (message == null || message.role == USER_ROLE || message.mediaRefs.isEmpty()) return null
    val answered = message.inReplyTo?.let(requests::get)
    return answered ?: message.metadata?.string(TURN_ID_KEY)?.let(turns::get)
}

/** The slot of a picture of [turn] kept as row [seq]: the first bubble's that opened after it, else the next one's. */
private fun slotOf(
    turn: LiveTurn,
    seq: ULong,
): Int =
    turn.bubbles
        .sortedBy { it.index }
        .firstOrNull { it.openedAfter >= seq }
        ?.index ?: nextSlot(turn)

/** The slot of [turn]'s card that shows or is to come: the bubble it would become. */
internal fun nextSlot(turn: LiveTurn): Int = (turn.bubbles.maxOfOrNull { it.index } ?: 0) + 1

/** The key at [turnId]'s [slot] after [count] pictures there: the card's own first, then counted. */
internal fun slotKey(
    turnId: String,
    slot: Int,
    count: Int,
): String = if (count == 0) cardKey(turnId, slot) else "${cardKey(turnId, slot)}.$count"

/**
 * Whether [key] is a slot's after a picture there (`card:T:s.n`): the card, or the bubble it became, that opened again
 * below a picture that took its card's place.
 */
internal fun isBelowPicture(key: String): Boolean =
    key.startsWith(CARD_KEY_PREFIX) && key.substringAfterLast(':').contains('.')
