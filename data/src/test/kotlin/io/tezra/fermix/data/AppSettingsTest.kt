package io.tezra.fermix.data

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The app-wide settings (design sections 9.1, 13.4 and 13.7): the app lock and the last open chat. */
class AppSettingsTest {
    @TempDir
    lateinit var directory: File

    private val chat = ChatRef(idOf(key(1)), MAIN_PROFILE)

    @Test
    fun `the settings start with the lock off and no last chat`() =
        runTest {
            val store = AppSettingsStore(appSettingsDataStore(File(directory, "settings.json"), backgroundScope))
            assertEquals(AppSettings(appLock = false, lastChat = null), store.settings.first())
        }

    @Test
    fun `the lock and the last chat are kept on disk, and the last chat clears`() =
        runTest {
            val file = File(directory, "settings.json")
            val store = AppSettingsStore(appSettingsDataStore(file, backgroundScope))
            store.setAppLock(true)
            store.setLastChat(chat)
            assertEquals(AppSettings(appLock = true, lastChat = chat), store.settings.first())
            val read = AppSettingsSerializer.readFrom(file.inputStream())
            assertEquals(AppSettings(appLock = true, lastChat = chat), read)
            store.setLastChat(null)
            assertEquals(AppSettings(appLock = true, lastChat = null), store.settings.first())
        }

    @Test
    fun `a settings file that does not decode is reported and kept, never replaced by the lock turned off`() =
        runTest {
            val bytes = "{\"app_lock\": tru".encodeToByteArray()
            val file = File(directory, "settings.json").apply { writeBytes(bytes) }
            val store = AppSettingsStore(appSettingsDataStore(file, backgroundScope))
            val failure = runCatching { store.settings.first() }.exceptionOrNull()
            assertInstanceOf(CorruptionException::class.java, failure)
            assertArrayEquals(bytes, file.readBytes())
        }

    @Test
    fun `a chat is keyed by an instance's id and a profile`() {
        assertThrows<IllegalArgumentException> { ChatRef("suj-mbp", MAIN_PROFILE) }
        assertThrows<IllegalArgumentException> { ChatRef(idOf(key(1)), "") }
    }
}
