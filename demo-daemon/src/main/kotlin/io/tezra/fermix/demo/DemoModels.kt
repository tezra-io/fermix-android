package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelRef
import io.tezra.fermix.protocol.ModelSource
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.protocol.ServerEvent

/** The config's model, which a chat with none of its own runs (feature-chat's samples' GPT-6 Astra). */
internal val DEFAULT_MODEL = ModelRef("codex", "gpt-6-astra", "GPT-6 Astra")

/** What a `/model` pick that names the config's model again says (design section 8.6). */
private const val RESET = "reset"

/**
 * The models a demo Fermix lists (design section 8.6; feature-chat's samples' sheet): two providers, the
 * config's default among the first, and one whose live listing failed.
 */
private val LISTED =
    listOf(
        ModelEntry("codex", "gpt-6-astra", "GPT-6 Astra", "the config's", streams = true),
        ModelEntry("codex", "gpt-6-sol", "GPT-6 Sol", streams = true),
        ModelEntry("codex", "gpt-6-luna", "GPT-6 Luna", "fast, cheaper", streams = true),
        ModelEntry("anthropic", "claude-opus-5.5", "Claude Opus 5.5", "best quality", streams = false),
        ModelEntry("anthropic", "claude-haiku-4.5", "Claude Haiku 4.5", "fastest", streams = false),
        ModelEntry("ollama", listingUnavailable = true),
    )

/** The route a reply of [this] Fermix's chat runs on now: its own model, or the config's. */
internal fun DemoHome.route(): Route = (model ?: DEFAULT_MODEL).let { Route(it.provider, it.model) }

/**
 * Whether the route a reply runs on now streams, as the list says of its model: `caps.streaming` and a turn's
 * `turn_started` and deltas follow it (design section 7, "truthful per the active route"; R1).
 */
internal fun DemoHome.streams(): Boolean {
    val active = route()
    val entry = LISTED.firstOrNull { it.provider == active.provider && it.model == active.model }
    return checkNotNull(entry?.streams) { "the demo lists no model ${active.provider}/${active.model}" }
}

/** The model list as one `models` page: the chat's own marked active, the config's marked its default. */
internal fun modelsOf(home: DemoHome): ServerEvent.Models {
    val active = home.model ?: DEFAULT_MODEL
    val entries =
        LISTED.map { entry ->
            if (entry.listingUnavailable == true) return@map entry
            val mine = entry.provider == active.provider && entry.model == active.model
            val config = entry.provider == DEFAULT_MODEL.provider && entry.model == DEFAULT_MODEL.model
            entry.copy(active = mine, isDefault = config)
        }
    return ServerEvent.Models(entries)
}

/**
 * The model switcher's command on one connection (design section 8.6): `/model provider/model` sets the chat's
 * own model and `/model reset` takes it away, each told to every phone as `model_changed`, the next turn running
 * on it (a turn running keeps its route); `/model` with no words answers with the list; a model the list does not
 * hold fails the request. Design section 7 also persists a switch as a `kind: "system"` timeline row, whose role
 * and words it does not give: the demo writes none, so its rows number on without one (README, "The demo",
 * departures).
 */
internal class DemoModels(
    private val connection: DemoConnection,
) {
    private val home = connection.home

    fun pick(
        clientMsgId: String,
        args: String?,
    ) {
        val claim = home.claims.getValue(clientMsgId)
        claim.state = RequestState.COMPLETED
        val entry = LISTED.firstOrNull { "${it.provider}/${it.model}" == args && it.label != null }
        when {
            args == null -> {
                connection.send(modelsOf(home))
            }

            args == RESET -> {
                changed(null)
            }

            entry == null -> {
                claim.state = RequestState.FAILED
                claim.error = REQUEST_FAILED
                connection.send(ServerEvent.Error(REQUEST_FAILED, "no model $args here", clientMsgId))
            }

            else -> {
                changed(ModelRef(entry.provider, checkNotNull(entry.model), checkNotNull(entry.label)))
            }
        }
    }

    private fun changed(picked: ModelRef?) {
        home.model = picked
        val now = picked ?: DEFAULT_MODEL
        val source = if (picked == null) ModelSource.DEFAULT else ModelSource.OVERRIDE
        home.broadcast(ServerEvent.ModelChanged(MAIN, now.provider, now.model, now.label, source))
    }
}
