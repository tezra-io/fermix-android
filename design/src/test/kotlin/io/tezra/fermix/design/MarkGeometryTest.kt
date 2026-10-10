package io.tezra.fermix.design

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

// The mark's geometry is the design's (the M51 update's 2.1), vendored in design/mark beside SOURCE.json, which
// names the design-docs commit and each file's digest. The Kotlin in MarkGeometry is held to the vendored JSON here,
// value for value; nothing reads the JSON at run time. Unit tests run in the module's directory.
class MarkGeometryTest {
    private val source = Json.parseToJsonElement(File("mark/SOURCE.json").readText()).jsonObject
    private val geometry = Json.parseToJsonElement(File("mark/fermix-mark-geometry.json").readText()).jsonObject

    @Test
    fun `the vendored files are the design-docs commit's, each with its recorded digest`() {
        val upstream = source.getValue("upstream").jsonObject
        assertEquals("tezra-io/fermix-design-docs", upstream.getValue("repository").jsonPrimitive.content)
        assertTrue(Regex("[0-9a-f]{40}").matches(upstream.getValue("commit").jsonPrimitive.content))
        val files = source.getValue("files").jsonArray.map { it.jsonObject }
        for (file in files) {
            val path = file.getValue("path").jsonPrimitive.content
            assertEquals(file.getValue("sha256").jsonPrimitive.content, sha256(File(path)), path)
        }
        val recorded = files.map { it.getValue("path").jsonPrimitive.content } + "mark/SOURCE.json"
        val present = File("mark").listFiles().orEmpty().map { "mark/${it.name}" }
        assertEquals(recorded.toSet(), present.toSet())
    }

    @Test
    fun `each vendored file is the test classpath's file of its name, so a changed byte runs these tests again`() {
        // The directory is a test resource, copied to the classpath's root, and the classpath is the test task's
        // input. A second resource directory with a file of the same name may stand in its place in silence.
        val files = File("mark").listFiles().orEmpty()
        for (file in files) {
            val copy = checkNotNull(javaClass.getResource("/${file.name}")) { "no ${file.name} on the classpath" }
            assertArrayEquals(file.readBytes(), copy.readBytes(), "the classpath's ${file.name} is not mark's")
        }
        assertEquals(VENDORED_FILES, files.size)
    }

    @Test
    fun `the outline and the visor are the JSON's path strings`() {
        assertEquals(geometry.text("outline"), MarkGeometry.OUTLINE)
        assertEquals(geometry.text("visor"), MarkGeometry.VISOR)
    }

    @Test
    fun `the vector is the outline with the visor cut out, and the two eyes`() {
        val svg = File("mark/fermix-mark.svg").readText()
        val path = Regex("""<path fill="#0B0B0D" fill-rule="evenodd" d="([^"]+)"/>""").find(svg)
        assertEquals(MarkGeometry.OUTLINE + MarkGeometry.VISOR, path?.groupValues?.get(1))
        val rects =
            Regex(
                """<rect fill="#0B0B0D" x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" rx="([\d.]+)"/>""",
            ).findAll(svg)
                .map { match -> match.groupValues.drop(1).map(String::toFloat) }
                .toList()
        val eyes =
            listOf(MarkGeometry.leftEye, MarkGeometry.rightEye).map { eye ->
                listOf(
                    eye.centre.x - eye.width / 2f,
                    eye.centre.y - eye.height / 2f,
                    eye.width,
                    eye.height,
                    MarkGeometry.EYE_CORNER,
                )
            }
        assertEquals(eyes.size, rects.size)
        for ((rect, eye) in rects.zip(eyes)) {
            rect.zip(eye).forEach { (svgValue, value) -> assertEquals(svgValue, value, ROUNDING) }
        }
    }

    @Test
    fun `both eyes are the JSON's rounded rectangles`() {
        val eyes = geometry.getValue("eyes").jsonArray.map { it.jsonObject }
        for ((json, eye) in eyes.zip(listOf(MarkGeometry.leftEye, MarkGeometry.rightEye))) {
            assertEquals(Offset(json.number("cx"), json.number("cy")), eye.centre)
            assertEquals(json.number("width"), eye.width)
            assertEquals(json.number("height"), eye.height)
            assertEquals(json.number("cornerRadius"), MarkGeometry.EYE_CORNER)
        }
        assertEquals(2, eyes.size)
    }

    @Test
    fun `the visor centre, the feet and the morph centre are the JSON's`() {
        assertEquals(geometry.point("visorCenter"), MarkGeometry.visorCentre)
        assertEquals(geometry.point("feet"), MarkGeometry.feet)
        assertEquals(geometry.point("morphCenter"), MarkGeometry.morphCentre)
    }

    @Test
    fun `the happy arcs are the JSON's stroke and curve`() {
        val stroke = units(MarkGeometry.HAPPY_STROKE)
        val half = units(MarkGeometry.HAPPY_HALF_WIDTH)
        val foot = units(MarkGeometry.HAPPY_FOOT)
        val rise = units(MarkGeometry.HAPPY_RISE)
        val words =
            "Per eye, a stroke of $stroke units with round caps: " +
                "M(cx-$half, cy+$foot) Q(cx, cy-$rise) (cx+$half, cy+$foot)"
        assertEquals(geometry.text("happyArc"), words)
    }

    @Test
    fun `the 96 outline points are the JSON's, in its order`() {
        val points =
            geometry
                .getValue("outlinePoints96")
                .jsonObject
                .getValue("points")
                .jsonArray
                .map { point ->
                    (point as JsonArray).let { Offset(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
                }
        assertEquals(OUTLINE_POINTS, points.size)
        assertEquals(points, MarkGeometry.outlinePoints)
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String): Float = getValue(name).jsonPrimitive.float

    private fun JsonObject.point(name: String): Offset =
        getValue(name).jsonArray.let { Offset(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }

    /** A length in mark units as the JSON writes it: a whole number without its point. */
    private fun units(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun sha256(file: File): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

    private companion object {
        const val OUTLINE_POINTS = 96

        /** The SVG, the geometry JSON and SOURCE.json. */
        const val VENDORED_FILES = 3

        /** The vector writes the eyes' corners to two decimals. */
        const val ROUNDING = 0.005f
    }
}
