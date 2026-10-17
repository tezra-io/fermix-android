package io.tezra.fermix.chat

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceLocation
import io.tezra.fermix.protocol.ReplyBlock
import io.tezra.fermix.session.TimelineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A link preview of [url], as the daemon's unfurl writes one. */
private fun preview(url: String) = LinkPreviewCard(url, "Example", "A page")

/** The places block of [PLACES], as a reply carries it. */
private val BLOCKS = listOf(ReplyBlock.Places(PLACES))

/** A row of [role]'s with [PLACES] and [previews] on it. */
private fun rowWith(
    role: String,
    previews: List<LinkPreviewCard>,
) = TimelineRow.Message(
    HistoryMessage(7uL, role, "Try these", at(1), emptyList(), linkPreviews = previews, blocks = BLOCKS),
)

/**
 * The addresses a place card reads (M72 sections 8.4 and 14): a link preview whose address names a place is left
 * out, under the engine's own comparison, scheme and host in any case, a fragment or a trailing slash or the
 * default port written or not, and one that names none is kept, at most two in all; the `geo:` address the Maps
 * app opens, built from the record's coordinates and name, never from a link; the Google Maps page that stands in
 * when no app answers it; and the page "Open website" opens.
 */
class PlaceAddressesTest {
    @Test
    fun `a preview whose address names a place is left out, however the address is written`() {
        val named =
            listOf(
                "https://example.coffee/",
                "https://example.coffee",
                "HTTPS://Example.COFFEE/",
                "https://example.coffee/#menu",
                "https://example.coffee:443/",
                "https://provider.example/place/1/",
                "https://provider.example/place/1#reviews",
                "https://quietcorner.example",
            )
        named.forEach { url ->
            assertEquals(url, emptyList<LinkPreviewCard>(), shownPreviews(listOf(preview(url)), PLACES))
        }
    }

    @Test
    fun `a preview whose address names no place is kept`() {
        val other =
            listOf(
                "https://example.coffee/menu",
                "https://example.coffee/?visit=1",
                "http://example.coffee/",
                "https://example.coffee:8443/",
                "https://provider.example/Place/1",
                "https://owner@example.coffee/",
                "https://exa mple.coffee/",
                "https://hexdocs.pm/elixir/Task.html",
            )
        other.forEach { url ->
            assertEquals(url, listOf(preview(url)), shownPreviews(listOf(preview(url)), PLACES))
        }
    }

    @Test
    fun `a page's key is the engine's reading of it, a host with an underscore and an unchecked escape among them`() {
        val same =
            listOf(
                "https://my_cafe.example/" to "HTTPS://My_Cafe.EXAMPLE:443",
                "https://example.coffee/%zz" to "https://EXAMPLE.coffee:0443/%zz/#top",
                "https://[::1]/menu" to "https://[::1]:443/menu/",
                "https://user:pw@example.coffee/" to "https://user:pw@Example.coffee:443",
                "https:///menu" to "https://:443/menu/",
            )
        same.forEach { (one, other) ->
            assertNotNull(one, addressKey(one))
            assertEquals(one, addressKey(one), addressKey(other))
        }
        val apart =
            listOf(
                "https://example.coffee/" to "https://example.coffee/?",
                "https://example.coffee/" to "https://@example.coffee/",
                "https://example.coffee/~menu" to "https://example.coffee/%7Emenu",
                "https://example.coffee/" to "https://example.coffee//",
            )
        apart.forEach { (one, other) -> assertNotEquals(one, addressKey(one), addressKey(other)) }
        val underscored = listOf(QUIET_CORNER.copy(canonicalUrl = "https://quiet_corner.example/"))
        val shown = shownPreviews(listOf(preview("HTTPS://Quiet_Corner.example")), underscored)
        assertEquals(emptyList<LinkPreviewCard>(), shown)
    }

    @Test
    fun `an address the engine's reader refuses has no key, so it names no place`() {
        val refused =
            listOf(
                "https://exa mple.coffee/",
                "https://café.example/",
                "https://example.coffee/a|b",
                "https://example.coffee/menu[1]",
                "https://example.coffee/?day=[1]",
                "https://example.coffee/#a#b",
                "https://a@b@example.coffee/",
                "https://example.coffee:port/",
                "https://example.coffee:443:1/",
                "https://exa:mple.coffee/",
                "https://[zz]/",
                "1https://example.coffee/",
                "https:example.coffee",
                "//example.coffee/",
                "mailto:owner@example.coffee",
                "https://example.coffee/\n",
            )
        refused.forEach { url -> assertNull(url, addressKey(url)) }
    }

