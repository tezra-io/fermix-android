package io.tezra.fermix.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion

/** Chips the card shows; the rest are "+n more" (design section 13.5). */
private const val SHOWN_CHIPS = 2

/** The running arc's sweep, a quarter of the ring on each side of its top (the canon's `.arc`). */
private const val ARC_SWEEP = 180f
private const val ARC_START = -90f
private const val FULL_TURN = 360f

/** A running chip's glyph and the ring around it (design section 13.5: "16 dp glyph"). */
private val GLYPH = 16.dp
private val ARC_RING = 22.dp

/** The chip's words, 500 13/20 (the canon's `.chip`). */
private val CHIP_STYLE = FermixType.label.copy(fontSize = 13.sp)

/** Each verb's 16 dp glyph, from the visual canon's icons. */
private fun glyphOf(verb: ToolVerb): Int =
    when (verb) {
        ToolVerb.RUNNING_SHELL, ToolVerb.CODING -> R.drawable.ic_chat_term
        ToolVerb.SEARCHING_WEB, ToolVerb.SEARCHING_FILES -> R.drawable.ic_chat_search
        ToolVerb.READING_PAGE, ToolVerb.USING_BROWSER -> R.drawable.ic_chat_globe
        ToolVerb.READING_FILES -> R.drawable.ic_chat_file
        ToolVerb.EDITING_FILE -> R.drawable.ic_chat_pencil
        ToolVerb.CHECKING_MEMORY -> R.drawable.ic_chat_chat
        ToolVerb.WORKING_WITH_HELPER -> R.drawable.ic_chat_swap
        ToolVerb.MAKING_IMAGE -> R.drawable.ic_chat_image
        ToolVerb.SCHEDULING -> R.drawable.ic_chat_clock
        ToolVerb.USING_COMPUTER -> R.drawable.ic_chat_laptop
        ToolVerb.WORKING -> R.drawable.ic_chat_hour
    }

/** Each verb's words (design section 13.5's verb map). */
private val VERB_WORDS: Map<ToolVerb, Int> =
    mapOf(
        ToolVerb.RUNNING_SHELL to R.string.chat_verb_running_shell,
        ToolVerb.SEARCHING_WEB to R.string.chat_verb_searching_web,
        ToolVerb.READING_PAGE to R.string.chat_verb_reading_page,
        ToolVerb.USING_BROWSER to R.string.chat_verb_using_browser,
        ToolVerb.READING_FILES to R.string.chat_verb_reading_files,
        ToolVerb.EDITING_FILE to R.string.chat_verb_editing_file,
        ToolVerb.SEARCHING_FILES to R.string.chat_verb_searching_files,
        ToolVerb.CHECKING_MEMORY to R.string.chat_verb_checking_memory,
        ToolVerb.WORKING_WITH_HELPER to R.string.chat_verb_working_with_helper,
        ToolVerb.CODING to R.string.chat_verb_coding,
        ToolVerb.MAKING_IMAGE to R.string.chat_verb_making_image,
        ToolVerb.SCHEDULING to R.string.chat_verb_scheduling,
        ToolVerb.USING_COMPUTER to R.string.chat_verb_using_computer,
        ToolVerb.WORKING to R.string.chat_verb_working,
    )

/** A verb's words. */
@Composable
internal fun verbWords(verb: ToolVerb): String = stringResource(VERB_WORDS.getValue(verb))

/**
 * The card's tool chips (design section 13.5): the latest two, stacked, each 28 dp with its 16 dp glyph and
 * verb, a thin accent arc turning while it runs and a check in the second ink once it stopped; "+n more" for
 * the rest. A chip scales in from 0.92 as it lands (section 13.1), at once under reduce-motion. TalkBack reads
 * each as "{verb}, started" or "{verb}, finished" (section 13.8).
 */
@Composable
internal fun ToolChips(
    chips: List<LiveChip>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Keyed by the place each started in, so a chip that slides up stays itself and only a new one scales in.
        chips
            .withIndex()
            .toList()
            .takeLast(SHOWN_CHIPS)
            .forEach { (index, chip) -> key(index) { ToolChip(chip) } }
        val more = chips.size - SHOWN_CHIPS
        if (more > 0) {
            Text(
                text = pluralStringResource(R.plurals.chat_chips_more, more, more),
                style = CHIP_STYLE,
                color = LocalFermixColors.current.inkSecondary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolChip(chip: LiveChip) {
    val colors = LocalFermixColors.current
    val verb = toolVerb(chip.tool)
    val words = verbWords(verb)
    val spoken =
        if (chip.running) {
            stringResource(
                R.string.chat_chip_started,
                words,
            )
        } else {
            stringResource(R.string.chat_chip_finished, words)
        }
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (reduced) 1f else FermixMotion.TOOL_CHIP_SCALE_FROM) }
    val spring = LocalFermixMotion.current.fastSpatial
    LaunchedEffect(scale) { scale.animateTo(1f, spring.spec(reduced)) }
    Row(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }.heightIn(min = 28.dp)
                .background(colors.agentBubble, FermixShapes.chip)
                .padding(start = 8.dp, end = 10.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chip.running) {
            RunningArc(glyphOf(verb))
        } else {
            Icon(
                painterResource(R.drawable.ic_chat_check),
                null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(words, style = CHIP_STYLE, color = if (chip.running) colors.ink else colors.inkSecondary)
    }
}

/**
 * The running arc (the canon's `.arc`): a hairline ring, half of it in the accent ink, turning, around the
 * tool's glyph. The glyph is 16 dp, as design section 13.5 says, in a 22 dp ring; the canon draws 11 in 20,
 * and the doc wins (reported to the owner).
 */
@Composable
private fun RunningArc(glyph: Int) {
    val colors = LocalFermixColors.current
    val turn =
        if (LocalReducedMotion.current) {
            0f
        } else {
            val angle by rememberInfiniteTransition(label = "arc").animateFloat(
                initialValue = 0f,
                targetValue = FULL_TURN,
                animationSpec =
                    infiniteRepeatable(
                        tween(FermixMotion.TOOL_CHIP_ARC_MILLIS, easing = LinearEasing),
                        RepeatMode.Restart,
                    ),
                label = "arc turn",
            )
            angle
        }
    Box(modifier = Modifier.size(ARC_RING), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ARC_RING).rotate(turn)) {
            val stroke = Stroke(width = 1.5.dp.toPx())
            drawCircle(colors.hairline, style = stroke)
            drawArc(colors.accentInk, ARC_START, ARC_SWEEP, useCenter = false, style = stroke)
        }
        Icon(painterResource(glyph), null, tint = colors.ink, modifier = Modifier.size(GLYPH))
    }
}
