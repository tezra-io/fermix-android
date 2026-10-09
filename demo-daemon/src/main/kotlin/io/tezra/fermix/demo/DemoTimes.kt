package io.tezra.fermix.demo

/**
 * Every duration the demo plays, in milliseconds, in one table: the pauses that let each of Connecting's
 * phases show, the computer's approval while Verify shows its code, a pairing window's life, a reply's thinking
 * card, its tools and its words streamed in, the long turn of the chat that is thinking, an approval's life, the
 * latency a pong shows, and the bounds a daemon holds a connection to (PROTOCOL.md "Transport"). A test plays them
 * on a virtual clock; nothing of the demo reads the clock to decide anything else. Two are the demo's own, not the
 * engine's (README, "The demo", departures): the scripted card's [approvalTtl], and the [reach] a dial pauses for
 * while its Fermix has a pairing window open.
 */
data class DemoTimes(
    /** A dial's pause before its socket opens, while a pairing window is open: Connecting's "Reaching". */
    val reach: Long = 900,
    /** A pairing's message 1 waits this long for message 2: Connecting's "Checking". */
    val check: Long = 900,
    /**
     * The computer approves this long after the phone's `pair_request`: about 6 s of Verify, as the owner reads it,
     * since the phone shows its code some 1.5 s after the request goes, once the Connecting screen has paced
     * "Securing the line…" (onboarding's SECURING_LINE_MILLIS, 600 ms) and moved to Verify.
     */
    val approve: Long = 7_500,
    /** A pairing window's life: its link pairs within this long of its opening (PROTOCOL.md "Pairing link"). */
    val window: Long = 120_000,
    /** A reply's card before its first heading. */
    val thinking: Long = 1_200,
    /** A heading's time on the card before the next. */
    val heading: Long = 800,
    /** A tool's run, from its chip's start to its stop. */
    val tool: Long = 900,
    /** A reply's words, streamed in deltas over this long. */
    val streaming: Long = 2_000,
    /** The least time between two deltas: PROTOCOL.md's "at most every 100 ms". */
    val delta: Long = 120,
    /**
     * The long turn of the chat that is thinking when it is paired, from its first `hello`: two minutes, long enough
     * to read Paired and name the Fermix and still find it thinking on the Chats list.
     */
    val longTurn: Long = 120_000,
    /**
     * How long the scripted approval waits for the owner from its first `hello`: ten minutes, the demo's own, where
     * the engine gives a sandbox card 60 s, so the owner reaches it after pairing.
     */
    val approvalTtl: Long = 600_000,
    /** How long an approval a reply raises waits for the owner: the engine's sandbox card's 60 s. */
    val askTtl: Long = 60_000,
    /** A pong's delay: the latency the Instance screen and the subtitle show. */
    val pong: Long = 38,
    /** Handshake deadline: the phone's first event after the handshake comes within it, or `1008`. */
    val handshakeDeadline: Long = 10_000,
    /** No inbound frame for this long closes the connection `1002`. */
    val idle: Long = 150_000,
    /** One Noise session's lifetime: past it the connection closes `1000` and the phone reconnects. */
    val lifetime: Long = 3_600_000,
)