    @Test
    fun `the previews shown are the first two left once the places' are out, web addresses alone`() {
        val previews =
            listOf(
                preview("https://example.coffee/"),
                preview("ftp://files.example/notes.txt"),
                preview("https://hexdocs.pm/elixir/Task.html"),
                preview("https://provider.example/place/3"),
                preview("https://elixir-lang.org/"),
                preview("https://example.org/post"),
            )
        assertEquals(previews.slice(listOf(2, 4)), shownPreviews(previews, PLACES))
        assertEquals(previews.slice(listOf(0, 2)), shownPreviews(previews, emptyList()))
    }

    @Test
    fun `an agent's message carries its places, and its previews leave out the pages they name`() {
        val previews = listOf(preview("https://example.coffee"), preview("https://hexdocs.pm/elixir/Task.html"))
        val reply = TimelineRow.Reply(5uL, "turn-1", "Try these", false, null, linkPreviews = previews, blocks = BLOCKS)
        assertEquals(PLACES to previews.drop(1), replyOf(reply, null).let { it.places to it.previews })
        val row = messageOf(rowWith("assistant", previews), null)
        assertEquals(PLACES to previews.drop(1), row.places to row.previews)
        val bare = replyOf(TimelineRow.Reply(6uL, "turn-2", "Hi", false, null, linkPreviews = previews), null)
        assertEquals(emptyList<Place>() to previews, bare.places to bare.previews)
    }

    @Test
    fun `the owner's message carries no places, whatever its row holds`() {
        val previews = listOf(preview("https://example.coffee"))
        val owner = messageOf(rowWith("user", previews), null)
        assertEquals(emptyList<Place>() to previews, owner.places to owner.previews)
    }

    @Test
    fun `the geo address names the place's coordinates and its name, encoded`() {
        assertEquals("geo:40.7233,-74.003?q=40.7233,-74.003(Example%20Coffee)", geoAddress(EXAMPLE_COFFEE))
        assertEquals(
            "geo:40.7262,-74.0008?q=40.7262,-74.0008(Paper%20Lantern%20Books%20%28Caf%C3%A9%29)",
            geoAddress(PAPER_LANTERN),
        )
        val marks = EXAMPLE_COFFEE.copy(name = "A+B & Co's #1?", location = PlaceLocation(0.00001, -180.0))
        assertEquals("geo:0.000010,-180.0?q=0.000010,-180.0(A%2BB%20%26%20Co%27s%20%231%3F)", geoAddress(marks))
    }

    @Test
    fun `a name of any length is cut before it goes in the address, never between a character's halves`() {
        val long = EXAMPLE_COFFEE.copy(name = "a".repeat(MAX_PLACE_LABEL_CHARS - 1) + "😀" + "b".repeat(500))
        val label = geoAddress(long).substringAfter('(').removeSuffix(")")
        assertEquals("a".repeat(MAX_PLACE_LABEL_CHARS - 1), label)
    }

    @Test
    fun `the web page that stands in for the Maps app searches the place's coordinates`() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=40.7233,-74.003", mapsAddress(EXAMPLE_COFFEE))
        val south = EXAMPLE_COFFEE.copy(location = PlaceLocation(-33.8688, 151.2093))
        assertEquals("https://www.google.com/maps/search/?api=1&query=-33.8688,151.2093", mapsAddress(south))
    }

    @Test
    fun `the website is the place's own page, else its source's, and none that opens nothing`() {
        assertEquals("https://example.coffee/", websiteOf(EXAMPLE_COFFEE))
        assertEquals("https://provider.example/place/3", websiteOf(PAPER_LANTERN))
        val ftp = EXAMPLE_COFFEE.copy(canonicalUrl = "ftp://example.coffee/")
        assertEquals("https://provider.example/place/1", websiteOf(ftp))
        assertNull(websiteOf(PAPER_LANTERN.copy(sourceUrl = "tel:+15551234")))
    }
}
