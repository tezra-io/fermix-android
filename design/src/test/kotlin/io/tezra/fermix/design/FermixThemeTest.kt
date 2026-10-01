package io.tezra.fermix.design

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Design section 13.1 composed: Material 3 handed the design's colours, type and shapes in each mode,
// the standard springs app-wide, the expressive ones inside ExpressiveMotion, and reduce-motion read from
// the system once the owner removes animations. JUnit 4 on Robolectric, in Roborazzi's activity, the one
// ComponentActivity the module's test manifest declares.
@RunWith(RobolectricTestRunner::class)
class FermixThemeTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private class Seen(
        val reducedMotion: Boolean,
        val standard: FermixMotionScheme,
        val expressive: FermixMotionScheme,
    )

    @Test
    fun `the theme moves on the standard springs, and ExpressiveMotion on the expressive ones`() {
        val seen = show()
        assertEquals(false, seen.reducedMotion)
        assertEquals(FermixMotionScheme.Standard, seen.standard)
        assertEquals(FermixMotionScheme.Expressive, seen.expressive)
    }

    @Test
    fun `with animations removed, the theme reduces motion`() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(true, show().reducedMotion)
    }

    @Test
    fun `in light mode, Material's components draw in the design's colours, type and shapes`() {
        assertHandedOver(FermixColors.Light, handedToMaterial(darkTheme = false))
    }

    @Test
    fun `in dark mode, Material's components draw in the design's colours, type and shapes`() {
        assertHandedOver(FermixColors.Dark, handedToMaterial(darkTheme = true))
    }

    @Test
    fun `the motion scheme read outside FermixTheme fails`() {
        val failure = assertThrows(IllegalStateException::class.java) { rule.setContent { LocalFermixMotion.current } }
        assertEquals("LocalFermixMotion is read outside FermixTheme.", failure.message)
    }

    private fun show(): Seen {
        var seen: Seen? = null
        rule.setContent {
            FermixTheme {
                val standard = LocalFermixMotion.current
                val reducedMotion = LocalReducedMotion.current
                ExpressiveMotion { seen = Seen(reducedMotion, standard, LocalFermixMotion.current) }
            }
        }
        rule.waitForIdle()
        return checkNotNull(seen) { "FermixTheme composed nothing." }
    }

    private class Handed(
        val colorScheme: ColorScheme,
        val typography: Typography,
        val shapes: Shapes,
    )

    private fun handedToMaterial(darkTheme: Boolean): Handed {
        var handed: Handed? = null
        rule.setContent {
            FermixTheme(darkTheme = darkTheme) {
                handed = Handed(MaterialTheme.colorScheme, MaterialTheme.typography, MaterialTheme.shapes)
            }
        }
        rule.waitForIdle()
        return checkNotNull(handed) { "FermixTheme composed nothing." }
    }

    private fun assertHandedOver(
        colors: FermixColors,
        handed: Handed,
    ) {
        val scheme = handed.colorScheme
        assertEquals(colors.accent, scheme.primary)
        assertEquals(colors.onAccent, scheme.onPrimary)
        assertEquals(colors.canvas, scheme.surface)
        assertEquals(colors.tonalSolid, scheme.surfaceContainerHigh)
        assertEquals(colors.hairline, scheme.outlineVariant)
        assertEquals(colors.err, scheme.error)
        assertEquals(FermixType.body, handed.typography.bodyLarge)
        assertEquals(FermixType.labelSmall, handed.typography.labelSmall)
        assertEquals(FermixShapes.card, handed.shapes.medium)
        assertEquals(RoundedCornerShape(28.dp), handed.shapes.extraLarge)
    }
}
