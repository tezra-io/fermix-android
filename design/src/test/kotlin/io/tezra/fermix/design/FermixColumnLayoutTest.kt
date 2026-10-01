package io.tezra.fermix.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Design section 13.11, rules 1 and 2, laid out at the three windows of @FermixPreviews: FermixColumn
// called with no modifier, as a screen calls it, spans the window and centres its column in it. JUnit 4
// on Robolectric, in Roborazzi's activity, the one ComponentActivity the module's test manifest declares.
@RunWith(RobolectricTestRunner::class)
class FermixColumnLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun `a compact window gives the content its whole width`() {
        show(ColumnWidth.Wide)
        rule.onNodeWithTag(CONTENT).assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(412.dp)
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun `a medium window centres the 480 dp column`() {
        show(ColumnWidth.Narrow)
        rule.onNodeWithTag(CONTENT).assertLeftPositionInRootIsEqualTo(96.5.dp).assertWidthIsEqualTo(480.dp)
    }

    @Test
    @Config(qualifiers = "w841dp-h673dp-xhdpi")
    fun `an expanded window centres the 640 dp column`() {
        show(ColumnWidth.Wide)
        rule.onNodeWithTag(CONTENT).assertLeftPositionInRootIsEqualTo(100.5.dp).assertWidthIsEqualTo(640.dp)
    }

    private fun show(width: ColumnWidth) {
        rule.setContent {
            FermixTheme {
                FermixColumn(width) { Box(modifier = Modifier.fillMaxWidth().height(10.dp).testTag(CONTENT)) }
            }
        }
    }
}

private const val CONTENT = "content"
