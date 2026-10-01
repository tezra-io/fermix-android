package io.tezra.fermix.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// Design section 13.11, rules 1 and 2, at the three windows of @FermixPreviews.
class FermixColumnTest {
    private val compact = DpSize(412.dp, 915.dp)
    private val medium = DpSize(673.dp, 841.dp)
    private val expanded = DpSize(841.dp, 673.dp)

    @Test
    fun `a compact window is drawn as designed, the full width`() {
        assertEquals(Dp.Infinity, columnMaxWidth(compact, ColumnWidth.Wide))
        assertEquals(Dp.Infinity, columnMaxWidth(compact, ColumnWidth.Narrow))
    }

    @Test
    fun `a medium window centres the 640 or the 480 dp column`() {
        assertEquals(640.dp, columnMaxWidth(medium, ColumnWidth.Wide))
        assertEquals(480.dp, columnMaxWidth(medium, ColumnWidth.Narrow))
    }

    @Test
    fun `an expanded window centres the same columns`() {
        assertEquals(640.dp, columnMaxWidth(expanded, ColumnWidth.Wide))
        assertEquals(480.dp, columnMaxWidth(expanded, ColumnWidth.Narrow))
    }

    @Test
    fun `the window is compact below 600 dp of width, whatever its height`() {
        assertEquals(Dp.Infinity, columnMaxWidth(DpSize(599.dp, 2000.dp), ColumnWidth.Narrow))
        assertEquals(480.dp, columnMaxWidth(DpSize(600.dp, 300.dp), ColumnWidth.Narrow))
    }

    @Test
    fun `a window with no size yet is refused`() {
        assertThrows<IllegalArgumentException> {
            columnMaxWidth(DpSize.Unspecified, ColumnWidth.Wide)
        }
    }
}
