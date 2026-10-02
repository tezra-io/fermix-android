package io.tezra.fermix.data

/** The most characters a nickname holds (design section 13.7, the Instance screen). */
const val MAX_NICKNAME_CHARACTERS = 40

/** Why a nickname is refused (design section 13.7); the Instance screen says which. */
enum class NicknameRefusal {
    /** Nothing but space. */
    BLANK,

    /** More than [MAX_NICKNAME_CHARACTERS] characters. */
    TOO_LONG,

    /** Another Fermix on this phone is already titled so. */
    TAKEN,
}

/**
 * Why [nickname], trimmed, cannot name instance [id] among [instances], or null when it can (design
 * section 13.7): 1 to 40 characters, and no other row titled the same, its nickname or else its label,
 * whatever the case, since two rows that read alike are what the rule prevents. A character is a Unicode
 * code point, so an emoji outside the basic plane counts once. [id] need not be among [instances]: the
 * Paired screen asks before its record exists.
 */
fun nicknameRefusal(
    nickname: String,
    id: String,
    instances: List<Instance>,
): NicknameRefusal? {
    val trimmed = nickname.trim()
    return when {
        trimmed.isEmpty() -> NicknameRefusal.BLANK
        characters(trimmed) > MAX_NICKNAME_CHARACTERS -> NicknameRefusal.TOO_LONG
        instances.any { it.id != id && it.title.equals(trimmed, ignoreCase = true) } -> NicknameRefusal.TAKEN
        else -> null
    }
}

/** A nickname as a record holds it: trimmed, and 1 to [MAX_NICKNAME_CHARACTERS] characters. */
internal fun isNickname(nickname: String): Boolean =
    nickname.isNotEmpty() && nickname == nickname.trim() && characters(nickname) <= MAX_NICKNAME_CHARACTERS

private fun characters(text: String): Int = text.codePointCount(0, text.length)

/** The records after a pairing, and the record the paired one replaced, if any. */
internal data class Replacement(
    val instances: List<Instance>,
    val replaced: Instance?,
)

/**
 * A pairing approved (design section 6.1): [paired] replaces the record of the same daemon in place, which
 * keeps the owner's nickname and tint (section 9.2), or else it is added last. A pairing always brings a
 * new key alias, one per attempt, and the caller deletes the replaced record's; [paired] under the alias its
 * record holds is no pairing and is refused, so a change to a record goes through [updated] and never hands
 * the live key over for deletion. A nickname the pairing brings is held to the rename rule of section 13.7,
 * which the Paired screen asks [nicknameRefusal] beforehand; a refused one fails the write.
 */
internal fun planUpsert(
    current: List<Instance>,
    paired: Instance,
): Replacement {
    val index = current.indexOfFirst { it.id == paired.id }
    if (index >= 0) return replaceAt(current, index, paired)
    requireFreeNickname(paired.nickname, paired.id, current)
    return Replacement(current + paired, null)
}

/**
 * "Pair again" on the row of a daemon that was reinstalled (design section 9.2): [paired], a new gateway
 * key, merges into the row of [oldId], which keeps its place, nickname and tint. The owner names the row,
 * never the host and profile: two daemons on one computer can share both, and section 9.2 keeps them
 * apart by nickname. Section 9.2's reinstall is on the same host and profile. The profile is checked: the
 * v2 link carries it, so it is the daemon's at pairing. The host is not: until the first `hello_ack` a
 * pairing knows only the link's `name`, which the old row's `hello_ack` may have replaced with the host's
 * own, so a check would refuse the very reinstall it is for. A [paired] daemon of another profile, or that
 * another row already holds, is refused, and so is one under the alias of the row it replaces, as [planUpsert]
 * refuses it.
 */
internal fun planMerge(
    current: List<Instance>,
    oldId: String,
    paired: Instance,
): Replacement {
    val index = current.indexOfFirst { it.id == oldId }
    require(index >= 0) { "no record is $oldId" }
    require(current.none { it.id == paired.id && it.id != oldId }) { "another row holds ${paired.id}" }
    require(paired.profile == current[index].profile) {
        "profile ${paired.profile} cannot merge into the row of profile ${current[index].profile}"
    }
    return replaceAt(current, index, paired)
}

/**
 * What only a pairing sets, the gateway key, the TLS pin, the device id, the key alias and the push salt,
 * and what only the owner sets, the nickname ([renamed]) and the tint: an update that changes one is refused.
 */
private val SET_APART: List<Pair<String, (Instance) -> String?>> =
    listOf(
        "gateway_pk" to Instance::gatewayPk,
        "tls_fp" to Instance::tlsFp,
        "device_id" to Instance::deviceId,
        "key_alias" to Instance::keyAlias,
        "push_salt" to Instance::pushSalt,
        "nickname" to Instance::nickname,
        "tint" to Instance::tint,
    )

/**
 * [id]'s record after [change]: what the daemon reports after pairing (`hello_ack`'s host, label, profile,
 * candidates, caps and push platforms; the port) and this phone's own settings (notifications, the last
 * `push_register`). A change to a field of [SET_APART] is refused, naming it.
 */
internal fun updated(
    current: List<Instance>,
    id: String,
    change: (Instance) -> Instance,
): List<Instance> {
    val old = requireNotNull(current.firstOrNull { it.id == id }) { "no record is $id" }
    val new = change(old)
    val touched = SET_APART.filter { (_, field) -> field(new) != field(old) }.map { (name, _) -> name }
    require(touched.isEmpty()) { "an update of $id changed $touched, which a pairing or the owner sets" }
    return current.map { if (it.id == id) new else it }
}

/**
 * [paired] in the row at [index], which hands the row's record back for its key alias to be deleted, so
 * [paired] must hold another alias. The nickname kept is the old row's, or else the new pairing's, which is
 * then a new name and held to the rename rule among the other rows.
 */
private fun replaceAt(
    current: List<Instance>,
    index: Int,
    paired: Instance,
): Replacement {
    val old = current[index]
    require(paired.keyAlias != old.keyAlias) { "a pairing into the row of ${old.id} kept its key alias" }
    if (old.nickname == null) requireFreeNickname(paired.nickname, paired.id, current - old)
    val kept = paired.copy(nickname = old.nickname ?: paired.nickname, tint = old.tint)
    return Replacement(current.toMutableList().also { it[index] = kept }, old)
}

/** [nickname], when there is one, refused unless [nicknameRefusal] accepts it for [id] among [others]. */
private fun requireFreeNickname(
    nickname: String?,
    id: String,
    others: List<Instance>,
) {
    val refusal = nickname?.let { nicknameRefusal(it, id, others) }
    require(refusal == null) { "the pairing's nickname '$nickname' is refused: $refusal" }
}

/** [id]'s record with [nickname], or with none: "Reset to gateway name". */
internal fun renamed(
    current: List<Instance>,
    id: String,
    nickname: String?,
): List<Instance> {
    require(current.any { it.id == id }) { "no record is $id" }
    return current.map { if (it.id == id) it.copy(nickname = nickname) else it }
}

/** [id]'s row moved to [toIndex], the others in their order: "Move to top" is index 0 (section 13.4). */
internal fun moved(
    current: List<Instance>,
    id: String,
    toIndex: Int,
): List<Instance> {
    val record = requireNotNull(current.firstOrNull { it.id == id }) { "no record is $id" }
    require(toIndex in current.indices) { "index $toIndex is outside the ${current.size} rows" }
    val others = current.filter { it.id != id }
    return others.take(toIndex) + record + others.drop(toIndex)
}
