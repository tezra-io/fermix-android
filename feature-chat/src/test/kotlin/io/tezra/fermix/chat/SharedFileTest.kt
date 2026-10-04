package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** The instance records' file as AppServices keeps it under `noBackupFilesDir`, and what it holds. */
private const val RECORDS_NAME = "instances.pb"
private val RECORDS = byteArrayOf(9, 8, 7, 6)

/** A ref the wire allows (PROTOCOL.md gives `ref` no form) that climbs from cache/shared/ to no_backup/. */
private const val CLIMBING_REF = "../../no_backup/"

/** The most bytes a file's name takes on ext4 and f2fs (NAME_MAX). */
private const val NAME_MAX_BYTES = 255

/** The most copies kept for other apps, and a time older than any copy's. */
private const val MAX_SHARED = 8
private const val OLDEST_MS = 1_000_000_000_000L

/** A shared copy's directory: 16 lowercase hex characters. */
private const val SHARED_NAME_CHARS = 16
private val SHARED_NAME = Regex("[0-9a-f]{$SHARED_NAME_CHARS}")

private fun document(
    ref: String,
    name: String?,
) = ShownMedia(ref, null, MediaShape.DOCUMENT, "application/octet-stream", RECORDS.size.toLong(), name)

/** The first 16 hex characters of the SHA-256 of [name]'s UTF-8 bytes, as the test reckons them. */
private fun hashed(name: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8))
        .toHexString()
        .take(SHARED_NAME_CHARS)

/**
 * Where a blob is copied for another app (design section 13.5): a string from the wire never names the path. The
 * copy's directory is named by a digest of the blob's cache name, its file by the blob's name with no directory in
 * it, and the copy is checked to lie under the cache's shared directory before anything is deleted or made.
 */
class SharedFileTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `a shared copy's directory is 16 hex characters of its cache name's digest, whatever the daemon named it`() {
        val names =
            listOf(
                CLIMBING_REF,
                "..",
                "/data/x",
                "ab".repeat(32),
                " ",
                "\u200B",
                "\u0000",
                "r".repeat(4_096),
            )
        val directories = names.map(::sharedDirectoryName)
        directories.forEach { assertTrue(SHARED_NAME.matches(it), it) }
        assertEquals(names.map(::hashed), directories)
        assertEquals(names.size, directories.toSet().size)
        assertEquals(directories, names.map(::sharedDirectoryName))
    }

    @Test
    fun `a row whose ref climbs out and whose name is the records file's lands under cache shared and nowhere else`() {
        // A phone that has shared a blob before, so a path through cache/shared/.. resolves.
        val shared = File(root, "cache/shared").apply { mkdirs() }.canonicalFile
        val cache = File(root, "cache")
        val noBackup = File(root, "no_backup").apply { mkdirs() }
        val records = File(noBackup, RECORDS_NAME).apply { writeBytes(RECORDS) }
        val names =
            listOf(RECORDS_NAME, "../$RECORDS_NAME", "..\\..\\no_backup\\$RECORDS_NAME", "a/../../$RECORDS_NAME")
        for (name in names) {
            val file = sharedFile(cache, document(CLIMBING_REF, name)).canonicalFile
            val directory = checkNotNull(file.parentFile) { "$file is in no directory" }
            assertEquals(shared, directory.parentFile, name)
            assertEquals(sharedDirectoryName(CLIMBING_REF), directory.name, name)
            assertEquals(RECORDS_NAME, file.name, name)
        }
        assertArrayEquals(RECORDS, records.readBytes())
        assertEquals(setOf("cache", "no_backup"), root.list().orEmpty().toSet())
        assertEquals(listOf(RECORDS_NAME), noBackup.list().orEmpty().toList())
        assertEquals(listOf(sharedDirectoryName(CLIMBING_REF)), shared.list().orEmpty().toList())
    }

    @Test
    fun `a shared directory that leads out is refused before anything is deleted or made, naming neither`() {
        val cache = File(root, "cache")
        val outside = File(root, "no_backup").apply { mkdirs() }
        val shared = File(cache, "shared").apply { mkdirs() }
        // A full shared directory, each copy older than the link: a copy made would evict the oldest two first.
        val copies =
            (1..MAX_SHARED).map { at ->
                File(shared, "$at".padStart(SHARED_NAME_CHARS, '0')).apply {
                    mkdirs()
                    File(this, "copy.pdf").writeBytes(RECORDS)
                    check(setLastModified(OLDEST_MS + at * 1_000L)) { "$this kept its time" }
                }
            }
        val ref = "blob-1"
        val link = File(shared, sharedDirectoryName(ref))
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val refused = assertThrows<IOException> { sharedFile(cache, document(ref, RECORDS_NAME)) }
        val words = refused.message.orEmpty()
        assertFalse(ref in words, words)
        assertFalse(RECORDS_NAME in words, words)
        assertFalse(outside.path in words, words)
        assertEquals(emptyList<String>(), outside.list().orEmpty().toList())
        assertTrue(copies.all { File(it, "copy.pdf").isFile }, "no copy is evicted")
        assertTrue(Files.isSymbolicLink(link.toPath()), "the link stays")
    }

    @Test
    fun `a file lies under a directory only inside it, by their canonical paths`() {
        val directory = File(root, "cache").apply { mkdirs() }
        val outside = File(root, "no_backup").apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "out").toPath(), outside.toPath())
        assertTrue(liesUnder(File(directory, "a/b"), directory))
        assertTrue(liesUnder(File(directory, "a/../b"), directory))
        assertFalse(liesUnder(directory, directory))
        assertFalse(liesUnder(File(directory, "a/.."), directory))
        assertFalse(liesUnder(File(root, "cache2/x"), directory))
        assertFalse(liesUnder(File(directory, "../no_backup/x"), directory))
        assertFalse(liesUnder(File(directory, "out/x"), directory))
    }

    @Test
    fun `a blob handed to another app is named with no directory, no control character, and what a name can hold`() {
        val rows =
            listOf(
                "report.pdf" to "report.pdf",
                "  report.pdf  " to "report.pdf",
                "../../etc/passwd" to "passwd",
                "..\\..\\Windows\\win.ini" to "win.ini",
                "a/b\\c.txt" to "c.txt",
                "rep\u0000ort.pdf" to "report.pdf",
                "line\nbreak.txt" to "linebreak.txt",
                "..." to "...",
                "${"a".repeat(300)}.pdf" to "${"a".repeat(251)}.pdf",
                "${"é".repeat(200)}.pdf" to "${"é".repeat(125)}.pdf",
                "${"😀".repeat(100)}.txt" to "${"😀".repeat(62)}.txt",
                null to "file",
                "" to "file",
                " " to "file",
                "." to "file",
                ".." to "file",
                " .. " to "file",
                "a/.." to "file",
                "reports/" to "file",
                "..\\" to "file",
                ".\u0000." to "file",
                "\u0007\u001b\u007f\u009f" to "file",
                // A lone surrogate is no character a filesystem holds: ART's path calls throw on it.
                "x\uD800.pdf" to "x.pdf",
                "\uD800" to "file",
                "\uDC00\uD800" to "file",
                "a\uDC00b😀.txt" to "ab😀.txt",
            )
        val named = rows.map { (name, _) -> fileNameOf(document("r", name)) }
        assertEquals(rows.map { it.second }, named)
        named.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= NAME_MAX_BYTES, it) }
        named.forEach { assertEquals(it, it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8), "well-formed") }
    }

    @Test
    fun `the oldest copies are deleted without following a link out of the shared directory`() {
        val cache = File(root, "cache")
        val shared = File(cache, "shared").apply { mkdirs() }
        val noBackup = File(root, "no_backup").apply { mkdirs() }
        val records = File(noBackup, RECORDS_NAME).apply { writeBytes(RECORDS) }
        val link = File(shared, "a".repeat(SHARED_NAME_CHARS))
        Files.createSymbolicLink(link.toPath(), noBackup.toPath())
        // The link is the oldest copy: lastModified follows it to no_backup/.
        check(noBackup.setLastModified(OLDEST_MS)) { "$noBackup kept its time" }
        val copies =
            (1..MAX_SHARED).map { at ->
                File(shared, "$at".padStart(SHARED_NAME_CHARS, '0')).apply {
                    mkdirs()
                    File(this, "copy.pdf").writeBytes(RECORDS)
                    check(setLastModified(OLDEST_MS + at * 1_000L)) { "$this kept its time" }
                }
            }
        sharedFile(cache, document("fresh", "fresh.pdf"))
        assertArrayEquals(RECORDS, records.readBytes())
        assertEquals(listOf(RECORDS_NAME), noBackup.list().orEmpty().toList())
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS), "the link is evicted")
        assertFalse(copies.first().exists(), "the oldest real copy is evicted")
        assertTrue(copies.drop(1).all { it.isDirectory }, "the newer copies stay")
    }
}
