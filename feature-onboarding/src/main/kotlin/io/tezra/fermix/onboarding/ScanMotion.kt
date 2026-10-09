package io.tezra.fermix.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HintShake
import io.tezra.fermix.design.LEAST_ALPHA
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.Moment
import io.tezra.fermix.design.ReticleMotion
import io.tezra.fermix.design.hintShakeAt
import io.tezra.fermix.design.rememberLoop
import io.tezra.fermix.design.rememberMoment
import io.tezra.fermix.design.reticleBreathAt
import io.tezra.fermix.design.reticleEnterAt
import io.tezra.fermix.design.reticleShownAt
import io.tezra.fermix.design.sample

/** No visit the reticle has settled in on yet: the scan's visits count from 0. */
private const val NOT_SETTLED = -1

// The visual canon's reticle: 240 dp, its corners drawn 3 dp wide on a 16 dp radius and 44 dp long.
private val RETICLE = 240.dp
private val RETICLE_STROKE = 3.dp
private val RETICLE_CORNER = 16.dp
private val RETICLE_ARM = 44.dp

/**
 * The reticle as drawn (the M51 update's 7.4): its [scale] about its centre, how far it has faded in, [shown], and the
 * alpha of the white that flashes inside it, [flash].
 */
@Immutable
data class ReticlePose(
    val scale: Float = 1f,
    val shown: Float = 1f,
    val flash: Float = 0f,
) {
    companion object {
        /** Standing, whole, with no flash. */
        val Rest = ReticlePose()
    }
}

/** Scan's motion as drawn: the reticle in [reticle], and the hint moved sideways by [shake], a share of its reach. */
@Stable
internal class ScanMotion(
    val reticle: () -> ReticlePose,
    val shake: () -> Float,
)

/**
 * The reticle as it moves (the M51 update's 7.4), read as it is drawn: it settles from 108 % as it fades in, once a
 * [visit], as it is first [shown] in it, so on a first pairing once the camera is allowed and not while the rationale
 * or the system's prompt is up, and anew each time the scan comes back; it breathes between 100 % and 102 % while the
 * camera searches, a loop that runs while it is [shown] and nothing is [found]; on a code it stops, locks from where
 * it stood to 66 % on the standard scheme's fastSpatial (the reader gives no bounds this screen can place: see
 * ScanEntry), and flashes white inside. Each moment is played as it starts, so a rotation, even mid-way, finds the
 * reticle settled, or locked with its flash over. Under Remove animations, on from the start or turned on mid-breath,
 * it stands at 100 %, locks at once and never flashes.
 */
@Composable
internal fun rememberReticle(
    shown: Boolean,
    found: Boolean,
    visit: Int,
): () -> ReticlePose {
    val reduced = LocalReducedMotion.current
    // The visit the reticle has settled in on, saved so that a rotation finds it settled.
    var settled by rememberSaveable { mutableIntStateOf(NOT_SETTLED) }
    val enter = key(visit, shown) { rememberMoment(ReticleMotion.ENTER_MILLIS, played = !shown || settled == visit) }
    LaunchedEffect(visit, shown) { if (shown) settled = visit }
    val breath = rememberLoop(running = shown && !found)
    // A reticle drawn anew on a code already found, after a rotation, stands locked.
    val lock = remember { Animatable(if (found) ReticleMotion.LOCKED else 1f) }
    LaunchedEffect(found, reduced) {
        if (!found || lock.value == ReticleMotion.LOCKED) return@LaunchedEffect
        lock.snapTo(reticleBreathAt(breath.ms))
        lock.animateTo(ReticleMotion.LOCKED, ReticleMotion.lockSpring.spec(reduced))
    }
    val flash = rememberFlash(found)
    return {
        val locked = if (reduced) ReticleMotion.LOCKED else lock.value
        val breathing = if (reduced) 1f else reticleBreathAt(breath.ms)
        ReticlePose(
            scale = reticleEnterAt(enter.ms) * (if (found) locked else breathing),
            shown = reticleShownAt(enter.ms),
            flash = sample(ReticleMotion.flash, flash.ms),
        )
    }
}

