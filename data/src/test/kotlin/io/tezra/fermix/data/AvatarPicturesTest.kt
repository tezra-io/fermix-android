package io.tezra.fermix.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * The avatar pictures' rules on the JVM (the owner, 2026-10-10): the square a photo is cut to, its file's name, and
 * which files go. The decoding itself is the platform's ImageDecoder, which the app's device test runs
 * (AvatarPicturesDeviceTest): android.jar's stubs have none.
 */
class AvatarPicturesTest {
    @TempDir
    lateinit var directory: File

    private val id = instance(gateway = 1).id
    private val other = instance(gateway = 3).id

    @Test
    fun `a landscape photo is scaled until its short edge is 256 and cut to the square at its centre`() {
        assertEquals(AvatarCrop(width = 342, height = 256, left = 43, top = 0), avatarCrop(4_000, 3_000))
    }

    @Test
    fun `a portrait photo is cut about its middle the same way`() {
        assertEquals(AvatarCrop(width = 256, height = 342, left = 0, top = 43), avatarCrop(3_000, 4_000))
    }

    @Test
    fun `a square photo, or one smaller than the picture, is scaled to 256 across`() {
        assertEquals(AvatarCrop(width = 256, height = 256, left = 0, top = 0), avatarCrop(1_024, 1_024))
        assertEquals(AvatarCrop(width = 256, height = 256, left = 0, top = 0), avatarCrop(100, 100))
        assertEquals(AvatarCrop(width = 512, height = 256, left = 128, top = 0), avatarCrop(2, 1))
    }

    @Test
    fun `a photo so long and thin that its scaled edge passes 4,096 pixels is refused, as is one with no pixels`() {
        assertEquals(AvatarCrop(width = 256, height = 4_096, left = 0, top = 1_920), avatarCrop(1, 16))
        assertEquals(AvatarCrop(width = 3_072, height = 256, left = 1_408, top = 0), avatarCrop(12_000, 1_000))
        assertThrows<IOException> { avatarCrop(1, 17) }
        assertThrows<IOException> { avatarCrop(0, 10) }
    }

    @Test
    fun `a picture's file is its instance's id under the directory, and a name that is no id is refused`() {
        val pictures = AvatarPictures(directory)
        assertEquals(File(directory, "$id.jpg"), pictures.fileOf(id))
        listOf("../instances.json", "", id.uppercase()).forEach { name ->
            assertThrows<IllegalArgumentException> { pictures.fileOf(name) }
        }
    }

    @Test
    fun `the pictures of the named instances stay, and the others and any write's part go, but no stranger`() {
        val pictures = AvatarPictures(directory)
        val kept = pictures.fileOf(id).apply { writeText("kept") }
        pictures.fileOf(other).writeText("orphan")
        File(directory, "${id}17.jpg.part").writeText("a write cut short")
        File(directory, "notes.txt").writeText("not a picture")
        pictures.deleteAllExcept(setOf(id))
        assertEquals(setOf(kept.name, "notes.txt"), directory.list().orEmpty().toSet())
    }

    @Test
    fun `a move hands the picture to the new id in one rename, and moves nothing when there is none`() {
        val pictures = AvatarPictures(directory)
        pictures.fileOf(id).writeBytes(BYTES)
        pictures.move(id, other)
        assertFalse(pictures.fileOf(id).exists())
        assertArrayEquals(BYTES, pictures.fileOf(other).readBytes())
        pictures.move(id, other)
        assertArrayEquals(BYTES, pictures.fileOf(other).readBytes())
        pictures.delete(other)
        assertFalse(pictures.fileOf(other).exists())
        pictures.delete(other)
    }

    private companion object {
        val BYTES = "a picture's JPEG".encodeToByteArray()
    }
}
