package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelSource
import io.tezra.fermix.protocol.ModelState
import java.util.Locale

/** The command that switches the chat's model (design section 8.6): `model`, its args a route or "reset". */
internal const val MODEL_COMMAND = "model"

/** The args of [MODEL_COMMAND] that hand the chat back to the config's default. */
internal const val RESET_ARGS = "reset"

/**
 * The client_msg_id prefix of a model picked on the sheet or reset from a `model_unavailable` card. The canon
 * draws no "/model …" bubble for one, only the line the daemon answers it with, so the chat leaves out its
 * outbox item, but for one refused, and the owner's row the daemon writes of it, which carries its id.
 */
internal const val MODEL_PICK_PREFIX = "model-pick:"

/** Whether [clientMsgId] is a model picked on the sheet or reset from a card ([MODEL_PICK_PREFIX]). */
internal fun isModelPick(clientMsgId: String): Boolean = clientMsgId.startsWith(MODEL_PICK_PREFIX)

/**
 * The model chip in the composer's second row (design section 8.6): the provider's glyph, the model's short
 * name, and whether the chat has its own model ([overridden]: tonal-filled with a dot; else at 70 %). It is
 * the one control that is disabled, while no connection is up ([enabled]).
 */
data class ModelChip(
    val label: String,
    val glyph: String,
    val overridden: Boolean,
    val enabled: Boolean,
)

/**
 * The chip for the model the daemon last said ([live], a `model_changed`), or else `hello_ack`'s
 * `caps.model_state`: the chat's own model when it has one, the config's default otherwise. None when the
 * daemon says neither: a daemon without `model_state` has no switcher to offer.
 */
fun modelChipOf(
    live: LiveModel?,
    state: ModelState?,
    connected: Boolean,
): ModelChip? {
    val own = state?.overrideModel
    val chosen =
        when {
            live != null -> Triple(live.provider, live.label, live.source == ModelSource.OVERRIDE)
            own != null -> Triple(own.provider, own.label, true)
            state != null -> Triple(state.defaultModel.provider, state.defaultModel.label, false)
            else -> null
        }
    return chosen?.let { (provider, label, overridden) -> ModelChip(label, glyphOf(provider), overridden, connected) }
}

/**
 * A provider's glyph: the first letter of its id, "A" for anthropic as the canon draws it. The canon's "O" for
 * GPT-6 Astra is no letter of its provider, codex, which gives "C" here (README, for the owner).
 */
internal fun glyphOf(provider: String): String = provider.take(1).uppercase(Locale.ROOT)

/** A provider's group heading: its name with its first letter raised, as the canon's "Codex" and "Anthropic". */
internal fun providerName(provider: String): String = provider.replaceFirstChar { it.titlecase(Locale.ROOT) }

/** One row of the "Model" sheet (design section 8.6). */
sealed interface ModelRow {
    /** "Default · {label} (from config)", ✓ while the chat has no model of its own. */
    data class Default(
        val label: String,
        val active: Boolean,
    ) : ModelRow

    /** A provider's heading, greyed when the daemon could not list its models. */
    data class Group(
        val name: String,
        val unavailable: Boolean,
    ) : ModelRow

    /** A model to pick: its label and trait, "no live typing" when it does not stream, ✓ when it is the chat's. */
    data class Choice(
        val provider: String,
        val model: String,
        val label: String,
        val trait: String?,
        val liveTyping: Boolean,
        val active: Boolean,
    ) : ModelRow

    /** "Couldn't list models on {host}", greyed. */
    data object Unlisted : ModelRow
}

/**
 * The sheet's rows from the `models` [entries] (design section 8.6): the default first, then each provider's
 * models under its name, in the order the daemon listed them; a provider whose listing failed shows greyed
 * with its one sentence. [defaultLabel] is the config's model's, and with none the default has no row;
 * [overridden] whether the chat has its own, which then carries the ✓. The entry the daemon marks default is
 * the Default row's, as the canon leaves GPT-6 Astra out of Codex, unless it is the chat's own; a provider left
 * with no model has no heading. None of it is free text, and nothing sets a provider up.
 */
fun modelRowsOf(
    entries: List<ModelEntry>,
    defaultLabel: String?,
    overridden: Boolean,
): List<ModelRow> {
    val byProvider = entries.groupBy { it.provider }
    val groups =
        byProvider.flatMap { (provider, listed) ->
            val unavailable = listed.any { it.listingUnavailable == true }
            val choices =
                listed
                    .filterNot { defaultLabel != null && it.isDefault == true && !(overridden && it.active == true) }
                    .mapNotNull { entry -> choiceOf(entry, overridden) }
            val rows = if (unavailable) listOf(ModelRow.Unlisted) else choices
            if (rows.isEmpty()) emptyList() else listOf(ModelRow.Group(providerName(provider), unavailable)) + rows
        }
    return listOfNotNull(defaultLabel?.let { ModelRow.Default(it, active = !overridden) }) + groups
}

/**
 * The open sheet's rows (design section 8.6): none while the models come; the listed ones (modelRowsOf); or,
 * when the pull came to nothing, the default and "Couldn't list models on {host}", greyed.
 */
fun sheetRowsOf(
    sheet: ModelSheet,
    state: ModelState?,
    overridden: Boolean,
): List<ModelRow> =
    when (sheet) {
        ModelSheet.Loading -> {
            emptyList()
        }

        is ModelSheet.Listed -> {
            modelRowsOf(sheet.entries, defaultLabelOf(state, sheet.entries), overridden)
        }

        ModelSheet.Unlisted -> {
            modelRowsOf(emptyList(), defaultLabelOf(state, emptyList()), overridden) +
                ModelRow.Unlisted
        }
    }

private fun choiceOf(
    entry: ModelEntry,
    overridden: Boolean,
): ModelRow.Choice? {
    val model = entry.model ?: return null
    val active = overridden && entry.active == true
    return ModelRow.Choice(entry.provider, model, entry.label ?: model, entry.trait, entry.streams != false, active)
}

/** The config's model's label: `caps.model_state.default`'s, or else the entry the daemon marks default. */
fun defaultLabelOf(
    state: ModelState?,
    entries: List<ModelEntry>,
): String? = state?.defaultModel?.label ?: entries.firstOrNull { it.isDefault == true }?.let { it.label ?: it.model }

/**
 * A pick on the sheet as the command the daemon takes (design section 8.6): `model` with "{provider}/{model}",
 * or with "reset" for the default.
 */
fun modelCommand(
    pick: ModelRow,
    clientMsgId: String,
    profileId: String,
): ClientEvent.Command {
    val args =
        when (pick) {
            is ModelRow.Choice -> "${pick.provider}/${pick.model}"
            is ModelRow.Default -> RESET_ARGS
            else -> throw IllegalArgumentException("only a model or the default is picked")
        }
    return ClientEvent.Command(clientMsgId, profileId, MODEL_COMMAND, args)
}
