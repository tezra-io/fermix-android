package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The image pipeline's long-edge cap (design section 8.5, "Images": ImageDecoder with setTargetSize): a photo past
 * [LONG_EDGE_PX] on its long edge decodes at it, its aspect kept; one within it at its own size, never scaled up;
 * no edge below a pixel.
 */
class ImageCapTest {
    @Test
    fun `a landscape photo past the cap decodes with its long edge at it, its aspect kept`() {
        assertEquals(2_048 to 1_536, cappedSize(4_000, 3_000, LONG_EDGE_PX))
        assertEquals(2_048 to 1_536, cappedSize(8_064, 6_048, LONG_EDGE_PX))
    }

    @Test
    fun `a portrait photo is capped on its height`() {
        assertEquals(1_536 to 2_048, cappedSize(3_000, 4_000, LONG_EDGE_PX))
        assertEquals(945 to 2_048, cappedSize(1_080, 2_340, LONG_EDGE_PX))
    }

    @Test
    fun `an image at or within the cap keeps its size, and none is scaled up`() {
        assertEquals(2_048 to 2_048, cappedSize(2_048, 2_048, LONG_EDGE_PX))
        assertEquals(64 to 48, cappedSize(64, 48, LONG_EDGE_PX))
    }

    @Test
    fun `a sliver keeps a pixel on its short edge, and a size of nothing decodes at one`() {
        assertEquals(2_048 to 1, cappedSize(100_000, 10, LONG_EDGE_PX))
        assertEquals(1 to 1, cappedSize(0, 0, LONG_EDGE_PX))
    }
}
