package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The design's own place (M72 section 8.3), every field the schema's `place` defines present. */
private const val PLACE =
    """{"name":"Example Coffee","canonical_url":"https://example.coffee/",""" +
        """"source_url":"https://provider.example/place/1","location":{"latitude":40.7233,"longitude":-74.003},""" +
        """"address":"12 Example St, New York","rating":{"value":4.6,"best":5,"count":321},"price_range":"$$",""" +
        """"categories":["coffee_shop"],"opening_hours":{"current_day":"Thursday 07:00-19:00"},""" +
        """"distance":{"value":0.4,"units":"km"}}"""

/** A place with what the schema requires and one web address, nothing else. */
private const val BARE =
    """{"name":"Corner Tea","source_url":"https://provider.example/place/2",""" +
        """"location":{"latitude":-33.8688,"longitude":151.2093}}"""

private val EXAMPLE_COFFEE =
    Place(
        name = "Example Coffee",
        canonicalUrl = "https://example.coffee/",
        sourceUrl = "https://provider.example/place/1",
        location = PlaceLocation(40.7233, -74.003),
        address = "12 Example St, New York",
        rating = PlaceRating(value = 4.6, best = 5.0, count = 321.0),
        priceRange = "$$",
        categories = listOf("coffee_shop"),
        openingHours = OpeningHours("Thursday 07:00-19:00"),
        distance = PlaceDistance(0.4, "km"),
    )

private val CORNER_TEA =
    Place(
        name = "Corner Tea",
        sourceUrl = "https://provider.example/place/2",
        location = PlaceLocation(-33.8688, 151.2093),
    )

private fun places(vararg place: String) = """{"type":"places","places":[${place.joinToString(",")}]}"""

/**
 * A reply's blocks (the companion PROTOCOL.md "Blocks under a reply", which the mobile PROTOCOL.md's `blocks[]`
 * names): on `row`, `text_done` and a history page's message, the key only when the reply has one; a `places`
 * block is read into its places, and a block of a type this app does not know is skipped, never refused, so the
 * row stands; and the schema's rules on what a block holds, one of each type, one to ten places, each with a web
 * address and its coordinates in range. `blocks` is protocol 2's.
 */
class ReplyBlocksTest {
    private fun row(
        blocks: String?,
        v: Int = 2,
    ) = """{"v":$v,"t":"row","seq":5,"profile_id":"main","server_seq":13,"role":"assistant","text":"Try these",""" +
        """"ts":"2026-10-16T09:00:00Z","media_refs":[]${blocks?.let { ",\"blocks\":$it" }.orEmpty()}}"""

    private fun textDone(blocks: String?) =
        """{"v":2,"t":"text_done","seq":12,"turn_id":"turn-1","server_seq":14,"text":"Try these"""" +
            """${blocks?.let { ",\"blocks\":$it" }.orEmpty()}}"""

    private fun page(blocks: String?) =
        """{"v":2,"t":"history_page","seq":26,"profile_id":"main","messages":[{"server_seq":13,""" +
            """"role":"assistant","content":"Try these","ts":"2026-10-16T09:00:00Z","media_refs":[]""" +
            """${blocks?.let { ",\"blocks\":$it" }.orEmpty()}}],"next_after_seq":13,"history_head_seq":13}"""

    private fun rowOf(header: String) = decodeServerEvent(frameOf(header)).event as ServerEvent.Row

    private fun refused(header: String): ProtocolException.InvalidField =
        assertThrows<ProtocolException.InvalidField>(header) { decodeServerEvent(frameOf(header)) }

    @Test
    fun `a row, a text_done and a history page's message read a places block into its places, in order`() {
        val expected = listOf(ReplyBlock.Places(listOf(EXAMPLE_COFFEE, CORNER_TEA)))
        val blocks = "[${places(PLACE, BARE)}]"

        assertEquals(expected, rowOf(row(blocks)).blocks)
        val done = decodeServerEvent(frameOf(textDone(blocks))).event as ServerEvent.TextDone
        assertEquals(expected, done.blocks)
        val history = decodeServerEvent(frameOf(page(blocks))).event as ServerEvent.HistoryPage
        assertEquals(expected, history.messages.single().blocks)
    }

    @Test
    fun `a reply without the key has no blocks`() {
        assertNull(rowOf(row(null)).blocks)
        assertNull((decodeServerEvent(frameOf(textDone(null))).event as ServerEvent.TextDone).blocks)
        assertNull((decodeServerEvent(frameOf(page(null))).event as ServerEvent.HistoryPage).messages.single().blocks)
    }

