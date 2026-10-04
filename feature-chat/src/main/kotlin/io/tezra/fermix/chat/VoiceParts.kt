package io.tezra.fermix.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixType

/** The waveform's height (the canon's `svg.wave`, 28). */
private val WAVE_HEIGHT = 28.dp

/** The opacity of a waveform's bars not yet played (the canon's `svg.wave`, .55). */
private const val WAVE_ALPHA = 0.55f

/** The speed chip's type (the canon's `.spd`, mono 500 11/16). */
private val SPEED = FermixType.mono.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

/**
 * [bars] as the canon's `svg.wave` draws them: each bar half its slot wide with round ends, as tall as its level,
 * centred; the share [played] of them in [color] and the rest at .55 of it, or all at [alpha] when nothing plays.
 * [modifier] gives it its width.
 */
@Composable
internal fun Wave(
    bars: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    played: Float? = null,
    alpha: Float = WAVE_ALPHA,
) {
    Canvas(modifier = modifier.heightIn(min = WAVE_HEIGHT, max = WAVE_HEIGHT)) {
        val slot = size.width / bars.size.coerceAtLeast(1)
        val bar = slot / 2
        val playedBars = played?.let { (it * bars.size).toInt() } ?: -1
        bars.forEachIndexed { index, level ->
            val height = (level * size.height).coerceAtLeast(bar)
            val tint = if (index < playedBars) color else color.copy(alpha = alpha)
            drawRoundRect(
                color = tint,
                topLeft = Offset(index * slot, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** How much of [playing] has played, 0 to 1; 0 for a note of no length. */
internal fun playedShare(playing: Playing): Float =
    if (playing.durationMs <= 0) 0f else (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f)

/** A voice note's play ↔ pause in its 36 dp circle on [background]. */
@Composable
internal fun PlayButton(
    running: Boolean,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    val label = stringResource(if (running) R.string.chat_pause else R.string.chat_play)
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .background(background, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = if (running) R.drawable.ic_chat_pause else R.drawable.ic_chat_play
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * The speed chip (the canon's `.spd`): "1×", "1.5×" or "2×" on [background], mono, its corners 10 dp, on one line
 * however narrow its row.
 */
@Composable
internal fun SpeedChip(
    speed: Float,
    background: Color,
    ink: Color,
    onClick: () -> Unit,
) {
    val words = stringResource(R.string.chat_speed, speedWords(speed))
    Text(
        words,
        style = SPEED,
        color = ink,
        maxLines = 1,
        softWrap = false,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .background(background, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A speed as its chip writes it: "1", "1.5", "2". */
internal fun speedWords(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}" else "$speed"
