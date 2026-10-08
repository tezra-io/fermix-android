package io.tezra.fermix

import androidx.compose.ui.graphics.Color
import io.tezra.fermix.design.FermixColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/** The app module's main sources: the unit tests run in the module's directory. */
private val MAIN = File("src/main")
private val RES = File(MAIN, "res")

/** The repository's root, whose modules' sources are read; at most this deep is looked at. */
private val ROOT = File("..")
private const val MAX_DEPTH = 16

/** Directories that hold no source of the repository's own. */
private val NOT_SOURCE = setOf("build", ".gradle", ".git", ".kotlin", ".idea")

/** A call or an import of the platform's wallpaper colours, which the design does not use (section 13.1). */
private val DYNAMIC_COLOR = Regex("""\bdynamic(?:Light|Dark)ColorScheme\b""")

/** The mark as the design module vendors it (`design/mark/SOURCE.json` names where it came from). */
private val MARK_SVG = File(ROOT, "design/mark/fermix-mark.svg")

/** How far a vector's body may lie from the SVG's: its numbers rounded to one decimal, under lint's path limit. */
private const val ROUNDING = 0.05 + 1e-9

/** A path's number: lint's limit counts characters, so a vector drops a leading zero. */
private val NUMBER = Regex("""-?(?:\d+\.?\d*|\.\d+)""")

/** The adaptive icon's 108 dp, and the circle in its middle that every launcher's mask leaves whole. */
private const val ICON_CENTRE = 54.0
private const val SAFE_RADIUS = 33.0

/** A splash library, or a call that would hold the system's splash on screen. */
private val SPLASH_HOLD = Regex("""\binstallSplashScreen\b|\bsetKeepOnScreenCondition\b|\bcore[-.]splashscreen\b""")

private fun parsed(file: File): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    return factory.newDocumentBuilder().parse(file).documentElement
}