    @Test
    fun `a block of a type this app does not know is skipped, not refused, whatever it holds, and the row stands`() {
        val event = """{"type":"event","when":7,"places":"not a list"}"""
        val untyped = """{"type":3,"places":[]}"""
        val blocks = rowOf(row("[$event,${places(BARE)},$untyped,$event]")).blocks.orEmpty()

        assertEquals(4, blocks.size)
        val skipped = blocks.filterIsInstance<ReplyBlock.Skipped>()
        assertEquals(listOf("event", null, "event"), skipped.map { it.type })
        assertEquals(ReplyBlock.Places(listOf(CORNER_TEA)), blocks[1])
        assertEquals("Try these", rowOf(row("[$event]")).text)
    }

    @Test
    fun `a skipped block encodes back as it came, and a places block with its type first`() {
        val skipped = row("""[{"type":"event","title":"Standup"}]""")
        assertEquals(skipped, headerOf(encodeServerEvent(2, 5uL, rowOf(skipped))))
        val bare = row("[${places(BARE)}]")
        assertEquals(bare, headerOf(encodeServerEvent(2, 5uL, rowOf(bare))))
    }

    @Test
    fun `a reply holds one block of each type, and at least one when it has the key`() {
        assertEquals("blocks", refused(row("[${places(PLACE)},${places(BARE)}]")).field)
        assertEquals("blocks", refused(textDone("[${places(PLACE)},${places(BARE)}]")).field)
        assertEquals("messages[0].blocks", refused(page("[${places(PLACE)},${places(BARE)}]")).field)
        assertEquals("blocks", refused(row("[]")).field)
    }

    @Test
    fun `a places block holds one to ten places`() {
        rowOf(row("[${places(*Array(10) { BARE })}]"))
        assertEquals("blocks[0].places", refused(row("[${places(*Array(11) { BARE })}]")).field)
        assertEquals("blocks[0].places", refused(row("[${places()}]")).field)
    }

    @Test
    fun `a place names a web address, and its coordinates are on the globe`() {
        val noAddress = """{"name":"Nowhere","location":{"latitude":1,"longitude":2},"address":"1 Main St"}"""
        assertEquals("blocks[0].places[0]", refused(row("[${places(noAddress)}]")).field)
        val north = BARE.replace("-33.8688", "91")
        assertEquals("blocks[0].places[0].location.latitude", refused(row("[${places(north)}]")).field)
        val west = BARE.replace("151.2093", "-180.5")
        assertEquals("blocks[0].places[1].location.longitude", refused(row("[${places(BARE, west)}]")).field)
        val page = page("[${places(BARE, north)}]")
        assertEquals("messages[0].blocks[0].places[1].location.latitude", refused(page).field)
    }

    @Test
    fun `a place's numbers are numbers, and a field the source gave is never empty`() {
        val counted = PLACE.replace("\"count\":321", "\"count\":\"321\"")
        assertEquals("blocks[0].places[0].rating.count", refused(row("[${places(counted)}]")).field)
        val endless = PLACE.replace("\"value\":0.4", "\"value\":1e400")
        assertEquals("blocks[0].places[0].distance.value", refused(row("[${places(endless)}]")).field)
        val unnamed = BARE.replace("Corner Tea", "")
        assertEquals("blocks[0].places[0].name", refused(row("[${places(unnamed)}]")).field)
        val sixKinds = PLACE.replace("[\"coffee_shop\"]", List(6) { "\"cafe\"" }.joinToString(",", "[", "]"))
        assertEquals("blocks[0].places[0].categories", refused(row("[${places(sixKinds)}]")).field)
        val hours = PLACE.replace("Thursday 07:00-19:00", "")
        assertEquals("blocks[0].places[0].opening_hours.current_day", refused(row("[${places(hours)}]")).field)
    }

    @Test
    fun `blocks are protocol 2's, so a protocol 1 row's are left as unknown and none is written at protocol 1`() {
        val v1 = rowOf(row("[${places(BARE)}]", v = 1))
        assertNull(v1.blocks)
        val withBlocks = rowOf(row("[${places(BARE)}]"))
        val refusal = assertThrows<ProtocolException.FieldNotInVersion> { encodeServerEvent(1, 5uL, withBlocks) }
        assertEquals("blocks", refusal.field)
    }
}
