package io.tezra.fermix.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import javax.xml.parsers.DocumentBuilderFactory

// The Fermix wordmark is fermix-macos's file (the owner, 2026-10-10), vendored in design/wordmark beside SOURCE.json,
// which names the commit and the file's digest. WordmarkGeometry is held to the vendored SVG here, path for path, and
// the file to its digest; nothing reads it at run time. Unit tests run in the module's directory.
class WordmarkGeometryTest {
    private val source = Json.parseToJsonElement(File("wordmark/SOURCE.json").readText()).jsonObject
    private val svg =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("wordmark/fermix-wordmark.svg"))
            .documentElement

    @Test
    fun `the vendored file is fermix-macos's at the pinned commit, with its recorded digest, and alone`() {
        val upstream = source.getValue("upstream").jsonObject
        assertEquals("tezra-io/fermix-macos", upstream.getValue("repository").jsonPrimitive.content)
        assertTrue(Regex("[0-9a-f]{40}").matches(upstream.getValue("commit").jsonPrimitive.content))
        val files = source.getValue("files").jsonArray.map { it.jsonObject }
        for (file in files) {
            val path = file.getValue("path").jsonPrimitive.content
            assertEquals(file.getValue("sha256").jsonPrimitive.content, sha256(File(path)), path)
        }
        val recorded = files.map { it.getValue("path").jsonPrimitive.content } + "wordmark/SOURCE.json"
        val present = File("wordmark").listFiles().orEmpty().map { "wordmark/${it.name}" }
        assertEquals(recorded.toSet(), present.toSet())
        assertEquals(listOf("wordmark/fermix-wordmark.svg"), recorded.dropLast(1))
    }

    @Test
    fun `the box is the file's viewBox, the letters' 384 by 100 with the margin round them`() {
        val box = svg.getAttribute("viewBox").split(" ").map(String::toFloat)
        val port = with(WordmarkGeometry) { listOf(LEFT, TOP, WIDTH, HEIGHT) }
        assertEquals(box, port)
    }

    @Test
    fun `the letters are the file's paths, group by group at its translations, filled even-odd in currentColor`() {
        val (letters, _) = elements(svg, "g")
        assertEquals("currentColor", letters.getAttribute("fill"))
        assertEquals("evenodd", letters.getAttribute("fill-rule"))
        val groups =
            elements(letters, "g").map { group ->
                WordmarkGroup(translation(group), elements(group, "path").map { it.getAttribute("d") })
            }
        assertEquals(groups, WordmarkGeometry.letters)
        // F, E, R, M, I and X: fourteen paths.
        assertEquals(LETTER_PATHS, WordmarkGeometry.letters.sumOf { it.paths.size })
    }

    @Test
    fun `the two eye-dots are the file's circles at its translation, in the signal of both modes`() {
        val (_, dots) = elements(svg, "g")
        assertEquals(translation(dots), WordmarkGeometry.dotsAt)
        val circles = elements(dots, "circle")
        assertEquals(circles.map { Offset(it.number("cx"), it.number("cy")) }, WordmarkGeometry.dots)
        for (circle in circles) {
            assertEquals(circle.number("r"), WordmarkGeometry.DOT_RADIUS)
            assertEquals(colour(circle.getAttribute("fill")), FermixColors.Light.signal)
            assertEquals(colour(circle.getAttribute("fill")), FermixColors.Dark.signal)
        }
        assertEquals(2, circles.size)
    }

    @Test
    fun `the file draws the letters' group and the dots' group and nothing else`() {
        assertEquals(2, elements(svg, "g").size)
    }

    @Test
    fun `each element carries only the attributes the port reads, so nothing in the file goes undrawn`() {
        assertAttributes(svg, "role", "aria-label", "viewBox", "fill", "xmlns")
        // The root fills nothing itself, and its label is the description the port gives TalkBack.
        assertEquals("none", svg.getAttribute("fill"))
        assertEquals("Fermix", svg.getAttribute("aria-label"))
        val (letters, dots) = elements(svg, "g")
        assertAttributes(letters, "fill", "fill-rule")
        for (group in elements(letters, "g")) {
            assertAttributes(group, "transform")
            for (path in elements(group, "path")) {
                assertAttributes(path, "d")
                assertEmpty(path)
            }
        }
        assertAttributes(dots, "transform")
        for (circle in elements(dots, "circle")) {
            assertAttributes(circle, "cx", "cy", "r", "fill")
            assertEmpty(circle)
        }
    }

    /** [element] holds no element: an `<animate>` or a `<set>` in a path or a dot changes what it draws. */
    private fun assertEmpty(element: Element) {
        val nodes = element.childNodes
        val children = (0 until nodes.length).map(nodes::item).filterIsInstance<Element>().map { it.tagName }
        assertEquals(emptyList<String>(), children, "<${element.tagName}>'s children")
    }

    /** [element] carries exactly [names]: another (a stroke, an opacity, a transform) is one the port lacks. */
    private fun assertAttributes(
        element: Element,
        vararg names: String,
    ) {
        val attributes = element.attributes
        val carried = (0 until attributes.length).map { attributes.item(it).nodeName }
        assertEquals(names.toSet(), carried.toSet(), "<${element.tagName}>'s attributes")
    }

    /** [parent]'s child elements, each a [name]: an element of another kind fails, as the port has no such part. */
    private fun elements(
        parent: Element,
        name: String,
    ): List<Element> {
        val nodes = parent.childNodes
        val children = (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
        for (child in children) assertEquals(name, child.tagName, "a <${child.tagName}> under <${parent.tagName}>")
        return children
    }

    /** [element]'s `translate(x y)`, the only transform the file writes. */
    private fun translation(element: Element): Offset {
        val transform = element.getAttribute("transform")
        val match = Regex("""translate\((-?[\d.]+) (-?[\d.]+)\)""").matchEntire(transform)
        val (x, y) = checkNotNull(match) { "not a translation: $transform" }.destructured
        return Offset(x.toFloat(), y.toFloat())
    }

    private fun Element.number(name: String): Float = getAttribute(name).toFloat()

    /** An SVG fill written `#rrggbb`, opaque. */
    private fun colour(fill: String): Color {
        val match = Regex("#([0-9a-f]{6})").matchEntire(fill)
        return Color(OPAQUE or checkNotNull(match) { "not a #rrggbb fill: $fill" }.groupValues[1].toLong(HEX))
    }

    private fun sha256(file: File): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

    private companion object {
        const val LETTER_PATHS = 14
        const val OPAQUE = 0xFF000000
        const val HEX = 16
    }
}
