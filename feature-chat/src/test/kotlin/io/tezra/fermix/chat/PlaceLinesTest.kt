package io.tezra.fermix.chat

import android.content.res.Resources
import io.tezra.fermix.protocol.OpeningHours
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceDistance
import io.tezra.fermix.protocol.PlaceRating
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** The no-break space that keeps a distance with its units. */
private const val NBSP = " "

/** A place with none of the facts a card's lines tell. */
private val BARE: Place = PAPER_LANTERN

/**
 * A place card's words (M72 sections 8.1 and 14): the facts line, "4.6 (321) · $$ · Coffee shop", and the hours
 * line, today's hours as the source gives them and the distance, "Thursday 07:00-19:00 · 0.4 km", each fact the
 * record lacks left out with its separator, and no line when it lacks them all; numbers in the owner's locale, one
 * decimal at most; and what TalkBack says of the card, in one sentence.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceLinesTest {
    private val resources: Resources = RuntimeEnvironment.getApplication().resources

    private fun facts(
        place: Place,
        locale: Locale = Locale.US,
    ) = factsLine(place, locale, resources)

    private fun hours(
        place: Place,
        locale: Locale = Locale.US,
    ) = hoursLine(place, locale, resources)

    @Test
    fun `the facts line leaves out each fact the record lacks, with its separator`() {
        val rating = PlaceRating(4.6, 5.0, 321.0)
        val cases =
            listOf(
                Triple(rating, "$$", "coffee_shop") to "4.6 (321) · $$ · Coffee shop",
                Triple(rating, "$$", null) to "4.6 (321) · $$",
                Triple(rating, null, "coffee_shop") to "4.6 (321) · Coffee shop",
                Triple(rating, null, null) to "4.6 (321)",
                Triple(null, "$$", "coffee_shop") to "$$ · Coffee shop",
                Triple(null, "$$", null) to "$$",
                Triple(null, null, "coffee_shop") to "Coffee shop",
                Triple(null, null, null) to null,
            )
        cases.forEach { (facts, line) ->
            val (rated, price, kind) = facts
            val place = BARE.copy(rating = rated, priceRange = price, categories = kind?.let(::listOf))
            assertEquals("$facts", line, facts(place))
        }
    }

    @Test
    fun `a rating shows its value, with how many rated when the record says, and none without a value`() {
        assertEquals("4.6", facts(BARE.copy(rating = PlaceRating(4.6, 5.0, null))))
        assertEquals("4.6", facts(BARE.copy(rating = PlaceRating(4.6, null, null))))
        assertEquals("4 (1,204)", facts(BARE.copy(rating = PlaceRating(4.0, 5.0, 1_204.0))))
        assertEquals("4.7 (12)", facts(BARE.copy(rating = PlaceRating(4.66, 5.0, 12.0))))
        assertEquals(null, facts(BARE.copy(rating = PlaceRating(null, 5.0, 321.0))))
        assertEquals("$$", facts(BARE.copy(rating = PlaceRating(null, 5.0, 321.0), priceRange = "$$")))
        assertEquals("4,6 (1.204)", facts(BARE.copy(rating = PlaceRating(4.6, 5.0, 1_204.0)), Locale.GERMANY))
    }

    @Test
    fun `the kind is the first category, in words, in sentence case`() {
        val kinds =
            listOf(
                listOf("coffee_shop", "bakery") to "Coffee shop",
                listOf("bookstore") to "Bookstore",
                listOf("ice_cream__parlour_") to "Ice cream parlour",
                listOf("Tea House") to "Tea House",
                listOf("_") to null,
            )
        kinds.forEach { (categories, kind) ->
            assertEquals("$categories", kind, facts(BARE.copy(categories = categories)))
        }
    }

    @Test
    fun `the hours line leaves out what the record lacks, and gives the hours as the source does`() {
        val today = OpeningHours("Thursday 07:00-19:00")
        val near = PlaceDistance(0.4, "km")
        assertEquals("Thursday 07:00-19:00 · 0.4${NBSP}km", hours(BARE.copy(openingHours = today, distance = near)))
        assertEquals("Thursday 07:00-19:00", hours(BARE.copy(openingHours = today)))
        assertEquals("0.4${NBSP}km", hours(BARE.copy(distance = near)))
        assertEquals(null, hours(BARE))
    }

    @Test
    fun `a distance has one decimal at most, in the owner's locale, in the record's units`() {
        assertEquals("2.5${NBSP}mi", hours(BARE.copy(distance = PlaceDistance(2.46, "mi"))))
        assertEquals("12${NBSP}km", hours(BARE.copy(distance = PlaceDistance(12.0, "km"))))
        assertEquals("1,250${NBSP}m", hours(BARE.copy(distance = PlaceDistance(1_250.0, "m"))))
        assertEquals("0,4${NBSP}km", hours(BARE.copy(distance = PlaceDistance(0.4, "km")), Locale.GERMANY))
    }

    @Test
    fun `TalkBack hears the card as one sentence, each fact the record lacks left out`() {
        assertEquals(
            "Example Coffee, 4.6 of 5 from 321 ratings, $$, Coffee shop, Thursday 07:00-19:00, 0.4${NBSP}km",
            spokenPlace(EXAMPLE_COFFEE, Locale.US, resources),
        )
        assertEquals("Paper Lantern Books (Café)", spokenPlace(BARE, Locale.US, resources))
        val once = BARE.copy(rating = PlaceRating(5.0, 5.0, 1.0))
        assertEquals("Paper Lantern Books (Café), 5 of 5 from 1 rating", spokenPlace(once, Locale.US, resources))
        val unbounded = BARE.copy(rating = PlaceRating(8.2, null, 40.0))
        val rated = spokenPlace(unbounded, Locale.US, resources)
        assertEquals("Paper Lantern Books (Café), rated 8.2 from 40 ratings", rated)
        val uncounted = BARE.copy(rating = PlaceRating(4.2, 5.0, null), priceRange = "$")
        assertEquals("Paper Lantern Books (Café), 4.2 of 5, $", spokenPlace(uncounted, Locale.US, resources))
    }
}
