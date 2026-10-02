package io.tezra.fermix.onboarding

import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.NicknameRefusal
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.data.nicknameRefusal
import io.tezra.fermix.session.InstanceFacts

/** The profile a `DEV` tag marks (design section 9.2). */
private const val DEV_PROFILE = "fermix-dev"

/** The nickname a `DEV` tag marks, in any case (design section 9.2). */
private const val DEV_NICKNAME = "Dev"

/**
 * The record design section 9.1 keeps for an approved pairing: [facts] as the ceremony reports them,
 * with [tint], no nickname yet, and notifications off until the owner allows them (section 13.3, step 7).
 */
fun instanceOf(
    facts: InstanceFacts,
    tint: String,
): Instance =
    Instance(
        gatewayPk = facts.gatewayPk,
        tlsFp = facts.tlsFp,
        host = facts.host,
        profile = facts.profile,
        label = facts.label,
        tint = tint,
        candidates = facts.candidates,
        port = facts.port,
        deviceId = facts.deviceId,
        keyAlias = facts.keyAlias,
        pushSalt = facts.pushSalt,
        pushPlatforms = facts.pushPlatforms,
        notificationsEnabled = false,
    )

/**
 * The auto-picked tint of a new pairing (design section 9.2): the first of the six, in design's order,
 * that no record on this phone carries, so a second Fermix on one computer never shares the first one's;
 * with all six taken, the one fewest records carry, the first of those. A Fermix paired again keeps its
 * own tint, which the store's upsert keeps.
 */
fun pickTint(existing: List<Instance>): String {
    val counts = TINT_NAMES.associateWith { name -> existing.count { it.tint == name } }
    return TINT_NAMES.minBy { counts.getValue(it) }
}

/**
 * Whether the Paired step asks "Name this Fermix" for [record] among [all] (design section 9.2): when
 * another Fermix on this phone already carries the same title, as a second Fermix on the same computer
 * does when its daemon sent no distinguishing name.
 */
fun needsName(
    record: Instance,
    all: List<Instance>,
): Boolean = nicknameRefusal(record.title, record.id, all) == NicknameRefusal.TAKEN

/** Design section 9.2's `DEV` tag: the `fermix-dev` profile, or a Fermix the owner calls Dev. */
fun showsDevTag(
    profile: String,
    title: String,
): Boolean = profile == DEV_PROFILE || title.trim().equals(DEV_NICKNAME, ignoreCase = true)
