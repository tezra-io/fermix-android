package io.tezra.fermix.chat

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.instance.tintColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Where a tap on a link sends it: the Custom Tab at this address, or nowhere ([NOWHERE]). */
private const val NOWHERE = ""

/** A web address of exactly 2,048 UTF-8 bytes, a link preview's bound on the wire, and the longest that opens. */
private val LONGEST = "https://example.com/" + "a".repeat(2_048 - "https://example.com/".length)

/** What the log says of a link of [scheme] that opens nothing. */
private fun refused(scheme: String): String = "A link of scheme $scheme in a message opens nothing"

/** What the log says of a web address that opens nothing for its length alone. */
private const val TOO_LONG = "A link of scheme https in a message opens nothing: it is longer than 2048 bytes"

/**
 * A link's tap, by its scheme (design sections 8.1, 8.3 and 13.5): the address the Custom Tab opens, scheme
 * lower-cased; or [NOWHERE], and the line the log says, which names the scheme alone. The wire's text picks either,
 * so case, blanks, brackets and length are rows of their own.
 */
private val TAPS =
    listOf(
        Triple("https://hexdocs.pm/elixir/Task.html", "https://hexdocs.pm/elixir/Task.html", null),
        Triple("http://example.com/a?b=c", "http://example.com/a?b=c", null),
        Triple("HTTPS://Example.com/Path", "https://Example.com/Path", null),
        Triple("Http://example.com", "http://example.com", null),
        Triple("<https://example.com/D2>", NOWHERE, refused("none")),
        Triple(LONGEST, LONGEST, null),
        Triple(LONGEST + "a", NOWHERE, TOO_LONG),
        Triple("https://example.com/" + "é".repeat(1_020), NOWHERE, TOO_LONG),
        Triple("https://example.com/" + "c".repeat(1_040_000), NOWHERE, TOO_LONG),
        Triple(" https://example.com", NOWHERE, refused("none")),
        Triple("\thttps://example.com", NOWHERE, refused("none")),
        Triple("tel:+15551234", NOWHERE, refused("tel")),
        Triple("TEL:+15551234", NOWHERE, refused("tel")),
        Triple("content://io.tezra.fermix.chat.files/shared/0123456789abcdef/x.pdf", NOWHERE, refused("content")),
        Triple("file:///data/user/0/io.tezra.fermix/no_backup/instances.json", NOWHERE, refused("file")),
        Triple("intent://pair#Intent;scheme=fermix;package=io.tezra.fermix;end", NOWHERE, refused("intent")),
        Triple("fermix://pair?v=2&host=suj-mbp", NOWHERE, refused("fermix")),
        Triple("javascript:alert(1)", NOWHERE, refused("javascript")),
        Triple("mailto:owner@example.com", NOWHERE, refused("mailto")),
        Triple("www.example.com", NOWHERE, refused("none")),
        Triple("https", NOWHERE, refused("none")),
        Triple("", NOWHERE, refused("none")),
        Triple("x".repeat(40) + ":y", NOWHERE, refused("none")),
    )

/**
 * The definitions under [DRAWN]'s rows: a phone number's, a web address's, one whose destination is a label in
 * brackets, as written and escaped, and the address that label names.
 */
private const val DEFINITIONS =
    "[office]: tel:+15551234\n[docs]: https://hexdocs.pm/elixir/Task.html\n[a]: [u]\n[r]: \\[the-foo\\]\n" +
        "[u]: https://evil.example/chain"

/**
 * Rows of a message, the words each draws, and the address its link carries, none for words with no link on them.
 * A destination in angle brackets is an autolink to the parser, and the renderer links no words to it; angle
 * brackets around no address are words. A reference is looked up as the renderer looks it up, and draws as its
 * words when its address opens nothing; a definition whose destination is a label in brackets is none, and its
 * reference draws as written. An autolink among the words of a link that opens nothing is their text.
 */
