package io.tezra.fermix.onboarding

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The scan hands the screen each code the analysis reads once until another is read, as the camera reads
 * the same code in every frame it is in (design section 13.3, step 3: one `CONFIRM` per code).
 */
class ScanDeliveryTest {
    @Test
    fun `a code read frame after frame goes to the screen once, again after another, until the reads end`() =
        runTest {
            val reads = Channel<String>(Channel.UNLIMITED)
            for (text in listOf("A", "A", "B", "A")) reads.send(text)
            reads.close()
            val delivered = mutableListOf<String>()
            deliver(reads) { delivered += it }
            assertEquals(listOf("A", "B", "A"), delivered)
        }
}
