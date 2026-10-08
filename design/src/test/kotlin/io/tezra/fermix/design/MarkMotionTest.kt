package io.tezra.fermix.design

import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** One key as a table writes it: its time, its value and the name of the easing after it ("" after the last). */
private data class Row(
    val ms: Int,
    val value: Float,
    val easing: String,
)

// The update's 3.2 and section 5 (MILESTONE_51_ANDROID_MONOCHROME_AND_WELCOME_MOTION.md), row by row, in its words.
private val UPDATE_DROP =
    mapOf(
        "dropBottom" to listOf(Row(0, -6f, "fall"), Row(380, 96f, "")),
        "dropRx" to listOf(Row(0, 5f, "hold"), Row(330, 5f, "in-out"), Row(400, 8.6f, "in-out"), Row(470, 6f, "")),
        "dropRy" to listOf(Row(0, 7.5f, "hold"), Row(330, 7.5f, "in-out"), Row(400, 4f, "in-out"), Row(470, 6f, "")),
        "morph" to listOf(Row(0, 0f, "hold"), Row(470, 0f, "emphasized decelerate"), Row(900, 1f, "")),
        "bodyScale" to
            listOf(
                Row(0, 1f, "hold"),
                Row(700, 1f, "in-out"),
                Row(860, 1.04f, "in-out"),
                Row(980, 0.99f, "in-out"),
                Row(1_060, 1f, ""),
            ),
        "visorOpen" to
            listOf(
                Row(0, 0f, "hold"),
                Row(820, 0f, "emphasized decelerate"),
                Row(1_000, 1.08f, "in-out"),
                Row(1_080, 1f, ""),
            ),
        "eyeLeft" to listOf(Row(0, 0f, "hold"), Row(940, 0f, "back"), Row(1_140, 1f, "")),
        "eyeRight" to listOf(Row(0, 0f, "hold"), Row(1_000, 0f, "back"), Row(1_200, 1f, "")),
        "blink" to listOf(Row(0, 1f, "hold"), Row(1_320, 1f, "in-out"), Row(1_380, 0.1f, "in-out"), Row(1_460, 1f, "")),
    )

private val UPDATE_HOP =
    mapOf(
        "happy" to listOf(Row(0, 0f, "in-out"), Row(180, 1f, "")),
        "hopY" to
            listOf(
                Row(0, 0f, "in-out"),
                Row(90, 1.2f, "standard"),
                Row(250, -10f, "fall"),
                Row(400, 0f, "hold"),
                Row(500, 0f, ""),
            ),
        "hopScaleY" to
            listOf(
                Row(0, 1f, "in-out"),
                Row(90, 0.9f, "standard"),
                Row(200, 1.06f, "in-out"),
                Row(400, 0.92f, "in-out"),
                Row(520, 1f, ""),
            ),
        "hopScaleX" to
            listOf(
                Row(0, 1f, "in-out"),
                Row(90, 1.07f, "standard"),
                Row(200, 0.96f, "in-out"),
                Row(400, 1.06f, "in-out"),
                Row(520, 1f, ""),
            ),
    )

