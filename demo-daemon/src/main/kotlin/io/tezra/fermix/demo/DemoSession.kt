package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ActiveTurn
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.Instance
import io.tezra.fermix.protocol.ModelState
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.session.turnIdOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The largest attachment a demo Fermix takes, its `caps.max_media_bytes`: 8 MiB, where a daemon's default is 20 MiB
 * (PROTOCOL.md "Attachments"), as the demo keeps what it takes in the app's own heap; the app refuses a larger file
 * itself, as it would for a daemon configured so.
 */
internal const val MAX_MEDIA_BYTES = 8L * 1024 * 1024

/** The most approvals a `hello_ack` names and a reconnect sends again (design section 7). */
private const val MAX_REPLAYED = 4

/**
 * An event a daemon newer than protocol v2 might send, which no engine sends today: the visual canon's example of
 * the line the Instance screen's Diagnostics shows for one ("unknown server event"). The phone ignores it and notes
 * it, so a demo session, which nothing else goes wrong in, has a line there (README, "The demo", departures).
 */
private const val NEWER_EVENT = "call_offer"

/** The daemon's commands, as feature-chat's samples and the visual canon's palette list them. */
internal val DEMO_COMMANDS =
    listOf(
        CommandDescriptor("new", emptyList(), "Start a fresh conversation session."),
        CommandDescriptor("stop", emptyList(), "Stop all running Fermix work and clear queued messages."),
        CommandDescriptor("compact", emptyList(), "Compact this conversation history now."),
        CommandDescriptor("tasks", emptyList(), "List running and recent background work."),
        CommandDescriptor("model", listOf("m"), "Switch the model for this chat."),
        CommandDescriptor("help", emptyList(), "List available commands."),
    )

/**
 * A paired phone's session on one connection (PROTOCOL.md "Envelope", "One timeline"; design section 7): its
 * `hello` within the deadline, `hello_ack` after the round trip a pong takes, with what the app can draw turned on
 * (the commands, models, search, thoughts, `turn_done`, approval replay; push off; `streaming` as the chat's model
 * has it), the approvals waiting sent again, a newer daemon's event ([NEWER_EVENT]), what was under way at the
 * pairing begun on the first `hello` of the run, then every event answered until the phone leaves, the idle bound
 * passes, the frames reach their bound or the hour's lifetime ends it.
 */
