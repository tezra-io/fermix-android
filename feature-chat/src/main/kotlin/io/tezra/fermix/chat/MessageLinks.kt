package io.tezra.fermix.chat

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.utils.mapAutoLinkToType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.findChildOfType

/** The most UTF-8 bytes a message's link opens with: a link preview's address's bound on the wire. */
private const val MAX_LINK_BYTES = 2_048

/** RFC 3986's scheme: a letter, then letters, digits, "+", "-" and ".". */
private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

/** The longest scheme a log line names; a longer one is named as none. */
private const val MAX_SCHEME_CHARS = 32

/** Whether [link] fits in [MAX_LINK_BYTES] of UTF-8, its length looked at first, as a message's text runs to a MiB. */
private fun bounded(link: String): Boolean =
    link.length <= MAX_LINK_BYTES && link.encodeToByteArray().size <= MAX_LINK_BYTES

/**
 * Whether a message's [link], as the renderer's annotation holds it, opens: a web address (isWebLink) no longer than
 * a link preview's. The one rule a tap (MessageLinks) and the drawing of a link's words (closedLinkWords,
 * closedReferenceWords, closedAutolink) both follow.
 */
internal fun opens(link: String): Boolean = isWebLink(link) && bounded(link)

/**
 * [link]'s scheme, lower-cased, as a log line names it: "none" when what comes before its first ':' is no scheme
 * (RFC 3986), a blank before it among them, or is longer than [MAX_SCHEME_CHARS]. The rest of a link is the wire's,
 * and is never logged.
 */
internal fun schemeOf(link: String): String {
    val scheme = link.substringBefore(':', "")
    val named = scheme.length <= MAX_SCHEME_CHARS && SCHEME.matches(scheme)
    return if (named) scheme.lowercase() else "none"
}

/**
 * What a tap on a link in a message does (design sections 8.1, 8.3 and 13.5): a link that opens (opens), a web
 * address no longer than a link preview's, goes to [open], the Custom Tab a link preview opens in; any other link
 * is never handed to the system, a `tel:`, an `intent:`, a `file:`, the app's own `content:`, a pairing link or a
 * web address of a MiB among them, and [log] hears its scheme alone.
 */
internal class MessageLinks(
    private val open: (String) -> Unit,
    private val log: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        val refused = "A link of scheme ${schemeOf(uri)} in a message opens nothing"
        when {
            opens(uri) -> open(uri)
            isWebLink(uri) -> log("$refused: it is longer than $MAX_LINK_BYTES bytes")
            else -> log(refused)
        }
    }
}

/** The chat's [MessageLinks] over [open], a link preview's opener, a refusal logged under [LINK_TAG]. */
@Composable
internal fun rememberMessageLinks(open: (String) -> Unit): UriHandler =
    remember(open) { MessageLinks(open) { line -> Log.w(LINK_TAG, line) } }

/**
 * An inline link's words, the nodes between its brackets as the renderer draws a link's (appendMarkdownLink), when
 * the renderer would link them to an address that opens nothing; none when the address opens. A destination in
 * angle brackets is no destination to the parser, which reads it as an autolink, and the renderer links no words
 * to it.
 */
internal fun closedLinkWords(
    link: ASTNode,
    content: String,
): List<ASTNode>? {
    val destination = link.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
    val words = link.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.children
    val linked = destination != null && opens(destination)
    return if (linked || words == null) null else words.drop(1).dropLast(1).mapAutoLinkToType()
}

/**
 * A reference link's words, the nodes between the brackets the renderer draws as its words
 * (appendMarkdownReference: a full reference's text, a short one's label), when the address [links] holds for its
 * label, looked up as the renderer looks it up as it draws the link, opens nothing. None when that address opens
 * or the label has no definition, which the renderer draws as written.
 */
internal fun closedReferenceWords(
    link: ASTNode,
    content: String,
    links: DefinedLinks,
): List<ASTNode>? {
    val label = link.findChildOfType(MarkdownElementTypes.LINK_LABEL) ?: return null
    val full = link.type == MarkdownElementTypes.FULL_REFERENCE_LINK
    val text = if (full) link.findChildOfType(MarkdownElementTypes.LINK_TEXT) else label
    val address = links.find(label.getUnescapedTextInNode(content))
    val words = text?.children
    val linked = address.isEmpty() || opens(address)
    return if (linked || words == null) null else words.drop(1).dropLast(1).mapAutoLinkToType()
}

/** The node the renderer takes an autolink's address from (appendAutoLink): its inner address, or the node itself. */
private fun addressOf(link: ASTNode): ASTNode =
    link.children.firstOrNull { it.type.name == MarkdownElementTypes.AUTOLINK.name } ?: link

/**
 * An autolink's words as text that is no autolink draws them: the address the renderer links it to as text, or,
 * for angle brackets around no address, the node's own words, brackets included.
 */
internal fun autolinkWords(link: ASTNode): List<ASTNode> {
    val address = addressOf(link)
    // The inner address is a token, which mapAutoLinkToType leaves as it is and the renderer draws nothing of.
    val bare = address === link && link.children.isNotEmpty()
    val text = LeafASTNode(MarkdownTokenTypes.TEXT, address.startOffset, address.endOffset)
    return if (bare) link.children.mapAutoLinkToType() else listOf(text)
}

/**
 * An autolink's words (autolinkWords) when the address the renderer links it to opens nothing, an email's among
 * them; none when the address opens.
 */
internal fun closedAutolink(
    link: ASTNode,
    content: String,
): List<ASTNode>? = if (opens(addressOf(link).getUnescapedTextInNode(content))) null else autolinkWords(link)

/**
 * The renderer's table of reference links, keyed by a label as written, in its brackets, in any case: a
 * definition's, and, as the renderer keys them, an inline link's words in their brackets as it parses and the
 * first of a link's words as it draws them, when they are a label in brackets, so a reference drawn after
 * `[[r] more](https://…)` carries that link's address, one that opens, as a link that opens nothing never
 * reaches the renderer. The renderer also keys each autolink to its address, which is no label and kept nowhere.
 * A destination that is a label in brackets is no definition: so no address a link carries is one, and the
 * renderer's look-up of a tapped link's own address (find), which once turned a bare address into the
 * destination of a later link whose words were that address, hands on the address itself.
 */
internal class DefinedLinks : ReferenceLinkHandler {
    private val labels = mutableMapOf<String, String?>()

    override fun store(
        label: String,
        destination: String?,
    ) {
        if (bracketed(label) && destination?.let(::bracketed) != true) labels[label.lowercase()] = destination
    }

    override fun find(label: String): String = if (bracketed(label)) labels[label.lowercase()].orEmpty() else ""

    private fun bracketed(label: String): Boolean = label.startsWith('[') && label.endsWith(']')
}