// The reference player's tables (MILESTONE_51_ANDROID_MONOCHROME_AND_WELCOME_MOTION.html), copied by hand as its script
// writes them; a key with no easing is the last of its row. DROP is lines 130 to 140, HOP lines 141 to 146 (its hopSy
// and hopSx are the update's hopScaleY and hopScaleX).
private val PLAYER_DROP =
    mapOf(
        // line 131: dropBottom: [[0, -6, "fall"], [380, 96]],
        "dropBottom" to listOf(Row(0, -6f, "fall"), Row(380, 96f, "")),
        // line 132: dropRx: [[0, 5, "hold"], [330, 5, "io"], [400, 8.6, "io"], [470, 6]],
        "dropRx" to listOf(Row(0, 5f, "hold"), Row(330, 5f, "io"), Row(400, 8.6f, "io"), Row(470, 6f, "")),
        // line 133: dropRy: [[0, 7.5, "hold"], [330, 7.5, "io"], [400, 4, "io"], [470, 6]],
        "dropRy" to listOf(Row(0, 7.5f, "hold"), Row(330, 7.5f, "io"), Row(400, 4f, "io"), Row(470, 6f, "")),
        // line 134: morph: [[0, 0, "hold"], [470, 0, "emph"], [900, 1]],
        "morph" to listOf(Row(0, 0f, "hold"), Row(470, 0f, "emph"), Row(900, 1f, "")),
        // line 135: bodyScale: [[0, 1, "hold"], [700, 1, "io"], [860, 1.04, "io"], [980, 0.99, "io"], [1060, 1]],
        "bodyScale" to
            listOf(
                Row(0, 1f, "hold"),
                Row(700, 1f, "io"),
                Row(860, 1.04f, "io"),
                Row(980, 0.99f, "io"),
                Row(1_060, 1f, ""),
            ),
        // line 136: visorOpen: [[0, 0, "hold"], [820, 0, "emph"], [1000, 1.08, "io"], [1080, 1]],
        "visorOpen" to listOf(Row(0, 0f, "hold"), Row(820, 0f, "emph"), Row(1_000, 1.08f, "io"), Row(1_080, 1f, "")),
        // line 137: eyeLeft: [[0, 0, "hold"], [940, 0, "back"], [1140, 1]],
        "eyeLeft" to listOf(Row(0, 0f, "hold"), Row(940, 0f, "back"), Row(1_140, 1f, "")),
        // line 138: eyeRight: [[0, 0, "hold"], [1000, 0, "back"], [1200, 1]],
        "eyeRight" to listOf(Row(0, 0f, "hold"), Row(1_000, 0f, "back"), Row(1_200, 1f, "")),
        // line 139: blink: [[0, 1, "hold"], [1320, 1, "io"], [1380, 0.1, "io"], [1460, 1]],
        "blink" to listOf(Row(0, 1f, "hold"), Row(1_320, 1f, "io"), Row(1_380, 0.1f, "io"), Row(1_460, 1f, "")),
    )

private val PLAYER_HOP =
    mapOf(
        // line 142: happy: [[0, 0, "io"], [180, 1]],
        "happy" to listOf(Row(0, 0f, "io"), Row(180, 1f, "")),
        // line 143: hopY: [[0, 0, "io"], [90, 1.2, "std"], [250, -10, "fall"], [400, 0, "hold"], [500, 0]],
        "hopY" to
            listOf(
                Row(0, 0f, "io"),
                Row(90, 1.2f, "std"),
                Row(250, -10f, "fall"),
                Row(400, 0f, "hold"),
                Row(500, 0f, ""),
            ),
        // line 144: hopSy: [[0, 1, "io"], [90, 0.9, "std"], [200, 1.06, "io"], [400, 0.92, "io"], [520, 1]],
        "hopScaleY" to
            listOf(
                Row(0, 1f, "io"),
                Row(90, 0.9f, "std"),
                Row(200, 1.06f, "io"),
                Row(400, 0.92f, "io"),
                Row(520, 1f, ""),
            ),
        // line 145: hopSx: [[0, 1, "io"], [90, 1.07, "std"], [200, 0.96, "io"], [400, 1.06, "io"], [520, 1]],
        "hopScaleX" to
            listOf(
                Row(0, 1f, "io"),
                Row(90, 1.07f, "std"),
                Row(200, 0.96f, "io"),
                Row(400, 1.06f, "io"),
                Row(520, 1f, ""),
            ),
    )

/** The easings by the names the update (3.3) and the player (its `E`, line 114) give them. */
private val EASINGS =
    mapOf(
        "emphasized decelerate" to MarkEasing.EmphasizedDecelerate,
        "emph" to MarkEasing.EmphasizedDecelerate,
        "standard" to MarkEasing.Standard,
        "std" to MarkEasing.Standard,
        "in-out" to MarkEasing.InOut,
        "io" to MarkEasing.InOut,
        "fall" to MarkEasing.Fall,
        "back" to MarkEasing.Back,
        "hold" to MarkEasing.Hold,
    )

private val DROP_TABLES =
    mapOf(
        "dropBottom" to Drop.dropBottom,
        "dropRx" to Drop.dropRx,
        "dropRy" to Drop.dropRy,
        "morph" to Drop.morph,
        "bodyScale" to Drop.bodyScale,
        "visorOpen" to Drop.visorOpen,
        "eyeLeft" to Drop.eyeLeft,
        "eyeRight" to Drop.eyeRight,
        "blink" to Drop.blink,
    )

