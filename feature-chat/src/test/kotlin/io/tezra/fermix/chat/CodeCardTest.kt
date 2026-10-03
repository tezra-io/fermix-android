package io.tezra.fermix.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The canon's ink on the code card and two of its tints (`.code`, `.code .k`, `.code .f`). */
private val INK = Color(0xFFE6E7EB)
private val KEYWORD = Color(0xFFB69CFF)
private val MARK = Color(0xFF7FB2FF)

private const val KOTLIN = "suspend fun upload(file: File) {\n    val tries = 3\n}"

/** One fence that Kotlin's lexer and Python's tint apart: `fun` is Kotlin's keyword, `def` Python's. */
private const val KOTLIN_OR_PYTHON = "fun upload\ndef upload"

/** How long a tint that another thread brings is waited for, in real time and again on the main clock. */
private const val LATE_MS = 1_000L

/** A Kotlin fence of [length] characters, its lines all alike. */
private fun kotlinOf(length: Int): String = "val x = 1\n".repeat(length / 10 + 1).take(length)

/**
 * The code card's tint is a fact of its first frame (design section 13.5): a tinted language's fence is laid out
 * in the canon's tints by the composition that first draws it, and an untinted language's, or a tinted one's past
 * TINT_MAX_CHARS, in the code's ink alone, then and later. The clock stands still from before the card is set, so
 * no frame follows the first, and a tint that a later frame or another thread brought could never be read there;
 * for the untinted fences, the later frames are read too, after a wait such a thread would have finished in. A
 * fence whose language changes is tinted by its new lexer, never left in the old one's tints. JUnit 4, in
 * Roborazzi's activity, on the compact window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class CodeCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    /** Sets the card for [code], a fence of [info]'s language, with the clock standing still. */
    private fun setCard(
        info: () -> FenceInfo,
        code: String,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme { CodeCard(info(), code, "c1", TextActions({}, {}), FermixShapes.card) }
        }
    }

    /** What the card lays out now, found by the [shown] words. */
    private fun layoutOf(shown: String): TextLayoutInput {
        val layout = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(shown, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layout)
        return layout.single().layoutInput
    }

    /** What the card's first frame lays out for [code], a fence of [language], found by the [shown] words. */
    private fun firstFrame(
        language: String,
        code: String,
        shown: String,
    ): TextLayoutInput {
        setCard({ FenceInfo(language, null) }, code)
        return layoutOf(shown)
    }

    /** What the card lays out once a thread of its own would have tinted it: [LATE_MS] of real time, then frames. */
    private fun laterFrame(shown: String): TextLayoutInput {
        rule.mainClock.advanceTimeByFrame()
        Thread.sleep(LATE_MS)
        rule.mainClock.advanceTimeBy(LATE_MS)
        return layoutOf(shown)
    }

    /** The colour a span gives the character at [index] of the laid-out text, the last span's that sets one. */
    private fun TextLayoutInput.tintAt(index: Int): Color =
        text.spanStyles
            .lastOrNull { index >= it.start && index < it.end && it.item.color.isSpecified }
            ?.item
            ?.color ?: Color.Unspecified

    private fun assertPlainInk(input: TextLayoutInput) {
        assertEquals(INK, input.style.color)
        val tints = input.text.spanStyles.filter { it.item.color.isSpecified }
        assertTrue("spans tint the code: $tints", tints.isEmpty())
    }

    @Test
    fun `a Kotlin fence is in the canon's tints in its first frame`() {
        val input = firstFrame("kotlin", KOTLIN, "suspend fun")
        assertEquals(KOTLIN, input.text.text)
        listOf("suspend", "fun", "val").forEach { keyword ->
            assertEquals(keyword, KEYWORD, input.tintAt(KOTLIN.indexOf(keyword)))
        }
        listOf("(", "{", "=", "}").forEach { mark -> assertEquals(mark, MARK, input.tintAt(KOTLIN.indexOf(mark))) }
    }

    @Test
    fun `a fence of a language the design does not name is in the code's ink alone, though highlights has it`() {
        val rust = "fn export(report: &Report) -> Result<(), Error> {\n    let tries = 3;\n}"
        val input = firstFrame("rust", rust, "fn export")
        assertEquals(rust, input.text.text)
        assertPlainInk(input)
        assertPlainInk(laterFrame("fn export"))
    }

    @Test
    fun `a Kotlin fence of TINT_MAX_CHARS characters is still tinted in its first frame`() {
        // The control for the one past the bound: the same lines, folded alike, one character shorter.
        val input = firstFrame("kotlin", kotlinOf(TINT_MAX_CHARS), "val x = 1")
        assertEquals(folded(kotlinOf(TINT_MAX_CHARS)), input.text.text)
        assertEquals(KEYWORD, input.tintAt(0))
    }

    @Test
    fun `a Kotlin fence past TINT_MAX_CHARS is in the code's ink alone, in its first frame and later`() {
        val input = firstFrame("kotlin", kotlinOf(TINT_MAX_CHARS + 1), "val x = 1")
        assertEquals(folded(kotlinOf(TINT_MAX_CHARS + 1)), input.text.text)
        assertPlainInk(input)
        assertPlainInk(laterFrame("val x = 1"))
    }

    @Test
    fun `a fence whose language changes is tinted by the new lexer in the next frame`() {
        var info by mutableStateOf(FenceInfo("kotlin", null))
        setCard({ info }, KOTLIN_OR_PYTHON)
        assertEquals(KEYWORD, layoutOf("fun upload").tintAt(KOTLIN_OR_PYTHON.indexOf("fun")))
        info = FenceInfo("python", null)
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        val input = layoutOf("fun upload")
        assertEquals(KEYWORD, input.tintAt(KOTLIN_OR_PYTHON.indexOf("def")))
        assertNotEquals(KEYWORD, input.tintAt(KOTLIN_OR_PYTHON.indexOf("fun")))
    }
}