private val DRAWN =
    listOf(
        Triple("[the guide](https://hexdocs.pm/elixir/Task.html)", "the guide", "https://hexdocs.pm/elixir/Task.html"),
        Triple("[the shout](HTTPS://example.com/Shout)", "the shout", "HTTPS://example.com/Shout"),
        Triple("[the pointy](<https://example.com/D2>)", "the pointy", null),
        Triple("an odd <https://example.com/x\ny> one", "an odd <https://example.com/x y> one", null),
        Triple("a bare https://example.com/b page", "a bare https://example.com/b page", "https://example.com/b"),
        Triple("[the desk](tel:+15551234)", "the desk", null),
        Triple("[the pairing](fermix://pair?v=2&host=suj-mbp)", "the pairing", null),
        Triple("[the long one]($LONGEST" + "a)", "the long one", null),
        Triple("[the records](file:///data/user/0/io.tezra.fermix/no_backup/instances.json)", "the records", null),
        Triple("an angle <tel:+15551234> link", "an angle tel:+15551234 link", null),
        Triple("an email <owner@example.com> link", "an email owner@example.com link", null),
        Triple("a bare www.example.com page", "a bare www.example.com page", null),
        Triple("[the front office][office]", "the front office", null),
        Triple("the [office] itself", "the office itself", null),
        Triple("[the docs][docs]", "the docs", "https://hexdocs.pm/elixir/Task.html"),
        Triple("[the-foo](https://evil.example/x)", "the-foo", "https://evil.example/x"),
        Triple("[the chain][a]", "[the chain][a]", null),
        Triple("[the click][r]", "[the click][r]", null),
        Triple("[see <https://good.example/a>](tcel:+15551234)", "see https://good.example/a", null),
        Triple("[**read <https://good.example/b>**](tel:+15551234)", "read https://good.example/b", null),
    )

/** A link's words the renderer draws marked up: emphasis, a code span, an entity and escapes, a bare address. */
private val MARKED_WORDS = listOf("**the desk**", "`the code`", "AT&amp;T \\*desk\\*", "*it* and https://x.example/y")

/** [TAPPED]'s bare address, which a later link's words are. */
private const val BARE = "https://hexdocs.pm/elixir35"

/**
 * A message whose links a tap hands on (design section 13.5): a bare address, then a link whose words are that
 * address and whose destination is another; words with no link (a destination in angle brackets, angle brackets
 * around no address, references whose definitions are a phone number or a label in brackets, as written and
 * escaped); and a reference to a web address.
 */
private const val TAPPED =
    "first $BARE\n\nthen [$BARE](https://evil.example/35)\n\n" +
        "[the pointy](<https://example.com/D2>) and <https://example.com/x\ny>\n\n" +
        "[the front office][office], [foo](https://evil.example/x), [the click][r] and [the chain][a]\n\n" +
        "[the docs][docs]\n\n[office]: tel:+15551234\n[r]: \\[foo\\]\n[a]: [u]\n[u]: https://evil.example/chain\n" +
        "[docs]: https://hexdocs.pm/elixir/Task.html"

/** The address of the definition [OVERWRITTEN]'s first reference carries. */
private const val DEFINED = "https://def.example/r"

/** The address of the link whose words begin with [OVERWRITTEN]'s label. */
private const val OUTER = "https://outer.example/ov"

/** A reference, then a link whose words begin with the reference's label, then the reference again. */
private const val OVERWRITTEN = "[r] first\n\n[[r] more]($OUTER) then [r]\n\n[r] last\n\n[r]: $DEFINED"