private val HOP_TABLES =
    mapOf(
        "happy" to Hop.happy,
        "hopY" to Hop.hopY,
        "hopScaleY" to Hop.hopScaleY,
        "hopScaleX" to Hop.hopScaleX,
    )

/** The pose's field each table drives, by the table's name. */
private val DROP_FIELDS: Map<String, (MarkPose) -> Float> =
    mapOf(
        "dropBottom" to MarkPose::dropBottom,
        "dropRx" to MarkPose::dropRx,
        "dropRy" to MarkPose::dropRy,
        "morph" to MarkPose::morph,
        "bodyScale" to MarkPose::bodyScale,
        "visorOpen" to MarkPose::visorOpen,
        "eyeLeft" to MarkPose::eyeLeft,
        "eyeRight" to MarkPose::eyeRight,
        "blink" to MarkPose::blink,
    )

private val HOP_FIELDS: Map<String, (MarkPose) -> Float> =
    mapOf(
        "happy" to MarkPose::happy,
        "hopY" to MarkPose::hopY,
        "hopScaleY" to MarkPose::hopScaleY,
        "hopScaleX" to MarkPose::hopScaleX,
    )

/** The idle's fields, which neither moment drives. */
private val IDLE_FIELDS: Map<String, (MarkPose) -> Float> =
    mapOf(
        "breathX" to MarkPose::breathX,
        "breathY" to MarkPose::breathY,
        "idleBlink" to MarkPose::idleBlink,
    )

private const val CLOSE = 1e-3f

/**
 * The mark's motion as data (the M51 update's 3.2, 3.3, section 5 and section 6): the easings, the one sampler, and
 * the drop's and the hop's tables, held key by key to the update and to its reference player, the owner's two
 * renderings of one table, so that a table drifting from either fails.
 */
