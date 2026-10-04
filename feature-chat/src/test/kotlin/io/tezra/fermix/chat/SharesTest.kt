package io.tezra.fermix.chat

import io.tezra.fermix.session.MAX_ATTACHMENTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The chat's own FileProvider and another provider of the app's package, as the phone would resolve them. */
private const val OWN = "io.tezra.fermix.chat.files"
private const val OWN_STARTUP = "io.tezra.fermix.androidx-startup"
private val OWN_AUTHORITIES = setOf(OWN, OWN_STARTUP)

private const val SEND = "android.intent.action.SEND"
private const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

private fun photo(n: Int) = SharedUri("content://media/external/images/media/$n", "content", "media")

private val NOTE =
    SharedUri("content://com.example.notes.fileprovider/n/1.pdf", "content", "com.example.notes.fileprovider")
private val PLANTED = SharedUri("file:///data/user/0/io.tezra.fermix/no_backup/instances.json", "file", null)
private val OWN_FILE = SharedUri("content://$OWN/shared/0123/report.pdf", "content", OWN)
private val OWN_USER = SharedUri("content://0@$OWN/shared/0123/report.pdf", "content", "0@$OWN")
private val OWN_OTHER = SharedUri("content://$OWN_STARTUP/x", "content", OWN_STARTUP)
private val UPPER = SharedUri("CONTENT://media/external/images/media/9", "CONTENT", "media")
private val WEB = SharedUri("https://example.com/a.png", "https", "example.com")
private val INTENT = SharedUri("intent://scan/#Intent;scheme=fermix;end", "intent", "scan")
private val NO_SCHEME = SharedUri("media/external/images/media/9", null, null)

/** One row of the parse: an intent's action, type, streams and words, and what lands of it, or nothing. */
private data class ShareRow(
    val name: String,
    val action: String?,
    val type: String?,
    val streams: List<SharedUri>,
    val text: String?,
    val lands: Shared?,
)

private fun lands(
    uris: List<SharedUri> = emptyList(),
    words: String? = null,
    refused: List<String> = emptyList(),
    past: Int = 0,
) = Shared(uris.map { it.uri }, words, refused, past)

private val ROWS =
    listOf(
        ShareRow("an image", SEND, "image/jpeg", listOf(photo(1)), null, lands(listOf(photo(1)))),
        ShareRow("a file", SEND, "application/pdf", listOf(NOTE), null, lands(listOf(NOTE))),
        ShareRow("words", SEND, "text/plain", emptyList(), "look at this", lands(words = "look at this")),
        ShareRow(
            "an image and its words",
            SEND,
            "image/*",
            listOf(photo(1)),
            "caption",
            lands(listOf(photo(1)), "caption"),
        ),
        // The type is the sender's word; what an item is, its provider says as it lands.
        ShareRow("words under an image's type", SEND, "image/png", emptyList(), "hi", lands(words = "hi")),
        ShareRow("a text file as a stream", SEND, "text/plain", listOf(NOTE), null, lands(listOf(NOTE))),
        ShareRow("blank words alone", SEND, "text/plain", emptyList(), "  \n ", null),
        ShareRow("nothing at all", SEND, "image/*", emptyList(), null, null),
        ShareRow(
            "two images",
            SEND_MULTIPLE,
            "image/*",
            listOf(photo(1), photo(2)),
            null,
            lands(listOf(photo(1), photo(2))),
        ),
        ShareRow(
            "twelve images, ten land",
            SEND_MULTIPLE,
            "image/*",
            (1..12).map(::photo),
            null,
            lands((1..MAX_ATTACHMENTS).map(::photo), past = 2),
        ),
        // Every URI enters through mayRead as another app's content URI only, refused by scheme and authority alone.
        ShareRow(
            "a file URI",
            SEND,
            "application/json",
            listOf(PLANTED),
            null,
            lands(refused = listOf("file URI of no authority")),
        ),
        ShareRow(
            "the chat's own provider",
            SEND,
            "application/pdf",
            listOf(OWN_FILE),
            null,
            lands(refused = listOf("content URI of $OWN")),
        ),
        ShareRow(
            "the chat's own provider behind a user",
            SEND,
            "application/pdf",
            listOf(OWN_USER),
            null,
            lands(refused = listOf("content URI of 0@$OWN")),
        ),
        ShareRow(
            "another provider of the app's",
            SEND,
            "*/*",
            listOf(OWN_OTHER),
            null,
            lands(refused = listOf("content URI of $OWN_STARTUP")),
        ),
        ShareRow(
            "an upper-case scheme",
            SEND,
            "image/*",
            listOf(UPPER),
            null,
            lands(refused = listOf("CONTENT URI of media")),
        ),
        ShareRow(
            "a web address",
            SEND,
            "image/*",
            listOf(WEB),
            null,
            lands(refused = listOf("https URI of example.com")),
        ),
        ShareRow("an intent", SEND, "*/*", listOf(INTENT), null, lands(refused = listOf("intent URI of scan"))),
        ShareRow(
            "no scheme",
            SEND,
            "*/*",
            listOf(NO_SCHEME),
            null,
            lands(refused = listOf("null URI of no authority")),
        ),
        ShareRow(
            "refused among taken, in order",
            SEND_MULTIPLE,
            "*/*",
            listOf(PLANTED, photo(1), OWN_FILE, NOTE),
            null,
            lands(listOf(photo(1), NOTE), refused = listOf("file URI of no authority", "content URI of $OWN")),
        ),
        // Only a share is taken: any other action is nothing, whatever it carries.
        ShareRow("a view", "android.intent.action.VIEW", "image/*", listOf(photo(1)), "hi", null),
        ShareRow("no action", null, "image/*", listOf(photo(1)), "hi", null),
    )

/**
 * A share as the share entry reads it (design section 13.6, "Share into Fermix"): what another app hands over is
 * weighed by the one check, mayRead, as another app's content URI only, at most ten land however many come, and its
 * words are words.
 */
class SharesTest {
    private fun parsed(row: ShareRow): Shared? =
        sharedOf(ShareInput(row.action, row.type, row.streams, row.text), own = { it in OWN_AUTHORITIES })

    @Test
    fun `each action, type and extra lands or is refused as the table says`() {
        val wrong = ROWS.mapNotNull { row -> parsed(row).takeIf { it != row.lands }?.let { row.name to it } }
        assertEquals(emptyList<Pair<String, Shared?>>(), wrong)
    }

    @Test
    fun `a share's words are bounded as they are read, never cut inside a character`() {
        val emoji = "😀"
        val long = "a" + emoji.repeat(MAX_SHARED_CHARS)
        val words = requireNotNull(parsed(ShareRow("long", SEND, "text/plain", emptyList(), long, null))?.words)
        assertTrue(words.length <= MAX_SHARED_CHARS, "${words.length} characters")
        assertTrue(!words.last().isHighSurrogate(), "cut inside a character")
        assertEquals(long.take(words.length), words)
    }

    @Test
    fun `a share of a thousand streams reads only the ten it lands and counts the rest`() {
        val many = (1..1_000).map(::photo)
        val shared = requireNotNull(parsed(ShareRow("many", SEND_MULTIPLE, "image/*", many, null, null)))
        assertEquals(MAX_ATTACHMENTS, shared.uris.size)
        assertEquals(990, shared.past)
    }

    @Test
    fun `no URI is refused by its path`() {
        val shared = requireNotNull(parsed(ShareRow("planted", SEND, "*/*", listOf(PLANTED, OWN_FILE), null, null)))
        assertTrue(shared.refused.none { "instances" in it || "report" in it }, "${shared.refused}")
        assertNull(shared.words)
    }
}
