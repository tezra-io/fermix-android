package io.tezra.fermix

import io.tezra.fermix.chats.SHARE_CATEGORY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/** What a share may carry (design section 13.6): an image, a video, words, or any file. */
private val SHARED_TYPES = listOf("image/*", "video/*", "text/plain", "*/*")

private fun parsed(path: String): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    return factory.newDocumentBuilder().parse(File(path)).documentElement
}

private fun Element.all(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

/**
 * The share entry as the app's manifest and shortcuts declare it (design section 13.6): an exported activity of its
 * own, with no window, kept out of Recents and out of the app's task, whose filters take SEND and SEND_MULTIPLE of the
 * four types and nothing else; the conversation shortcuts' share target with the category they carry; and the one
 * activity, singleTop as before the share came, with the launcher's filter alone.
 */
class ShareEntryTest {
    private val manifest = parsed("src/main/AndroidManifest.xml")

    private fun activity(name: String): Element = manifest.all("activity").single { it.android("name") == name }

    @Test
    fun `the share entry is an exported activity with no window, taking a share of the four types only`() {
        assertEquals(emptyList<Element>(), manifest.all("activity-alias"))
        val entry = activity(".ShareTarget")
        assertEquals(SHARE_ENTRY, "io.tezra.fermix" + entry.android("name"))
        assertEquals(SHARE_ENTRY, ShareTarget::class.java.name)
        assertEquals("true", entry.android("exported"))
        assertEquals("@android:style/Theme.NoDisplay", entry.android("theme"))
        assertEquals("true", entry.android("excludeFromRecents"))
        assertEquals("", entry.android("taskAffinity"))
        assertTrue(entry.hasAttributeNS(ANDROID, "taskAffinity"))
        val filter = entry.all("intent-filter").single()
        val actions = filter.all("action").map { it.android("name") }
        assertEquals(listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"), actions)
        assertEquals(listOf("android.intent.category.DEFAULT"), filter.all("category").map { it.android("name") })
        val data = filter.all("data")
        assertEquals(SHARED_TYPES, data.map { it.android("mimeType") })
        assertEquals(
            listOf("mimeType"),
            data
                .flatMap { d ->
                    (0 until d.attributes.length).map { d.attributes.item(it).localName }
                }.distinct(),
        )
    }

    @Test
    fun `the activity is singleTop, so the launcher clears nothing over it, and its one filter is the launcher's`() {
        assertEquals(listOf(".MainActivity", ".ShareTarget"), manifest.all("activity").map { it.android("name") })
        val activity = activity(".MainActivity")
        assertEquals("singleTop", activity.android("launchMode"))
        val filters = activity.all("intent-filter")
        assertEquals(
            listOf("android.intent.action.MAIN"),
            filters.flatMap { it.all("action") }.map { it.android("name") },
        )
    }

    @Test
    fun `the conversation shortcuts' share target is the entry, in the category they carry`() {
        val target = parsed("src/main/res/xml/shortcuts.xml").all("share-target").single()
        assertEquals(SHARE_ENTRY, target.android("targetClass"))
        assertEquals(SHARED_TYPES, target.all("data").map { it.android("mimeType") })
        assertEquals(listOf(SHARE_CATEGORY), target.all("category").map { it.android("name") })
    }
}
