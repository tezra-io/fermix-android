package io.tezra.fermix.design

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the owner asked for reduced motion (design sections 13.1 and 13.8). Three behaviours read it:
 * springs snap ([MotionSpring.spec]), the working indicator's line stops shimmering and changes without
 * the cross-fade, and the streaming cursor stands still. The visual canon's Motion table has the
 * indicator hold a static "Thinking…" instead; section 13.1, which keeps the phrase changing, is followed.
 * Read outside FermixTheme, it fails.
 */
val LocalReducedMotion = staticCompositionLocalOf<Boolean> { error("LocalReducedMotion is read outside FermixTheme.") }

/**
 * Android's "Remove animations" sets the animator duration scale to 0; any other scale, slowed or sped
 * up for development, still animates.
 */
fun isReducedMotion(animatorDurationScale: Float): Boolean {
    require(animatorDurationScale >= 0f) { "An animator duration scale is 0 or more, not $animatorDurationScale." }
    return animatorDurationScale == 0f
}

/**
 * The system's animator duration scale, as reduce-motion, kept current: Android does not recreate an
 * Activity when the owner changes it, so the setting is observed for as long as the theme is shown.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(animatorDurationScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = AnimatorDurationScaleObserver(resolver) { changed -> scale = changed }
        val setting = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(setting, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return isReducedMotion(scale)
}

/** The platform's own default when the setting was never changed is 1, a normal speed. */
private fun animatorDurationScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

private class AnimatorDurationScaleObserver(
    private val resolver: ContentResolver,
    private val onScale: (Float) -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
        onScale(animatorDurationScale(resolver))
    }
}
