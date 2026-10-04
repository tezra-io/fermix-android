package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** The most events one connection carries before the session reconnects; the daemon closes far sooner, at an hour. */
private const val MAX_EVENTS_PER_CONNECTION = 1_000_000

/**
 * The heap core-protocol asks core-session to budget for: a logical event is up to 1 MiB of text, whose
 * tree takes tens of MiB when it holds many small values. The reader hands each decoded event to the
 * actor directly and decodes the next only once the actor took it, so a connection holds at most two
 * decoded events, the actor's and the reader's, beside the one `event_part` run being joined. A slow
 * announcer slows the reading, not the heap; the pongs it keeps unread are not missed (handOff).
 */
private const val INPUT_BUFFER = Channel.RENDEZVOUS

/** The keepalive's wakes on one connection: a ping every 25 s for an hour is 144, with room for sends between. */
private const val MAX_KEEPALIVE_WAKES = 1_000

/**
 * One connection from its `hello_ack` until it ends: a reader that answers `pong`s itself and hands
 * every other event on in order; an actor that reconciles, then applies each event; the keepalive; a
 * watch on the network, whose change is a reason to race again (design section 5.1); and the uploads,
 * which end it when one stalls. The first of them to end the connection says how; the others stop with it.
 */
internal suspend fun serve(
    core: SessionCore,
    live: Live,
    requests: Requests,
    ack: ServerEvent.HelloAck,
): Ending =
    coroutineScope {
        val inputs = Channel<Input>(INPUT_BUFFER)
        val dispatch = Dispatch(core, live, requests)
        val inbox = Inbox(inputs, dispatch, core::now)
        val reconciler = Reconciler(core, live, requests, inbox)
        val network = core.parts.network.value
        launch { read(live, inputs, core::now) }
        val endings =
            listOf(
                async { act(reconciler, inbox, dispatch, ack) },
                async { keepAlive(core, live) },
                async { networkChange(core.parts.network, network) },
                async { live.uploads.run() },
            )
        val ending = select { endings.forEach { running -> running.onAwait { it } } }
        coroutineContext.cancelChildren()
        ending
    }

private suspend fun read(
    live: Live,
    inputs: SendChannel<Input>,
    now: () -> Long,
) {
    repeat(MAX_EVENTS_PER_CONNECTION) {
        val input = live.channel.receive()
        val frame = (input as? Input.Frame)?.decoded
        when {
            frame == null -> return inputs.send(input)
            frame.v != SESSION_VERSION -> return inputs.send(Input.End(Ending.ProtocolError("a v${frame.v} frame")))
            frame.event == ServerEvent.Pong -> live.pong()
            else -> handOff(live.keepalive, inputs, input, now)
        }
    }
    inputs.send(Input.End(Ending.LifetimeReached))
}

/**
 * Hands [input] to the actor, which takes it once it is done with the one before. Until it does the
 * reader reads nothing, pongs included, so the [keepalive] counts none of that time against the link.
 */
private suspend fun handOff(
    keepalive: Keepalive,
    inputs: SendChannel<Input>,
    input: Input,
    now: () -> Long,
) {
    keepalive.stall(now())
    inputs.send(input)
    keepalive.unstall(now())
}

private suspend fun act(
    reconciler: Reconciler,
    inbox: Inbox,
    dispatch: Dispatch,
    ack: ServerEvent.HelloAck,
): Ending =
    try {
        reconciler.reconcile(ack)
        repeat(MAX_EVENTS_PER_CONNECTION) {
            val frame = inbox.next()
            dispatch.event(frame.event, frame.raw)
        }
        Ending.LifetimeReached
    } catch (ended: ConnectionEnded) {
        ended.ending
    } catch (refused: SessionProtocolError) {
        Ending.ProtocolError(refused.toString())
    }

private suspend fun keepAlive(
    core: SessionCore,
    live: Live,
): Ending {
    repeat(MAX_KEEPALIVE_WAKES) {
        delay(live.keepalive.nextCheckMs - core.now())
        when (live.keepalive.poll(core.now())) {
            // poll counted the ping as sent.
            KeepaliveAction.PING -> live.channel.send(ClientEvent.Ping)

            KeepaliveAction.LOST -> return Ending.KeepaliveLost

            KeepaliveAction.NONE -> Unit
        }
    }
    return Ending.LifetimeReached
}

private suspend fun networkChange(
    network: StateFlow<NetworkFacts>,
    at: NetworkFacts,
): Ending {
    network.first { it != at }
    return Ending.NetworkChanged
}
