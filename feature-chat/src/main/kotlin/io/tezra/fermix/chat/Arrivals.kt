package io.tezra.fermix.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse

/**
 * The turns of [arrived] not [handled] yet, oldest first: each answer arrives once. [handled] is bounded by
 * [MAX_REMEMBERED_TURNS] as the fold that remembers the turns is.
 */
fun freshArrivals(
    arrived: List<Arrival>,
    handled: Set<String>,
): List<Arrival> = arrived.filter { it.turnId !in handled }

/** [handled] with [fresh]'s turns, the newest [MAX_REMEMBERED_TURNS] kept. */
internal fun handledAfter(
    handled: Set<String>,
    fresh: List<Arrival>,
): Set<String> = (handled + fresh.map { it.turnId }).toList().takeLast(MAX_REMEMBERED_TURNS).toSet()

/**
 * An answer's arrival (design sections 13.1 and 13.8): its final bubble plays `CLOCK_TICK`, "arrive" in the
 * haptic grammar, and TalkBack reads its plain words once, from one polite live region. Each turn arrives once,
 * by its id: the answers the chat held as it opened arrived before it, a rotation keeps the ids it played, and
 * an answer that arrives while the chat is not [shown] is let go unplayed.
 */
@Composable
internal fun Arrivals(
    arrived: List<Arrival>,
    shown: Boolean,
) {
    val view = LocalView.current
    val saver = listSaver<MutableState<Set<String>>, String>({ it.value.toList() }, { mutableStateOf(it.toSet()) })
    var handled by rememberSaveable(saver = saver) { mutableStateOf(arrived.map { it.turnId }.toSet()) }
    // Not kept across a rotation: the region drawn again would be read again.
    var spoken by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(arrived, shown) {
        val fresh = freshArrivals(arrived, handled)
        if (fresh.isEmpty()) return@LaunchedEffect
        handled = handledAfter(handled, fresh)
        if (!shown) return@LaunchedEffect
        HapticFeedback.perform(view, HapticUse.FinalBubble)
        spoken = plainWords(fresh.last().words)
    }
    spoken?.let { words ->
        Box(
            modifier =
                Modifier.size(1.dp).semantics {
                    contentDescription = words
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}
