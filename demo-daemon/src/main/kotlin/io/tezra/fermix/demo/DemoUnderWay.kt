package io.tezra.fermix.demo

/**
 * What a Fermix's script has under way, begun at the first `hello` it gets in a run of the demo: its long turn, the
 * chat that shows "thinking…", and its approval, the card a request of the script raised before the demo started,
 * waiting for the owner (DemoApprovals). The card's ttl is the demo's own, [DemoTimes.approvalTtl], longer than the
 * engine's 60 s for a sandbox card, so the owner reaches it after pairing (README, "The demo", departures).
 *
 * A restarted app meets a new run of the demo, whose Fermixes begin again from their scripts, and which knows of a
 * run before only what the phone's first `hello` says, its cursor. Each item is decided as the engine decides it
 * when it restarts (PROTOCOL.md "Approvals", "Delivery and failure behavior"): a waiting card is forgotten, so it is
 * raised only for a phone that does not hold the script's rows, one pairing now; and an accepted request that did not
 * finish runs again, while a finished one never does, so the long turn runs unless the phone holds a row past the
 * script's, its answer as far as the demo can tell. A phone that wrote rows of its own there before the long turn's
 * answer came, and then restarted the app inside its two minutes, sees no answer (README, "The demo", departures).
 */
internal class DemoUnderWay(
    private val home: DemoHome,
    private val parts: DemoParts,
) {
    /** Begins what is under way for a phone whose first `hello` of the run holds rows up to [lastServerSeq]. */
    fun begin(lastServerSeq: ULong) {
        check(!home.begun) { "what is under way begins once" }
        home.begun = true
        val answered = lastServerSeq > home.scriptedHead
        val paired = lastServerSeq >= home.scriptedHead
        home.fermix.script.longTurn
            ?.takeUnless { answered }
            ?.let { DemoTurns(home, parts).long(it) }
        home.fermix.script.approval
            ?.takeUnless { paired }
            ?.let { approval ->
                val card = AskCard(approval.id, approval.kind, approval.text, approval.detail, approval.grants)
                raise(home, parts, card, parts.times.approvalTtl, approval.approved)
            }
    }
}