internal class DemoSession(
    private val connection: DemoConnection,
) {
    private val home = connection.home
    private val parts = connection.parts
    private val requests = DemoRequests(connection)
    private val queries = DemoQueries(connection)
    private val uploads = DemoUploads(connection)
    private val keeping = DemoKeeping(connection)

    suspend fun serve() {
        val hello = hello() ?: return
        // The phone's latency is its hello's round trip (Connector): a pong's, as every later one is.
        delay(parts.times.pong)
        welcome(hello)
        val ended = withTimeoutOrNull(parts.times.lifetime) { answerAll() }
        if (ended == null) connection.close(NORMAL, "Noise session lifetime reached")
    }

    /** The phone's `hello`, or none: the deadline, another event, another version or another device's id. */
    private suspend fun hello(): ClientEvent.Hello? {
        val read = connection.receive(parts.times.handshakeDeadline)
        val frame = (read as? Read.Got)?.frame
        val hello = frame?.event as? ClientEvent.Hello
        val device = home.devices[connection.phoneKey]
        when {
            read == Read.Quiet -> connection.close(HANDSHAKE_DEADLINE, "mobile handshake deadline")
            read is Read.Bound -> connection.close(read.code, read.reason)
            frame == null -> Unit
            hello == null -> connection.close(PROTOCOL_ERROR, "mobile protocol error")
            frame.v != DAEMON_VERSION || hello.protocolV != DAEMON_VERSION -> refuseVersion(frame.v)
            device != null && device != hello.deviceId -> connection.close(REVOKED, "authenticated device mismatch")
            else -> return hello.also { adopt(it) }
        }
        return null
    }

    private fun refuseVersion(v: Int) {
        val direction = if (v < DAEMON_VERSION) VersionDirection.CLIENT_TOO_OLD else VersionDirection.CLIENT_TOO_NEW
        val error =
            ServerEvent.Error(
                "unsupported_protocol_version",
                "the demo speaks protocol v$DAEMON_VERSION",
                direction = direction,
                clientVersion = v,
                minVersion = DAEMON_VERSION,
                maxVersion = DAEMON_VERSION,
            )
        connection.send(error)
        connection.close(PROTOCOL_ERROR, "unsupported mobile protocol version")
    }

    /**
     * A key this run of the demo never paired is a phone that paired with it before the app restarted: the demo
     * keeps no device list across runs, so it takes the phone at its word, as it does its cursors.
     */
    private fun adopt(hello: ClientEvent.Hello) {
        home.devices.getOrPut(connection.phoneKey) { hello.deviceId }
        home.catchUp(hello.lastServerSeq)
        home.mutationHead = maxOf(home.mutationHead, hello.lastMutationSeq ?: 0uL)
    }

    /**
     * `hello_ack`, the approvals waiting, a newer daemon's event, and what was under way begun on the first `hello` of
     * the run, as far as this phone's cursor says it was not done in a run before (DemoUnderWay).
     */
    private fun welcome(hello: ClientEvent.Hello) {
        check(hello.deviceId.isNotEmpty()) { "a hello names its device" }
        home.connections += connection
        home.sessions++
        connection.send(helloAck())
        home.approvals.values
            .take(MAX_REPLAYED)
            .forEach { connection.send(it.event(parts.nowMs())) }
        connection.sendUnknown(NEWER_EVENT)
        if (!home.begun) DemoUnderWay(home, parts).begin(hello.lastServerSeq)
    }

    /** The phone's welcome; its `active_turns` are the requests running, never a resumed one, which has none. */
    private fun helloAck(): ServerEvent.HelloAck {
        val fermix = home.fermix
        val active =
            home.turns.keys
                .filter { it in home.claims }
                .map { ActiveTurn(turnIdOf(it), it) }
        return ServerEvent.HelloAck(
            sessionId = "demo-${fermix.index}-${home.sessions}",
            minVersion = DAEMON_VERSION,
            maxVersion = DAEMON_VERSION,
            profiles = listOf(Profile(MAIN, fermix.agent)),
            candidates = listOf(fermix.wireRoute),
            historyHeadSeq = home.head,
            readUpToSeq = home.readFrontier,
            caps = caps(),
            instance = Instance(fermix.host, fermix.host, fermix.profile),
            activeTurns = active,
            pendingApprovals = home.approvals.keys.take(MAX_REPLAYED),
            mutationHeadSeq = home.mutationHead,
        )
    }

    private fun caps(): Caps =
        Caps(
            commands = DEMO_COMMANDS,
            media = true,
            streaming = home.streams(),
            maxMediaBytes = MAX_MEDIA_BYTES,
            thoughts = true,
            turnDone = true,
            transcripts = false,
            models = true,
            search = true,
            approvalReplay = true,
            reply = false,
            push = emptyList(),
            modelState = ModelState(DEFAULT_MODEL, home.model),
        )

    /**
     * Every frame answered, one at a time, until the phone leaves, the connection closes or the idle bound passes
     * (`1002`); at most [MAX_FRAMES], past which the connection's own bound closes it.
     */
    private suspend fun answerAll() {
        var frames = 0L
        while (connection.isOpen && frames < MAX_FRAMES) {
            when (val read = connection.receive(parts.times.idle)) {
                is Read.Got -> answer(read.frame)
                Read.Gone -> connection.close(NORMAL, "")
                Read.Quiet -> connection.close(PROTOCOL_ERROR, "no inbound frame for ${parts.times.idle} ms")
                is Read.Bound -> connection.close(read.code, read.reason)
            }
            frames++
        }
    }

    private suspend fun answer(frame: ClientFrame) {
        when (val event = frame.event) {
            is ClientEvent.Msg, is ClientEvent.Command, is ClientEvent.Cancel -> requests.answer(event)
            is ClientEvent.AttachBegin, is ClientEvent.AttachChunk, is ClientEvent.AttachEnd -> uploads.answer(frame)
            is ClientEvent.HistoryPull, is ClientEvent.HistorySearch, is ClientEvent.MediaFetch -> queries.answer(event)
            is ClientEvent.RequestStatus, is ClientEvent.MutationsPull, ClientEvent.ModelsPull -> queries.answer(event)
            else -> keeping.answer(event)
        }
    }
}

/**
 * What keeps a paired link on one connection: a ping answered after [DemoTimes.pong], the read frontier moved and
 * told to every phone, acks and push registrations taken (push is off), a second `hello` refused, and `unpair`,
 * which forgets the phone, stops the requests it sent that still run, each ending `cancelled` on every phone, and
 * closes `4003` (PROTOCOL.md "Delivery and failure behavior").
 */
internal class DemoKeeping(
    private val connection: DemoConnection,
) {
    private val home = connection.home

    suspend fun answer(event: ClientEvent) {
        when (event) {
            ClientEvent.Ping -> pong()
            is ClientEvent.ReadState -> home.broadcast(ServerEvent.ReadState(MAIN, home.read(event.readUpToSeq)))
            ClientEvent.Unpair -> unpair()
            is ClientEvent.Hello -> repeated()
            is ClientEvent.Ack, is ClientEvent.PushRegister, ClientEvent.PushUnregister -> Unit
            else -> refuse(event)
        }
    }

    private suspend fun pong() {
        delay(connection.parts.times.pong)
        connection.send(ServerEvent.Pong)
    }

    private fun repeated() {
        connection.send(ServerEvent.Error("repeated_hello", "a session says hello once"))
        connection.close(PROTOCOL_ERROR, "mobile protocol error")
    }

    private fun unpair() {
        val phone = connection.phoneKey
        home.devices.remove(phone)
        home.forgotten += phone
        val turns = DemoTurns(home, connection.parts)
        home.claims
            .filter { (requestId, claim) -> claim.phone == phone && requestId in home.turns }
            .keys
            .forEach(turns::cancel)
        connection.close(REVOKED, "device revoked")
    }

    private fun refuse(event: ClientEvent) =
        connection.send(ServerEvent.Error("unsupported_event", "a paired session takes no ${nameOf(event)}"))
}

/** An event's name on the wire, for a refusal's message. */
internal fun nameOf(event: ClientEvent): String = event::class.simpleName.orEmpty()