/**
 * A link in a message as the chat opens and draws it (design sections 8.1, 8.3 and 13.5): a web address opens in
 * the Custom Tab in the instance's tint, as a link preview does; any other scheme, and a web address longer than a
 * link preview's, is never handed to the system, the tap logged by its scheme alone; a tap hands on the address its
 * link carries, never another the renderer looked up; and a link that opens nothing, a reference's among them,
 * draws as its words, marked up as the renderer marks a link's words, with no link on them, nor on an autolink
 * among them. JUnit 4, in Roborazzi's activity, on the compact window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MessageLinksTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    /** Every text the screen draws, in the order of its tree, as its layout holds it. */
    private fun drawnTexts(): List<AnnotatedString> =
        rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node ->
                val layout = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                layout.single().layoutInput.text
            }

    /** The links [text] carries, in order. */
    private fun linksIn(text: AnnotatedString): List<LinkAnnotation.Url> =
        text.getLinkAnnotations(0, text.length).map { it.item as LinkAnnotation.Url }

    /**
     * The texts drawn as a definition is written: none, when each of the message's definitions is one, so a
     * reference drawn as written is so for its definition, not for a definition the parser read as words.
     */
    private fun definitionsDrawn(): List<String> = drawnTexts().map { it.text }.filter { "]:" in it }

    @Test
    fun `a web address opens in the tinted Custom Tab, and any other scheme is never handed to the system`() {
        var links: UriHandler? = null
        rule.setContent { FermixTheme { links = rememberMessageLinks(rememberLinkOpener("Slate")) } }
        TAPS.forEach { (link, opens, logged) ->
            ShadowLog.clear()
            rule.runOnIdle { checkNotNull(links).openUri(link) }
            val started: Intent? = shadowOf(rule.activity).nextStartedActivity
            val lines = ShadowLog.getLogsForTag(LINK_TAG).map { it.msg }
            val row = "'${link.take(64)}'"
            if (opens == NOWHERE) {
                assertNull("$row started $started", started)
                assertEquals(row, listOf(logged), lines)
            } else {
                val tab = checkNotNull(started) { "$row started nothing" }
                assertEquals(row, Intent.ACTION_VIEW, tab.action)
                assertEquals(row, opens, tab.dataString)
                val colors = CustomTabsIntent.getColorSchemeParams(tab, CustomTabsIntent.COLOR_SCHEME_LIGHT)
                assertEquals(row, tintColor("Slate").toArgb(), colors.toolbarColor)
                assertEquals(row, emptyList<String>(), lines)
            }
        }
    }

    @Test
    fun `a link that opens nothing draws as its words, and a web address as a link`() {
        val markdown = DRAWN.joinToString("\n\n") { it.first } + "\n\nThe end.\n\n$DEFINITIONS"
        rule.setContent { FermixTheme { Prose(markdown, streaming = false, resets = 0, TextActions({}, {})) } }
        DRAWN.forEach { (written, words, url) ->
            val node = rule.onNode(hasText(words, substring = true), useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val text = layout.single().layoutInput.text
            assertEquals("'$written'", listOfNotNull(url), linksIn(text).map { it.url })
            assertEquals("'$written' draws", words, text.text.trim())
        }
        assertEquals("the definitions draw nothing", emptyList<String>(), definitionsDrawn())
    }

    @Test
    fun `a link that opens nothing marks its words up as the renderer marks a web link's`() {
        val inline = MARKED_WORDS.map { "[$it](tel:+15551234)\n\n[$it](https://example.com/w)" }
        val referenced = MARKED_WORDS.map { "[$it][t]\n\n[$it][w]" }
        val definitions = "[t]: tel:+15551234\n[w]: https://example.com/w"
        val markdown = (inline + referenced + definitions).joinToString("\n\n")
        rule.setContent { FermixTheme { Prose(markdown, streaming = false, resets = 0, TextActions({}, {})) } }
        val texts = drawnTexts()
        assertEquals("a paragraph a link: $texts", MARKED_WORDS.size * 4, texts.size)
        (MARKED_WORDS + MARKED_WORDS).zip(texts.chunked(2)).forEach { (words, drawn) ->
            val (closed, open) = drawn
            assertEquals("'$words' opens nothing, with no link", emptyList<LinkAnnotation.Url>(), linksIn(closed))
            val link = linksIn(open).single()
            assertEquals("'$words' is a web link", "https://example.com/w", link.url)
            assertEquals("'$words' draws the web link's words", open.text, closed.text)
            // A link's own look (accent, underline) is laid out as a span of its own, which no closed link has.
            val marks = open.spanStyles.filterNot { it.item == link.styles?.style }
            assertEquals("'$words' is marked up as the web link's words", marks, closed.spanStyles)
        }
    }

    @Test
    fun `a tap hands on the address its link carries, sealed or streaming`() {
        val handed = mutableListOf<String>()
        val recorder =
            object : UriHandler {
                override fun openUri(uri: String) {
                    handed += uri
                }
            }
        rule.setContent {
            FermixTheme {
                CompositionLocalProvider(LocalUriHandler provides recorder) {
                    Column {
                        Prose(TAPPED, streaming = false, resets = 0, TextActions({}, {}))
                        Prose(TAPPED, streaming = true, resets = 0, TextActions({}, {}))
                    }
                }
            }
        }
        rule.waitForIdle()
        val links = drawnTexts().flatMap(::linksIn)
        val carried = links.map { it.url }
        val docs = "https://hexdocs.pm/elixir/Task.html"
        val half = listOf(BARE, "https://evil.example/35", "https://evil.example/x", docs)
        assertEquals("the links of each half", half + half, carried)
        assertEquals("the definitions draw nothing", emptyList<String>(), definitionsDrawn())
        links.forEach { link ->
            handed.clear()
            rule.runOnIdle { checkNotNull(link.linkInteractionListener).onClick(link) }
            assertEquals("a tap on the link to ${link.url}", listOf(link.url), handed)
        }
        assertTrue("every link drawn opens: $carried", carried.all(::opens))
    }

    /**
     * The renderer keys a link's words to its address as it draws them, a reference's label among them, so a
     * reference drawn after a link whose words begin with its label carries that link's address, not its
     * definition's: accepted, as a link reaches that table only when its address opens, sealed or streaming.
     */
    @Test
    fun `a reference drawn after a link whose words begin with its label carries that link's address`() {
        rule.setContent {
            FermixTheme {
                Column {
                    Prose(OVERWRITTEN, streaming = false, resets = 0, TextActions({}, {}))
                    Prose(OVERWRITTEN, streaming = true, resets = 0, TextActions({}, {}))
                }
            }
        }
        rule.waitForIdle()
        val carried = drawnTexts().flatMap(::linksIn).map { it.url }
        val half = listOf(DEFINED, OUTER, OUTER, OUTER, OUTER)
        assertEquals("the links of each half, the nested reference's among them", half + half, carried)
    }
}
