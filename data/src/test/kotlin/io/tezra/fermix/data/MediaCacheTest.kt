package io.tezra.fermix.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

/**
 * The media cache (design sections 9.1 and 13.7, Storage): one profile's blobs by their sha256, each
 * checked on its way in, the least recently used evicted first, never past its ceiling.
 */
class MediaCacheTest {
    @TempDir
    lateinit var directory: File

    private var now = 0L
    private val clock = { now }

    private fun blob(
        fill: Int,
        size: Int,
    ) = ByteArray(size) { fill.toByte() }

    @Test
    fun `a blob is kept by its digest, and read back`() {
        val cache = MediaCache(directory, clock)
        val bytes = blob(1, 100)
        val file = cache.put(bytes, idOf(bytes))
        assertEquals(File(directory, idOf(bytes)), file)
        assertArrayEquals(bytes, cache.get(idOf(bytes))?.readBytes())
        assertEquals(100L, cache.size())
        assertNull(cache.get(idOf(blob(2, 1))))
    }

    @Test
    fun `bytes that do not hash to the digest given are refused and nothing is kept`() {
        val cache = MediaCache(directory, clock)
        val claimed = idOf(blob(1, 100))
        val refusal = assertThrows<MediaDigestException> { cache.put(blob(2, 100).inputStream(), claimed) }
        assertEquals(claimed, refusal.expected)
        assertEquals(idOf(blob(2, 100)), refusal.actual)
        assertNull(cache.get(claimed))
        assertEquals(emptyList<String>(), directory.list()?.toList())
    }

    @Test
    fun `a stream that fails partway leaves no file, whatever it throws`() {
        val cache = MediaCache(directory, clock)
        val bytes = blob(1, 100)
        // okio throws IllegalStateException("closed") when a download's body is closed under it.
        val closedUnderIt =
            object : InputStream() {
                private var served = 0

                override fun read(): Int {
                    check(served < 10) { "closed" }
                    served += 1
                    return 1
                }
            }
        assertThrows<IllegalStateException> { cache.put(closedUnderIt, idOf(bytes)) }
        assertEquals(emptyList<String>(), directory.list()?.toList())
        assertEquals(0L, cache.size())
    }

    @Test
    fun `a name that is not a lowercase sha256 is refused`() {
        val cache = MediaCache(directory, clock)
        listOf("../escape", idOf(blob(1, 1)).uppercase(), "ab").forEach { name ->
            assertThrows<IllegalArgumentException> { cache.get(name) }
            assertThrows<IllegalArgumentException> { cache.put(blob(1, 1), name) }
        }
    }

    @Test
    fun `eviction takes the least recently used first, and a read counts as a use`() {
        val cache = MediaCache(directory, clock)
        val blobs = (1..3).map { fill -> blob(fill, 100) }
        blobs.forEachIndexed { index, bytes ->
            now = (index + 1) * STEP
            cache.put(bytes, idOf(bytes))
        }
        now = 10 * STEP
        cache.get(idOf(blobs[0]))
        cache.evict(toBytes = 150L)
        assertEquals(100L, cache.size())
        assertArrayEquals(blobs[0], cache.get(idOf(blobs[0]))?.readBytes())
        assertNull(cache.get(idOf(blobs[1])))
        assertNull(cache.get(idOf(blobs[2])))
    }

    @Test
    fun `a put evicts down to the ceiling, and a blob past the ceiling is refused`() {
        val cache = MediaCache(directory, clock, ceilingBytes = 250L)
        val blobs = (1..3).map { fill -> blob(fill, 100) }
        blobs.forEachIndexed { index, bytes ->
            now = (index + 1) * STEP
            cache.put(bytes, idOf(bytes))
        }
        assertEquals(200L, cache.size())
        assertNull(cache.get(idOf(blobs[0])))
        val tooLarge = blob(9, 251)
        assertThrows<IllegalArgumentException> { cache.put(tooLarge, idOf(tooLarge)) }
        assertEquals(200L, cache.size())
    }

    @Test
    fun `a put never evicts the blob it keeps, however the clock stands`() {
        // A clock that has not moved, or stepped back, leaves the new blob as old as any; ties go by name.
        val cache = MediaCache(directory, clock = { 0L }, ceilingBytes = 250L)
        (1..40).forEach { fill ->
            val bytes = blob(fill, 100)
            val file = cache.put(bytes, idOf(bytes))
            assertTrue(file.isFile, "put $fill evicted the blob it kept")
            assertTrue(cache.size() <= 250L, "put $fill left ${cache.size()} bytes")
        }
    }

    @Test
    fun `a partial file a put left when its process died is deleted when the cache is made, before any put`() {
        val orphan = File(directory, "partial-123456.tmp").apply { writeBytes(ByteArray(900)) }
        val cache = MediaCache(directory, clock, ceilingBytes = 1000L)
        assertFalse(orphan.exists(), "the orphaned partial file is still there")
        val bytes = blob(1, 900)
        cache.put(bytes, idOf(bytes))
        assertEquals(listOf(idOf(bytes)), directory.list()?.toList())
    }

    @Test
    fun `clear empties the cache`() {
        val cache = MediaCache(directory, clock)
        cache.put(blob(1, 10), idOf(blob(1, 10)))
        cache.clear()
        assertEquals(0L, cache.size())
        assertNull(cache.get(idOf(blob(1, 10))))
    }

    @Test
    fun `the eviction order is least recently used first, until what stays fits`() {
        val files =
            listOf(
                CachedFile("c", 100L, lastAccessMs = 3L),
                CachedFile("a", 100L, lastAccessMs = 1L),
                CachedFile("b", 100L, lastAccessMs = 2L),
            )
        assertEquals(listOf("a", "b"), evictionOrder(files, toBytes = 100L))
        assertEquals(emptyList<String>(), evictionOrder(files, toBytes = 300L))
        assertEquals(listOf("a", "b", "c"), evictionOrder(files, toBytes = 0L))
    }

    private companion object {
        /** Apart by whole seconds, which every file system's modification time keeps. */
        const val STEP = 1_000_000L
    }
}
