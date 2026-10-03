package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.session.OneShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The "Model" sheet while it is open: its models on their way, listed, or not to be had. */
sealed interface ModelSheet {
    data object Loading : ModelSheet

    data class Listed(
        val entries: List<ModelEntry>,
    ) : ModelSheet

    /** The pull came to nothing ([OneShot] other than an answer): the default alone, and the one sentence. */
    data object Unlisted : ModelSheet
}

/**
 * The chat's model switcher (design section 8.6): the sheet, which pulls the models as it opens; a pick, sent
 * as `command{name:"model"}` through the outbox like any command, under an id the chat draws no bubble for
 * ([MODEL_PICK_PREFIX]); and "Switches after this reply" while a pick
 * made during a turn waits for it to end ([switchPending], let go by [turnsEnded] as `turn_done` ends it).
 * The chip that opens it is disabled with no connection, so the pull goes over one.
 */
class ChatModels(
    private val requests: ChatRequests,
    private val session: StateFlow<ChatSession?>,
    private val scope: CoroutineScope,
    private val profileId: String,
    private val newId: () -> String,
    private val log: (String, Throwable?) -> Unit,
) {
    private val shown = MutableStateFlow<ModelSheet?>(null)
    private val pending = MutableStateFlow(false)
    private var pulling: Job? = null

    /** The sheet, none while it is closed. */
    val sheet: StateFlow<ModelSheet?> = shown.asStateFlow()

    /** Whether a model picked during a turn waits for it to end. */
    val switchPending: StateFlow<Boolean> = pending.asStateFlow()

    /** Opens the sheet and pulls the models, once at a time. */
    fun open() {
        if (shown.value != null) return
        shown.value = ModelSheet.Loading
        pulling = scope.launch { shown.value = listed(session.value?.pullModels() ?: OneShot.Offline) }
    }

    fun close() {
        pulling?.cancel()
        shown.value = null
    }

    /** [row] picked while a turn [turnRuns] or not: its command goes, and the sheet closes. */
    fun pick(
        row: ModelRow,
        turnRuns: Boolean,
    ) {
        val command = modelCommand(row, "$MODEL_PICK_PREFIX${newId()}", profileId)
        close()
        scope.launch {
            val taken = requests.send(command)
            if (taken && turnRuns) pending.value = true
            if (!taken) log("A model picked with no session to take it", null)
        }
    }

    /** No turn runs any more: the pick that waited for one has switched. */
    fun turnsEnded() {
        pending.value = false
    }

    /** The sheet once the pull ended: its models, or the default alone when it got none. */
    private fun listed(outcome: OneShot<List<ModelEntry>>): ModelSheet {
        if (outcome !is OneShot.Answered) log("The models were not listed: ${outcome::class.simpleName}", null)
        return (outcome as? OneShot.Answered)?.let { ModelSheet.Listed(it.value) } ?: ModelSheet.Unlisted
    }
}
