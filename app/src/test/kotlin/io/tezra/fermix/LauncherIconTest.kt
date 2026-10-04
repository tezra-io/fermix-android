package io.tezra.fermix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

private fun parsed(file: File): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    return factory.newDocumentBuilder().parse(file).documentElement
}

private fun Element.children(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** Whether [reference], `@type/name`, names a resource the module declares: a drawable's file or a colour's value. */
private fun declared(reference: String): Boolean {
    val (type, name) = reference.removePrefix("@").split('/', limit = 2)
    return when (type) {
        "drawable", "mipmap" -> {
            RES.listFiles().orEmpty().any {
                it.name.startsWith(
                    type,
                ) && File(it, "$name.xml").isFile
            }
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
 * The app's icon (design section 13.1): the launcher's adaptive icon and its round one, each with its background,
 * its foreground and the themed icon's monochrome layer, each layer a resource the app declares; the notification's
 * small icon the same two-dot mark in one colour; and the design's own colours, never the wallpaper's.
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
    fun `the notification's small icon is the monochrome mark, and every notification posts with it`() {
        val mark = parsed(File(RES, "drawable/ic_launcher_monochrome.xml")).children()
        val small = parsed(File(RES, "drawable/ic_notification.xml")).children()
        val ink = { paths: List<Element> -> paths.map { it.getAttributeNS(ANDROID, "fillAlpha") to it.tagName } }
        assertEquals(listOf("" to "path", "0.62" to "path"), ink(mark))
        assertEquals(ink(mark), ink(small))
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
