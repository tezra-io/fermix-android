package io.tezra.fermix.onboarding

import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.NicknameRefusal
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.data.nicknameRefusal
import io.tezra.fermix.session.InstanceFacts

/**
 * The record design section 9.1 keeps for an approved pairing: [facts] as the ceremony reports them,
 * with [tint], no nickname yet, and notifications off until the owner allows them (section 13.3, step 7);
 * and what the Instance screen's "This phone" shows (section 13.7): [deviceName], the name `pair_request`
 * carried, and [pairedAt], when the pairing was approved, in milliseconds since the epoch.
 */
fun instanceOf(
    facts: InstanceFacts,
    tint: String,
    deviceName: String,
    pairedAt: Long,
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
        deviceName = deviceName,
        pairedAt = pairedAt,
    )

/**
 * The row an approved pairing merges into after "Pair again" on [rowId] (design section 9.2): that row,
 * while it is still on this phone, when it is of the paired daemon's profile and no row holds the daemon
 * already. Otherwise none, and the pairing is recorded as any other is, InstanceStore.upsert's: the same
 * daemon paired again replaces its own row in place, as a revoked phone's does, and another is a new row.
 */
fun mergeTarget(
    rowId: String?,
    facts: InstanceFacts,
    records: List<Instance>,
): String? {
    val row = records.find { it.id == rowId }
    val held = records.any { it.id == facts.id }
    return rowId.takeIf { row != null && row.profile == facts.profile && !held }
}

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