class MarkMotionTest {
    @Test
    fun `the three curves are the update's, as the reference player computes them`() {
        // The player's own cubic-bezier (its line 110), run at 0.1, 0.25, 0.5, 0.75 and 0.9.
        val at = listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)
        assertCurve(MarkEasing.EmphasizedDecelerate, at, listOf(0.6214f, 0.8315f, 0.9502f, 0.9905f, 0.9987f))
        assertCurve(MarkEasing.Standard, at, listOf(0.1562f, 0.6072f, 0.8778f, 0.9755f, 0.9965f))
        assertCurve(MarkEasing.InOut, at, listOf(0.0197f, 0.1292f, 0.5f, 0.8708f, 0.9803f))
    }

    @Test
    fun `fall is gravity's t squared, and hold stays at the segment's start`() {
        for (t in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(t * t, MarkEasing.Fall.transform(t))
            assertEquals(0f, MarkEasing.Hold.transform(t))
        }
    }

    @Test
    fun `back starts at 0, overshoots about 10 percent and settles at 1`() {
        assertEquals(0f, MarkEasing.Back.transform(0f), CLOSE)
        assertEquals(1f, MarkEasing.Back.transform(1f), CLOSE)
        val peak = (0..1_000).maxOf { MarkEasing.Back.transform(it / 1_000f) }
        // 1 + 2.9u³ + 1.9u² peaks at u = -3.8 / 8.7: 12 % over, the update's "about 10 %".
        assertEquals(1.1208f, peak, CLOSE)
        // It never dips below its start, but for a float's rounding at 0.
        assertTrue((0..1_000).all { MarkEasing.Back.transform(it / 1_000f) >= -CLOSE })
    }

    @Test
    fun `before the first key the sampler gives the first value`() {
        val keys = listOf(MarkKey(100, 3f), MarkKey(200, 9f))
        assertEquals(3f, sample(keys, -50f))
        assertEquals(3f, sample(keys, 100f))
    }

    @Test
    fun `the easing of a key shapes the segment that starts at it`() {
        val keys = listOf(MarkKey(0, 0f, MarkEasing.Fall), MarkKey(100, 10f, MarkEasing.Hold), MarkKey(200, 20f))
        // Fall over the first segment, a quarter of its distance halfway; hold over the second, its start value.
        assertEquals(2.5f, sample(keys, 50f), CLOSE)
        assertEquals(10f, sample(keys, 100f), CLOSE)
        assertEquals(10f, sample(keys, 199f), CLOSE)
    }

    @Test
    fun `from the last key on the sampler gives the last value`() {
        val keys = listOf(MarkKey(0, 0f, MarkEasing.Fall), MarkKey(100, 10f))
        assertEquals(10f, sample(keys, 100f))
        assertEquals(10f, sample(keys, 10_000f))
        assertEquals(4f, sample(listOf(MarkKey(0, 4f)), 50f))
    }

    @Test
    fun `a table with no key, or keys out of time order, is refused`() {
        assertThrows<IllegalArgumentException> { sample(emptyList(), 0f) }
        assertThrows<IllegalArgumentException> { sample(listOf(MarkKey(100, 0f), MarkKey(100, 1f)), 150f) }
    }

    @Test
    fun `the drop's tables are the update's 3_2, key by key`() = assertTables(UPDATE_DROP, DROP_TABLES)

    @Test
    fun `the drop's tables are the reference player's DROP, key by key`() = assertTables(PLAYER_DROP, DROP_TABLES)

    @Test
    fun `the hop's tables are the update's section 5, key by key`() = assertTables(UPDATE_HOP, HOP_TABLES)

    @Test
    fun `the hop's tables are the reference player's HOP, key by key`() = assertTables(PLAYER_HOP, HOP_TABLES)

    @Test
    fun `the drop draws each of its tables in its own field, and leaves the hop's and the idle's at rest`() =
        assertDrives(::dropAt, DROP_TABLES, DROP_FIELDS, HOP_FIELDS + IDLE_FIELDS)

    @Test
    fun `the hop draws each of its tables in its own field, and leaves the drop's and the idle's at rest`() =
        assertDrives(::hopAt, HOP_TABLES, HOP_FIELDS, DROP_FIELDS + IDLE_FIELDS)

    @Test
    fun `the drop starts as a stretched dot above the box and ends at rest`() {
        val start = dropAt(0f)
        assertEquals(-6f, start.dropBottom)
        assertEquals(5f, start.dropRx)
        assertEquals(7.5f, start.dropRy)
        assertEquals(0f, start.morph)
        assertEquals(0f, start.visorOpen)
        assertEquals(0f, start.eyeLeft)
        assertEquals(0f, start.eyeRight)
        // Every row's last key is at or before 1,460 ms, where the mark settles; the clock runs on to 1,800.
        assertEquals(MarkPose.Rest, dropAt(1_460f))
        assertEquals(MarkPose.Rest, dropAt(Drop.CLOCK_MILLIS.toFloat()))
    }

    @Test
    fun `the hop leaves the rest, tops out 10 units up at 250 ms, and lands with the happy eyes`() {
        assertEquals(MarkPose.Rest, hopAt(0f))
        assertEquals(-10f, hopAt(250f).hopY)
        assertTrue((0..520).all { hopAt(it.toFloat()).hopY >= -10f })
        assertEquals(MarkPose.Rest.copy(happy = 1f), hopAt(520f))
        assertEquals(MarkPose.Rest.copy(happy = 1f), hopAt(Hop.CLOCK_MILLIS.toFloat()))
    }

    @Test
    fun `the rest is the player's REST`() {
        // line 147: dropBottom: 96, dropRx: 6, dropRy: 6, morph: 1, bodyScale: 1, visorOpen: 1, eyeLeft: 1,
        // eyeRight: 1, blink: 1, happy: 0, hopY: 0, hopSy: 1, hopSx: 1 (and the eyes' moves, Task 19's).
        val rest = MarkPose.Rest
        val values =
            listOf(
                rest.dropBottom,
                rest.dropRx,
                rest.dropRy,
                rest.morph,
                rest.bodyScale,
                rest.visorOpen,
                rest.eyeLeft,
                rest.eyeRight,
                rest.blink,
                rest.happy,
                rest.hopY,
                rest.hopScaleY,
                rest.hopScaleX,
            )
        assertEquals(listOf(96f, 6f, 6f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f), values)
        assertEquals(listOf(1f, 1f, 1f), listOf(rest.breathX, rest.breathY, rest.idleBlink))
    }

    @Test
    fun `the words rise in over 420 ms on emphasized decelerate, the last of Welcome's at 1,740 ms`() {
        val starts = Drop.words.map { it.toFloat() }
        assertEquals(listOf(1_100f, 1_210f, 1_320f), starts)
        for (start in starts) {
            assertEquals(0f, riseIn(start - 1f, start.toInt()))
            assertEquals(0f, riseIn(start, start.toInt()))
            assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), riseIn(start + 210f, start.toInt()), CLOSE)
            assertEquals(1f, riseIn(start + RISE_MILLIS, start.toInt()))
        }
        assertEquals(1f, riseIn(1_740f, Drop.words.last()))
        assertTrue(riseIn(1_700f, Drop.words.last()) < 1f)
        assertEquals(10.dp, Drop.wordsRise)
        assertEquals(250, Hop.TITLE_MILLIS)
        assertEquals(8.dp, Hop.titleRise)
    }

    @Test
    fun `the moments' clocks run past what lands last, and their haptics fall inside them`() {
        assertEquals(1_800, Drop.CLOCK_MILLIS)
        assertEquals(380, Drop.LANDS_MILLIS)
        assertEquals(90, Hop.LEAVES_GROUND_MILLIS)
        // Each haptic's time is its table's: the fall's last key, and the crouch's, from which the hop rises.
        assertEquals(Drop.dropBottom.last().ms, Drop.LANDS_MILLIS)
        assertEquals(Hop.hopY[1].ms, Hop.LEAVES_GROUND_MILLIS)
        assertEquals(Hop.hopScaleY[1].ms, Hop.LEAVES_GROUND_MILLIS)
        assertTrue(Hop.CLOCK_MILLIS >= Hop.TITLE_MILLIS + RISE_MILLIS)
        assertTrue(Drop.CLOCK_MILLIS >= Drop.words.last() + RISE_MILLIS)
    }

    @Test
    fun `only a frame of the landing's squash draws the landing, never one after it`() {
        assertFalse(Drop.drawsLanding(379.9f))
        assertTrue(Drop.drawsLanding(380f))
        assertTrue(Drop.drawsLanding(469.9f))
        // The squash settles at 470 ms, the last key of dropRx and dropRy.
        assertEquals(470, Drop.dropRy.last().ms)
        assertFalse(Drop.drawsLanding(470f))
        // The clock standing at its end, or the first frame back after time out of sight.
        assertFalse(Drop.drawsLanding(Drop.CLOCK_MILLIS.toFloat()))
    }

    private fun assertCurve(
        easing: Easing,
        at: List<Float>,
        expected: List<Float>,
    ) {
        for ((t, value) in at.zip(expected)) assertEquals(value, easing.transform(t), CLOSE, "at $t")
    }

    private fun assertTables(
        expected: Map<String, List<Row>>,
        tables: Map<String, List<MarkKey>>,
    ) {
        assertEquals(expected.keys, tables.keys)
        for ((name, rows) in expected) {
            val keys = tables.getValue(name)
            assertEquals(rows.map { it.ms to it.value }, keys.map { it.ms to it.value }, name)
            // The last key's easing shapes no segment, so neither source names one.
            val segments = rows.zip(keys).dropLast(1)
            for ((row, key) in segments) assertSame(EASINGS.getValue(row.easing), key.easing, "$name ${row.ms}")
        }
    }

    /**
     * [poseAt] holds each of [fields] to its table in [tables] at every key's time and every segment's middle, where
     * no two tables agree throughout, and leaves each of [others] at rest.
     */
    private fun assertDrives(
        poseAt: (Float) -> MarkPose,
        tables: Map<String, List<MarkKey>>,
        fields: Map<String, (MarkPose) -> Float>,
        others: Map<String, (MarkPose) -> Float>,
    ) {
        assertEquals(tables.keys, fields.keys)
        for (ms in instants(tables.values)) {
            val pose = poseAt(ms)
            for ((name, field) in fields) assertEquals(sample(tables.getValue(name), ms), field(pose), "$name at $ms")
            for ((name, field) in others) assertEquals(field(MarkPose.Rest), field(pose), "$name at $ms")
        }
    }

    /** Every key's time in [tables] and the middle of every segment, in order. */
    private fun instants(tables: Collection<List<MarkKey>>): List<Float> {
        val keys = tables.flatMap { table -> table.map { it.ms.toFloat() } }
        val middles = tables.flatMap { table -> table.zipWithNext { from, to -> (from.ms + to.ms) / 2f } }
        return (keys + middles).distinct().sorted()
    }
}
