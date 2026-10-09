package io.tezra.fermix.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.Countdown
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark

/** The SAS's digits, as core-session derives it (design section 6.2). */
private const val SAS_DIGITS = 6

/** The pairing window the countdown runs down, core-session's 120 s (section 13.3's "2 minutes"). */
const val PAIRING_COUNTDOWN_SECONDS = 120

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60

// The visual canon's `.cring`: a 40 dp ring, its circle 16 dp in radius, drawn 3 dp wide from the top.
private val RING = 40.dp
private val RING_RADIUS = 16.dp
private val RING_STROKE = 3.dp
private val RING_GAP = 12.dp
private const val FULL_TURN = 360f
private const val TOP = -90f

/** The canon's 14 / 20 at 500, with tabular figures so that the clock does not shift as it ticks. */
private val CLOCK = FermixType.label.copy(fontFeatureSettings = "tnum")

/** How far a digit rises as it lands. */
private val DIGIT_RISE = 12.dp

/** [sas] as the screen shows it, in two groups of three: `481 062` (section 13.3, step 5). */
fun sasGroups(sas: String): String {
    require(sas.length == SAS_DIGITS && sas.all { it in '0'..'9' }) { "a SAS is six digits" }
    return sas.take(SAS_DIGITS / 2) + " " + sas.drop(SAS_DIGITS / 2)
}

/**
 * [sas] as TalkBack reads it, digit by digit (design section 13.8), with a pause between the groups:
 * `6 6 9, 9 7 9`. The owner compares it by ear with the computer's figures, and "669" would be read as
 * a number.
 */
fun sasSpoken(sas: String): String =
    sasGroups(sas).split(" ").joinToString(", ") { group -> group.toList().joinToString(" ") }

/**
 * The whole seconds left until [expiresAt], rounded up so that the countdown reads 0:00 when the window
 * closes and not before; never more than the window.
 */
fun secondsUntil(expiresAt: TimeMark): Int {
    val left = -expiresAt.elapsedNow()
    if (!left.isPositive()) return 0
    val seconds = (left.inWholeMilliseconds + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    return seconds.toInt().coerceAtMost(PAIRING_COUNTDOWN_SECONDS)
}

/** How long until the countdown's next whole second, so that it ticks as the second turns. */
fun millisToNextSecond(expiresAt: TimeMark): Long {
    val left = (-expiresAt.elapsedNow()).inWholeMilliseconds
    val within = left % MILLIS_PER_SECOND
    return if (within <= 0) MILLIS_PER_SECOND else within
}

/** [seconds] as the countdown shows it, m:ss: `1:42`. */
fun clock(seconds: Int): String {
    require(seconds >= 0) { "the countdown never shows less than nothing: $seconds" }
    return "${seconds / SECONDS_PER_MINUTE}:" + (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
}

/**
 * The code at 44 sp in mono, its digits landing one after another [FermixMotion.SAS_DIGIT_STAGGER_MILLIS]
 * apart, each with its `SEGMENT_TICK` (section 13.1, "Haptics"), on the expressive scheme the Verify screen
 * provides. They land once: a rotation or a fold shows them landed. Under reduce-motion they stand landed
 * from the first frame, with no ticks, since a stagger of snapping digits still moves (section 13.8).
 * TalkBack reads the code digit by digit, [sasSpoken].
 */
@Composable
fun SasCode(
    sas: String,
    modifier: Modifier = Modifier,
) {
    val groups = sasGroups(sas)
    val spoken = sasSpoken(sas)
    val view = LocalView.current
    val spring = LocalFermixMotion.current.fastSpatial
    val reduced = LocalReducedMotion.current
    var landed by rememberSaveable(sas) { mutableStateOf(reduced) }
    val digits = remember(sas) { List(SAS_DIGITS) { Animatable(if (landed) 1f else 0f) } }
    LaunchedEffect(sas) {
        if (landed) return@LaunchedEffect
        for (digit in digits) {
            HapticFeedback.perform(view, HapticUse.SasDigit)
            launch { digit.animateTo(1f, spring.spec(reduced)) }
            delay(FermixMotion.SAS_DIGIT_STAGGER_MILLIS.toLong())
        }
        landed = true
    }
    val ink = LocalFermixColors.current.ink
    Row(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        groups.forEachIndexed { index, character ->
            val shown = if (character == ' ') null else digits[if (index < SAS_DIGITS / 2) index else index - 1]
            Text(
                text = character.toString(),
                style = FermixType.sas,
                color = ink,
                modifier =
                    Modifier.graphicsLayer {
                        val landing = shown?.value ?: 1f
                        alpha = landing.coerceIn(0f, 1f)
                        translationY = (1f - landing) * DIGIT_RISE.toPx()
                    },
            )
        }
    }
}

/**
 * The countdown ring as drawn (the M51 update's 7.4): the share of the pairing window [left], and how far the stroke
 * has thickened, from its 3 dp at 0 to 5 dp at 1, [thick].
 */
@Immutable
data class RingPose(
    val left: Float,
    val thick: Float = 0f,
) {
    companion object {
        /** The ring standing at [secondsLeft], its stroke at rest. */
        fun at(secondsLeft: Int): RingPose = RingPose(secondsLeft.toFloat() / PAIRING_COUNTDOWN_SECONDS)
    }
}

/**
 * The time left, a ring that empties from the top and the clock beside it (the canon's `.cnt`): the ring in [ring],
 * read as it is drawn, in the ink on the hairline grey whatever the time, and the clock in tabular figures, changing
 * without motion. While it is [announcing], at 30 s and at 10 s left, the clock tells TalkBack the time left, politely.
 */
@Composable
fun CountdownRing(
    secondsLeft: Int,
    ring: () -> RingPose,
    announcing: Boolean,
    modifier: Modifier = Modifier,
) {
    require(secondsLeft in 0..PAIRING_COUNTDOWN_SECONDS) { "$secondsLeft s is outside the pairing window" }
    val colors = LocalFermixColors.current
    val said = pluralStringResource(R.plurals.onboarding_verify_seconds_left, secondsLeft, secondsLeft)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(RING_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(RING).drawWithCache { ringDrawing(ring, colors.hairline, colors.ink) })
        Text(
            text = clock(secondsLeft),
            style = CLOCK,
            color = colors.ink,
            modifier =
                Modifier.semantics {
                    if (announcing) {
                        contentDescription = said
                        liveRegion = LiveRegionMode.Polite
                    }
                },
        )
    }
}

/**
 * The ring in [ring], read as each frame draws it: the [track] circle and the arc of the time left in [ink]. Its
 * strokes are made once for the ring's size, as it redraws on every frame of the countdown; only the pulse, 300 ms at a
 * mark, makes the stroke it thickens to.
 */
private fun CacheDrawScope.ringDrawing(
    ring: () -> RingPose,
    track: Color,
    ink: Color,
): DrawResult {
    val radius = RING_RADIUS.toPx()
    val corner = Offset(size.center.x - radius, size.center.y - radius)
    val box = Size(radius * 2f, radius * 2f)
    val circle = Stroke(width = RING_STROKE.toPx())
    val resting = Stroke(width = RING_STROKE.toPx(), cap = StrokeCap.Round)
    return onDrawBehind {
        val now = ring()
        val width = (RING_STROKE + (Countdown.thickTo - RING_STROKE) * now.thick).toPx()
        val arc = if (now.thick == 0f) resting else Stroke(width = width, cap = StrokeCap.Round)
        drawCircle(color = track, radius = radius, style = circle)
        drawArc(ink, TOP, FULL_TURN * now.left, useCenter = false, topLeft = corner, size = box, style = arc)
    }
}
