package io.tezra.fermix.chat

import androidx.compose.ui.graphics.ImageBitmap

/**
 * What the timeline's cards do: answer an approval card ([onAnswer], its id and whether it approves), a link
 * preview's thumbnail by its `image_ref` ([thumbnail], none when it is not to be had), and a preview's tap
 * ([onLink], its URL into a Custom Tab).
 */
data class CardActions(
    val onAnswer: (approvalId: String, approve: Boolean) -> Unit = { _, _ -> },
    val thumbnail: suspend (ref: String) -> ImageBitmap? = { null },
    val onLink: (url: String) -> Unit = {},
)

/** What the model chip and its "Model" sheet do (design section 8.6): open it, pick a row, close it. */
data class ModelActions(
    val onOpen: () -> Unit = {},
    val onPick: (ModelRow) -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * What search does (design section 13.7, ChatSearch): open it, the query and the chip, the list's end, "Try
 * again" once the daemon's search failed, a hit picked, ▲ ([onStep] true) and ▼, back from the chat to the list
 * and out, and close.
 */
data class SearchActions(
    val onOpen: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onChip: (SearchChip) -> Unit = {},
    val onMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPick: (Int) -> Unit = {},
    val onStep: (older: Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/** [search]'s actions. */
internal fun searchActionsOf(search: ChatSearch): SearchActions =
    SearchActions(
        onOpen = search::open,
        onQuery = search::query,
        onChip = search::chip,
        onMore = search::more,
        onRetry = search::retry,
        onPick = search::pick,
        onStep = search::step,
        onBack = search::back,
        onClose = search::close,
    )
