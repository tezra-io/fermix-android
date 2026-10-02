package io.tezra.fermix.design

import io.tezra.fermix.data.TINT_NAMES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An instance record keeps its tint by name and refuses a name not in data's TINT_NAMES, since data does not
 * depend on this module; the two lists are held equal here, where both are in reach, so a tint added or renamed
 * on one side fails until the other follows.
 */
class TintNamesTest {
    @Test
    fun `data's tint names are this enum's, in its order`() {
        assertEquals(Tint.entries.map { it.name }, TINT_NAMES)
    }
}
