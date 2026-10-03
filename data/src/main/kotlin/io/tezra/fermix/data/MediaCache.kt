package io.tezra.fermix.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The most one profile's media cache holds: a put evicts the least recently used down to it. */
const val MAX_MEDIA_CACHE_BYTES: Long = 512L * 1024 * 1024

private const val COPY_BUFFER_BYTES = 64 * 1024

/** A blob on its way in is written under this prefix, and takes its digest's name only once it is checked. */
private const val PARTIAL_PREFIX = "partial-"

/** Bytes offered as [expected] hash to [actual]; none of them was kept. */
class MediaDigestException(
    val expected: String,
    val actual: String,
) : IOException("the bytes hash to $actual, not $expected")

/** One cached blob as eviction weighs it. */
internal data class CachedFile(
    val name: String,
    val sizeBytes: Long,
    val lastAccessMs: Long,
)

/**
 * The names to delete so that what stays holds [toBytes] or less: the least recently used first, a name
 * breaking a tie. One pass over [files], so the eviction ends however many there are.
 */
internal fun evictionOrder(
    files: List<CachedFile>,
    toBytes: Long,
): List<String> {
    var remaining = files.sumOf { it.sizeBytes }
    val evicted = mutableListOf<String>()
    for (file in files.sortedWith(compareBy({ it.lastAccessMs }, { it.name }))) {
        if (remaining <= toBytes) break
        evicted += file.name
        remaining -= file.sizeBytes
    }
    return evicted
}

/**
 * One (instance, profile)'s media (design section 9.1): files in [directory], each named by the lowercase
 * hex of its SHA-256, which a put checks before it keeps the blob, so a blob read back is the one the daemon
 * named. The least recently used go first, a read counting as a use, and the cache holds at most
 * [ceilingBytes]; [size] and [clear] are the Instance screen's "cache size" and "Clear media cache" (design
 * section 13.7). A blob's last use is its file's modification time, set from [clock] on every put and read.
 * The methods do file I/O, so they run off the main thread, and so does making one: it deletes the partial
 * files of puts that a process death cut short, which nothing else counts or evicts. A put in flight writes
 * such a file too, so a process makes one MediaCache per directory, and only ProfileDatabases makes one, which
 * orders its uses against the instance's removal (ProfileDatabases.withMediaCache).
 */
class MediaCache internal constructor(
    private val directory: File,
    private val clock: () -> Long,
    private val ceilingBytes: Long = MAX_MEDIA_CACHE_BYTES,
) {
    private val lock = Any()

    init {
        require(ceilingBytes > 0L) { "a ceiling of $ceilingBytes bytes" }
        listed(directory).filter { it.name.startsWith(PARTIAL_PREFIX) }.forEach(::delete)
    }

    /** Keeps [bytes] as [sha256], as [put] keeps a stream. */
    fun put(
        bytes: ByteArray,
        sha256: String,
    ): File {
        requireName(sha256)
        require(bytes.size <= ceilingBytes) { "${bytes.size} bytes is past the cache's $ceilingBytes" }
        return bytes.inputStream().use { put(it, sha256) }
    }

    /**
     * Copies [input], which the caller closes, and keeps it as [sha256] once its bytes hash to that, after
     * evicting the least recently used of the other blobs until it fits under the ceiling; the blob kept is
     * never among them. Bytes that hash to anything else are a [MediaDigestException], a blob past the
     * ceiling an IllegalArgumentException, and neither, nor a stream that fails with anything at all, leaves
     * a file.
     */
    fun put(
        input: InputStream,
        sha256: String,
    ): File {
        requireName(sha256)
        makeDirectory(directory)
        return PartialFile(directory).use { partial -> keep(input, sha256, partial.file) }
    }

    /** The blob kept as [sha256], marked used, or null when the cache does not hold it. */
    fun get(sha256: String): File? {
        requireName(sha256)
        val file = File(directory, sha256)
        synchronized(lock) {
            if (!file.isFile) return null
            touch(file, clock())
        }
        return file
    }

    /** The bytes the cached blobs hold. */
    fun size(): Long = synchronized(lock) { cachedFiles(directory).sumOf { it.sizeBytes } }

    /** Deletes the least recently used blobs until the rest hold [toBytes] or less. */
    fun evict(toBytes: Long) {
        require(toBytes >= 0L) { "evicting to $toBytes bytes" }
        synchronized(lock) { evictLocked(toBytes) }
    }

    /** Deletes every file in the cache. */
    fun clear() {
        synchronized(lock) { listed(directory).forEach(::delete) }
    }

    private fun keep(
        input: InputStream,
        sha256: String,
        partial: File,
    ): File {
        val actual = partial.outputStream().use { output -> copyHashing(input, output, ceilingBytes) }
        if (actual != sha256) throw MediaDigestException(sha256, actual)
        val target = File(directory, sha256)
        synchronized(lock) {
            // Room is made among the others before the blob moves in, so the eviction never weighs the blob
            // being kept: a clock that has not moved would make it as old as any, and liable to go first.
            val others = cachedFiles(directory).filter { it.name != sha256 }
            evictionOrder(others, ceilingBytes - partial.length()).forEach { name -> delete(File(directory, name)) }
            if (!partial.renameTo(target)) throw IOException("could not move $partial to $target")
            touch(target, clock())
        }
        return target
    }

    private fun evictLocked(toBytes: Long) {
        evictionOrder(cachedFiles(directory), toBytes).forEach { name -> delete(File(directory, name)) }
    }
}

/**
 * Copies [input] into [output] and returns its SHA-256, refusing more than [ceilingBytes]. Each read before
 * the end returns a byte or more, so the ceiling bounds the loop.
 */
private fun copyHashing(
    input: InputStream,
    output: OutputStream,
    ceilingBytes: Long,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    var read = input.read(buffer)
    while (read >= 0) {
        total += read
        require(total <= ceilingBytes) { "the blob is past the cache's $ceilingBytes bytes" }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        read = input.read(buffer)
    }
    return hex(digest.digest())
}

/** The blobs in [directory], by their digest names; a file half-written is not one yet. */
private fun cachedFiles(directory: File): List<CachedFile> =
    listed(directory)
        .filter { SHA256_HEX.matches(it.name) }
        .map { CachedFile(it.name, it.length(), it.lastModified()) }

private fun listed(directory: File): List<File> {
    if (!directory.exists()) return emptyList()
    return directory.listFiles()?.toList() ?: throw IOException("could not list $directory")
}

private fun touch(
    file: File,
    nowMs: Long,
) {
    if (!file.setLastModified(nowMs)) throw IOException("could not mark $file used")
}

/** Makes [directory] and its parents, unless it is there. */
internal fun makeDirectory(directory: File) {
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("could not make $directory")
}

/** Whether [name] can name a cached blob: a SHA-256 in lowercase hex, which [MediaCache.get] and put require. */
fun isMediaName(name: String): Boolean = SHA256_HEX.matches(name)

private fun requireName(sha256: String) {
    require(isMediaName(sha256)) { "a cached blob is named by a SHA-256 in lowercase hex" }
}

private fun delete(file: File) {
    if (!file.delete() && file.exists()) throw IOException("could not delete $file")
}

/**
 * A blob on its way in, as a resource: closing it deletes [file] unless a put has moved it to its digest's
 * name, so `use` deletes it on every path, whatever the copy throws, and notes a failed delete on that
 * failure as suppressed rather than hiding it.
 */
private class PartialFile(
    directory: File,
) : Closeable {
    val file: File = File.createTempFile(PARTIAL_PREFIX, null, directory)

    override fun close() = delete(file)
}
