package io.tezra.fermix.chat

import android.content.res.Resources
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceRating
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/** A rating and a distance show at most this many decimals (M72 section 14). */
private const val MAX_DECIMALS = 1

/** [value] in [locale]'s digits, one decimal at most. */
private fun decimal(
    value: Double,
    locale: Locale,
): String = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = MAX_DECIMALS }.format(value)

/** [count] in [locale]'s digits, grouped. */
private fun whole(
    count: Long,
    locale: Locale,
): String = NumberFormat.getIntegerInstance(locale).format(count)

/** [parts] one after the other, [join] (two arguments) between each two; none when there are none. */
private fun joined(
    parts: List<String>,
    join: Int,
    resources: Resources,
): String? = parts.reduceOrNull { line, part -> resources.getString(join, line, part) }

/** A place's kind: its first category in words, "coffee_shop" as "Coffee shop"; none when it has no words. */
private fun kindOf(
    place: Place,
    locale: Locale,
): String? {
    val words =
        place.categories
            ?.firstOrNull()
            ?.split('_', ' ')
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    return words.joinToString(" ").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.titlecase(locale) }
}

/** A rating as a card shows it, "4.6 (321)": its value, and how many rated when the record says; none without one. */
private fun ratingFact(
    rating: PlaceRating?,
    locale: Locale,
    resources: Resources,
): String? {
    val value = rating?.value?.let { decimal(it, locale) }
    val count = rating?.count?.let { whole(it.roundToLong(), locale) }
    return when {
        value == null -> null
        count == null -> value
        else -> resources.getString(R.string.chat_place_rating_count, value, count)
    }
}

/** A distance as the record gives it, "0.4 km", in its own units; none when it gives none. */
private fun distanceOf(
    place: Place,
    locale: Locale,
    resources: Resources,
): String? =
    place.distance?.let { distance ->
        resources.getString(R.string.chat_place_distance, decimal(distance.value, locale), distance.units)
    }

/**
 * A place card's facts line (M72 section 8.1): its rating, its price and its kind, "4.6 (321) · $$ · Coffee shop",
 * each the record lacks left out with its separator; none when it lacks them all.
 */
internal fun factsLine(
    place: Place,
    locale: Locale,
    resources: Resources,
): String? {
    val facts = listOfNotNull(ratingFact(place.rating, locale, resources), place.priceRange, kindOf(place, locale))
    return joined(facts, R.string.chat_place_facts, resources)
}

/**
 * A place card's hours line (M72 section 8.1): today's hours as the source gives them, never worked out here, and the
 * distance, "Thursday 07:00-19:00 · 0.4 km", each the record lacks left out; none when it lacks both.
 */
internal fun hoursLine(
    place: Place,
    locale: Locale,
    resources: Resources,
): String? {
    val facts = listOfNotNull(place.openingHours?.currentDay, distanceOf(place, locale, resources))
    return joined(facts, R.string.chat_place_facts, resources)
}

/** A rating as TalkBack says it, "4.6 of 5 from 321 ratings"; none without a value. */
private fun spokenRating(
    rating: PlaceRating?,
    locale: Locale,
    resources: Resources,
): String? {
    val value = rating?.value?.let { decimal(it, locale) } ?: return null
    val rated =
        rating.best?.let { resources.getString(R.string.chat_place_rating_of, value, decimal(it, locale)) }
            ?: resources.getString(R.string.chat_place_rated, value)
    val count = rating.count?.roundToLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())
    val counted =
        count?.let { many ->
            resources.getQuantityString(R.plurals.chat_place_ratings, many.toInt(), rated, whole(many, locale))
        }
    return counted ?: rated
}

/**
 * What TalkBack says of a place card, in one sentence: its name, then its facts as the card's lines tell them,
 * "Example Coffee, 4.6 of 5 from 321 ratings, $$, Coffee shop, Thursday 07:00-19:00, 0.4 km", each the record lacks
 * left out.
 */
internal fun spokenPlace(
    place: Place,
    locale: Locale,
    resources: Resources,
): String {
    val facts =
        listOfNotNull(
            spokenRating(place.rating, locale, resources),
            place.priceRange,
            kindOf(place, locale),
            place.openingHours?.currentDay,
            distanceOf(place, locale, resources),
        )
    val parts = listOf(place.name) + facts
    return parts.reduce { line, part -> resources.getString(R.string.chat_place_spoken, line, part) }
}