private fun Element.children(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** The mark's drawing in the vendored SVG: its body (the outline with the visor cut) and its two eyes. */
private class SvgMark(
    val body: String,
    val eyes: List<Element>,
)

private fun svgMark(): SvgMark {
    val shapes = parsed(MARK_SVG).children().filter { it.tagName != "title" }
    assertEquals(listOf("path", "rect", "rect"), shapes.map { it.tagName })
    assertEquals("evenodd", shapes[0].getAttribute("fill-rule"))
    return SvgMark(body = shapes[0].getAttribute("d"), eyes = shapes.drop(1))
}

/** An SVG `rect` with rounded corners as a vector's path: clockwise from the top edge's start, each corner an arc. */
private fun roundedRect(rect: Element): String {
    val number = { name: String -> rect.getAttribute(name).toBigDecimal() }
    val (x, y, r) = Triple(number("x"), number("y"), number("rx"))
    val (right, bottom) = (x + number("width")) to (y + number("height"))
    return "M${x + r},${y}H${right - r}A$r,$r 0,0 1,$right,${y + r}V${bottom - r}A$r,$r 0,0 1,${right - r},$bottom" +
        "H${x + r}A$r,$r 0,0 1,$x,${bottom - r}V${y + r}A$r,$r 0,0 1,${x + r},${y}Z"
}

private typealias Point = Pair<Double, Double>

private operator fun Point.plus(other: Point): Point = (first + other.first) to (second + other.second)

/**
 * A path of moves, cubic curves and closes, absolute or relative, as its absolute points: one list per subpath, its
 * start and then each cubic's two handles and end.
 */
private fun absolutePoints(data: String): List<List<Point>> {
    val subpaths = mutableListOf<MutableList<Point>>()
    var at: Point = 0.0 to 0.0
    for (command in Regex("""[MmCcZz][^MmCcZz]*""").findAll(data).map { it.value }) {
        val points =
            NUMBER
                .findAll(command)
                .map { it.value.toDouble() }
                .chunked(2) { it[0] to it[1] }
                .toList()
        val relative = command.first().isLowerCase()
        when (command.first().uppercaseChar()) {
            'M' -> subpaths += mutableListOf(if (relative) at + points.single() else points.single())
            'C' -> subpaths.last() += cubics(at, points, relative)
            else -> require(points.isEmpty()) { command }
        }
        // A close returns to the subpath's start; a move or a curve ends where its last point is.
        at = if (command.first() in "Zz") subpaths.last().first() else subpaths.last().last()
    }
    return subpaths
}

/** The cubics' points, three each, from [from]: each relative one counted from where the one before it ended. */
private fun cubics(
    from: Point,
    points: List<Point>,
    relative: Boolean,
): List<Point> {
    require(points.isNotEmpty() && points.size % 3 == 0) { "A cubic has two handles and an end: $points" }
    var at = from
    return points.chunked(3).flatMap { cubic ->
        val absolute = if (relative) cubic.map { at + it } else cubic
        at = absolute.last()
        absolute
    }
}

/** A `#RRGGBB` or `#AARRGGBB` colour as Compose holds it. */
private fun colorOf(hex: String): Color {
    val digits = hex.trimStart('#')
    require(digits.length == 6 || digits.length == 8) { hex }
    return Color(java.lang.Long.parseLong(if (digits.length == 6) "FF$digits" else digits, 16))
}

/** The colour [reference], `@color/name`, holds in the values directory [values]. */
private fun colorValue(
    values: String,
    reference: String,
): Color {
    val name = reference.removePrefix("@color/")
    val colors =
        File(RES, values).listFiles().orEmpty().filter { it.extension == "xml" }.flatMap { file ->
            parsed(file).children().filter { it.tagName == "color" && it.getAttribute("name") == name }
        }
    return colorOf(colors.single().textContent.trim())
}

/** Theme.Fermix's item [name] in the values directory [values]. */
private fun themeItem(
    values: String,
    name: String,
): String {
    val style = parsed(File(RES, "$values/themes.xml")).children().single { it.getAttribute("name") == "Theme.Fermix" }
    return style
        .children()
        .single { it.getAttribute("name") == name }
        .textContent
        .trim()
}

/** A vector drawing the mark: its one group's placement, and its paths' data, fill types and colours. */
private class VectorMark(
    val group: Element,
    val paths: List<Element>,
)

private fun vectorMark(drawable: String): VectorMark {
    val vector = parsed(File(RES, "drawable/$drawable.xml"))
    assertEquals(
        listOf("108", "108"),
        listOf("viewportWidth", "viewportHeight").map { vector.getAttributeNS(ANDROID, it) },
    )
    val group = vector.children().single()
    assertEquals("group", group.tagName, drawable)
    return VectorMark(group, group.children())
}

/** Where the group's scale and translation (about its default pivot, the origin) put the mark's point ([x], [y]). */
private fun VectorMark.place(
    x: Double,
    y: Double,
): Pair<Double, Double> {
    val value = { name: String -> group.getAttributeNS(ANDROID, name) }
    assertEquals("", value("pivotX") + value("pivotY"))
    val (scaleX, scaleY) = value("scaleX").toDouble() to value("scaleY").toDouble()
    return (x * scaleX + value("translateX").toDouble()) to (y * scaleY + value("translateY").toDouble())
}

/**
 * Asserts that [drawable] draws the vendored mark, body then eyes: its body the SVG's outline and visor, point for
 * point to one decimal, the visor cut out; its eyes the SVG's rounded rectangles exactly; every path in [fill]; and
 * every point inside the safe zone: the body's every control point (its curves lie in their control points' hull) and
 * every eye's corners.
 */
private fun assertDrawsTheMark(
    drawable: String,
    fill: (String) -> Unit,
) {
    val svg = svgMark()
    val mark = vectorMark(drawable)
    val data = mark.paths.map { it.getAttributeNS(ANDROID, "pathData") }
    assertEquals(svg.eyes.map(::roundedRect), data.drop(1), drawable)
    val (body, want) = absolutePoints(data[0]) to absolutePoints(svg.body)
    // The outline, then the visor: two subpaths of the SVG's 96 and its visor's cubics, three points each.
    assertEquals(want.map { it.size }, body.map { it.size }, drawable)
    assertEquals(listOf(1 + 3 * 96), want.take(1).map { it.size })
    val pairs = body.flatten().zip(want.flatten())
    val apart = pairs.maxOf { (a, b) -> maxOf(abs(a.first - b.first), abs(a.second - b.second)) }
    assertTrue(apart <= ROUNDING, "$drawable lies $apart units from the SVG")
    assertEquals("evenOdd", mark.paths[0].getAttributeNS(ANDROID, "fillType"), drawable)
    for (path in mark.paths) fill(path.getAttributeNS(ANDROID, "fillColor"))
    val corners =
        svg.eyes.flatMap { eye ->
            val number = { name: String -> eye.getAttribute(name).toDouble() }
            val (x, y) = number("x") to number("y")
            val (right, bottom) = (x + number("width")) to (y + number("height"))
            listOf(x to y, right to y, x to bottom, right to bottom)
        }
    val points = body.flatten() + corners
    val placed = points.map { (x, y) -> mark.place(x, y) }
    val farthest = placed.maxOf { (px, py) -> Math.hypot(px - ICON_CENTRE, py - ICON_CENTRE) }
    assertTrue(farthest < SAFE_RADIUS, "$drawable reaches $farthest dp from the centre")
}

/** Whether [reference], `@type/name`, names a resource the module declares: a drawable's file or a colour's value. */
private fun declared(reference: String): Boolean {
    val (type, name) = reference.removePrefix("@").split('/', limit = 2)
    return when (type) {
        "drawable", "mipmap" -> {
            val folders = RES.listFiles().orEmpty().filter { it.name.startsWith(type) }
            folders.any { File(it, "$name.xml").isFile }
        }

        "color" -> {
            RES.listFiles().orEmpty().filter { it.name.startsWith("values") }.any { values ->
                colorIn(values, name)
            }
        }

        else -> {
            false
        }
    }
}

private fun colorIn(
    values: File,
    name: String,
): Boolean =
    values.listFiles().orEmpty().filter { it.extension == "xml" }.any { file ->
        parsed(file).children().any { it.tagName == "color" && it.getAttribute("name") == name }
    }

/** Every Kotlin source of the repository's modules, never what a build made. */
private fun kotlinSources(): List<File> =
    ROOT
        .walkTopDown()
        .maxDepth(MAX_DEPTH)
        .onEnter { it.name !in NOT_SOURCE }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

/**
 * The app's icon (design section 13.1, as the M51 update's 3.6 changes it): the launcher's adaptive icon and its round
 * one, each with its background, its foreground and the themed icon's monochrome layer, each layer a resource the app
 * declares; the foreground the vendored Fermix mark in the ink on white, inside the safe zone every mask leaves, and
 * the monochrome layer that same drawing, whose alpha alone the themed icon reads; the system's splash the mark on the
 * canvas in each mode, with no library and nothing holding it; the notification's small icon still the two-dot mark in
 * one colour; and the design's own colours, never the wallpaper's.
 */
class LauncherIconTest {
    @Test
    fun `the manifest names the adaptive icon and its round one`() {
        val application =
            parsed(
                File(MAIN, "AndroidManifest.xml"),
            ).getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID, "icon"))
        assertEquals("@mipmap/ic_launcher_round", application.getAttributeNS(ANDROID, "roundIcon"))
    }

    @Test
    fun `each launcher icon is adaptive, with a background, a foreground and a monochrome layer the app declares`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = parsed(File(RES, "mipmap-anydpi/$name.xml"))
            assertEquals("adaptive-icon", icon.tagName, name)
            val layers = icon.children()
            assertEquals(listOf("background", "foreground", "monochrome"), layers.map { it.tagName }, name)
            val missing = layers.map { it.getAttributeNS(ANDROID, "drawable") }.filterNot(::declared)
            assertEquals(emptyList<String>(), missing, name)
        }
    }

    @Test
    fun `the foreground is the vendored mark in the light ink on the white background, inside the safe zone`() {
        assertEquals(FermixColors.Light.canvas, colorValue("values", "@color/ic_launcher_background"))
        assertDrawsTheMark("ic_launcher_foreground") { assertEquals(FermixColors.Light.ink, colorOf(it)) }
    }

    @Test
    fun `the monochrome layer is the foreground's mark, every shape whole, as the themed icon reads its alpha alone`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val layer = parsed(File(RES, "mipmap-anydpi/$name.xml")).children().single { it.tagName == "monochrome" }
            assertEquals("@drawable/ic_launcher_foreground", layer.getAttributeNS(ANDROID, "drawable"), name)
        }
        val mark = vectorMark("ic_launcher_foreground")
        assertEquals(List(mark.paths.size) { "" }, mark.paths.map { it.getAttributeNS(ANDROID, "fillAlpha") })
    }

    @Test
    fun `the splash is the mark in the ink on the canvas, in each mode, placed as the launcher's`() {
        for ((values, colors) in listOf("values" to FermixColors.Light, "values-night" to FermixColors.Dark)) {
            assertEquals(colors.canvas, colorValue(values, themeItem(values, "android:windowSplashScreenBackground")))
            assertEquals("@drawable/splash_mark", themeItem(values, "android:windowSplashScreenAnimatedIcon"))
            assertDrawsTheMark("splash_mark") { assertEquals(colors.ink, colorValue(values, it)) }
        }
        assertEquals(vectorMark("ic_launcher_foreground").place(0.0, 0.0), vectorMark("splash_mark").place(0.0, 0.0))
    }

    @Test
    fun `nothing holds the splash, and no splash library is built in`() {
        val builds =
            ROOT
                .walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { it.name !in NOT_SOURCE }
                .filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml")) }
                .toList()
        assertTrue(builds.any { it.name == "libs.versions.toml" }, "${builds.size}")
        // The scan would see a hold or the library: this file spells both so that it does not match itself.
        assertTrue(SPLASH_HOLD.containsMatchIn("splashScreen.setKeep" + "OnScreenCondition { true }"))
        assertTrue(SPLASH_HOLD.containsMatchIn("androidx.core:core-" + "splashscreen:1.2.0"))
        val holding = (kotlinSources() + builds).filter { SPLASH_HOLD.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), holding)
    }

    @Test
    fun `the notification's small icon is still the two-dot mark in white, and every notification posts with it`() {
        val small = parsed(File(RES, "drawable/ic_notification.xml")).children()
        val ink = { paths: List<Element> -> paths.map { it.getAttributeNS(ANDROID, "fillAlpha") to it.tagName } }
        assertEquals(listOf("" to "path", "0.62" to "path"), ink(small))
        assertTrue(
            small.all {
                it
                    .getAttributeNS(ANDROID, "fillColor")
                    .uppercase()
                    .trimStart('#')
                    .endsWith("FFFFFF")
            },
        )
        val calls =
            kotlinSources().flatMap { file ->
                Regex("""setSmallIcon\(([^)]*)\)""").findAll(file.readText()).map { it.groupValues[1] }
            }
        // Three posts: a chat's or an approval's notification, the pairing wait's and the upload's.
        assertEquals(List(3) { "R.drawable.ic_notification" }, calls)
    }

    @Test
    fun `no source asks for the wallpaper's colours`() {
        val sources = kotlinSources()
        // The scan reaches every module: the design's theme and this test among the sources it reads.
        assertTrue(
            sources.any {
                it.path.endsWith("design/src/main/kotlin/io/tezra/fermix/design/FermixTheme.kt")
            },
            "${sources.size}",
        )
        assertTrue(sources.any { it.name == "LauncherIconTest.kt" })
        assertTrue(DYNAMIC_COLOR.containsMatchIn("val scheme = dynamic" + "DarkColorScheme(context)"))
        val calling = sources.filter { DYNAMIC_COLOR.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), calling)
    }
}