/**
 * The flash's clock: it plays once a code is [found], played as it starts, and stands over, dark, until then and after
 * a rotation.
 */
@Composable
private fun rememberFlash(found: Boolean): Moment {
    var flashed by rememberSaveable { mutableStateOf(false) }
    val flash = key(found) { rememberMoment(ReticleMotion.flash.last().ms, played = !found || flashed) }
    LaunchedEffect(found) { flashed = found }
    return flash
}

/**
 * The hint's shake, a share of its reach, read as it is drawn: once [refused], three cycles dying away over 300 ms,
 * once, played as it starts, so not again after a rotation, even mid-shake; none under Remove animations.
 */
@Composable
internal fun rememberShake(refused: Boolean): () -> Float {
    if (!refused) return { 0f }
    var shaken by rememberSaveable { mutableStateOf(false) }
    val shake = rememberMoment(HintShake.MILLIS, shaken)
    LaunchedEffect(Unit) { shaken = true }
    return { hintShakeAt(shake.ms) }
}

/**
 * The hint, or the refusal in its place, a polite live region so TalkBack reads the refusal as it comes: the one
 * cross-fades into the other, drawn from the first frame so TalkBack reaches it, or simply changes under Remove
 * animations, and the hint moves sideways by [shake], a share of its reach.
 */
@Composable
internal fun Hint(
    refused: Boolean,
    shake: () -> Float,
    modifier: Modifier = Modifier,
) {
    val shaken = modifier.graphicsLayer { translationX = shake() * HintShake.reach.toPx() }
    if (LocalReducedMotion.current) {
        HintLine(refused, shaken)
        return
    }
    AnimatedContent(
        targetState = refused,
        modifier = shaken,
        transitionSpec = {
            val fade = tween<Float>(HintShake.FADE_MILLIS)
            ContentTransform(fadeIn(fade, initialAlpha = LEAST_ALPHA), fadeOut(fade), sizeTransform = null)
        },
        contentAlignment = Alignment.TopCenter,
        label = "scan hint",
    ) { shown -> HintLine(shown, Modifier) }
}

@Composable
private fun HintLine(
    refused: Boolean,
    modifier: Modifier,
) {
    val hint = if (refused) R.string.onboarding_scan_not_fermix else R.string.onboarding_scan_hint
    Text(
        text = stringResource(hint),
        style = FermixType.body,
        color = ON_CAMERA,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The reticle's four corners (the canon's `.ret`) in [pose], read as it is drawn, with its flash of white inside. */
@Composable
internal fun Reticle(
    pose: () -> ReticlePose,
    modifier: Modifier = Modifier,
) {
    Spacer(modifier = modifier.size(RETICLE).drawWithCache { reticleDrawing(pose) })
}

/**
 * The reticle in [pose], read as each frame draws it: its stroke and its measures are made once for its size, as it
 * redraws on every frame of the search's breath.
 */
private fun CacheDrawScope.reticleDrawing(pose: () -> ReticlePose): DrawResult {
    val stroke = RETICLE_STROKE.toPx()
    val line = Stroke(width = stroke, cap = StrokeCap.Round)
    val corner = Offset(stroke / 2f, stroke / 2f)
    val box = Size(size.width - stroke, size.height - stroke)
    val radius = CornerRadius(RETICLE_CORNER.toPx())
    val arm = RETICLE_ARM.toPx()
    return onDrawBehind {
        val now = pose()
        scale(now.scale) {
            if (now.flash > 0f) drawRoundRect(ON_CAMERA.copy(alpha = now.flash), cornerRadius = radius)
            // The rounded square, kept only where the four corners' arms reach.
            clipRect(arm, 0f, size.width - arm, size.height, ClipOp.Difference) {
                clipRect(0f, arm, size.width, size.height - arm, ClipOp.Difference) {
                    drawRoundRect(ON_CAMERA.copy(alpha = now.shown), corner, box, radius, style = line)
                }
            }
        }
    }
}
