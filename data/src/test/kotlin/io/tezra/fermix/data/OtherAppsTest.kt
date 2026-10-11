package io.tezra.fermix.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val OWN = "io.tezra.fermix.chat.files"

/**
 * What the app reads as another app's (AGENTS.md: a string from another app never names a path or picks what the app
 * reads), the one rule the chat's landings (mayRead) and the avatar's photo hold to: only a `content:` URI whose
 * authority, its `user@` prefix stripped as the ContentResolver strips it, names a provider, and none of the app's.
 */
class OtherAppsTest {
    private val asked = mutableListOf<String>()
    private val own = { authority: String -> (authority == OWN).also { asked += authority } }

    @Test
    fun `another app's content URI is its, as the Photo Picker's is`() {
        assertTrue(isOtherAppsContent("content", "media", own))
        assertTrue(isOtherAppsContent("content", "com.android.providers.media.photopicker", own))
        assertTrue(isOtherAppsContent("content", "10@media", own))
        assertEquals(listOf("media", "com.android.providers.media.photopicker", "media"), asked)
    }

    @Test
    fun `a file URI, the app's own provider with or without a user, and any other scheme are refused`() {
        assertFalse(isOtherAppsContent("file", null, own))
        assertFalse(isOtherAppsContent("file", OWN, own))
        assertFalse(isOtherAppsContent("content", OWN, own))
        assertFalse(isOtherAppsContent("content", "0@$OWN", own))
        assertFalse(isOtherAppsContent("content", "x@y@$OWN", own))
        assertFalse(isOtherAppsContent("CONTENT", "media", own))
        assertFalse(isOtherAppsContent("http", "media", own))
        assertFalse(isOtherAppsContent(null, "media", own))
    }

    @Test
    fun `a content URI that names no provider is refused, and asks nothing`() {
        assertFalse(isOtherAppsContent("content", null, own))
        assertFalse(isOtherAppsContent("content", "", own))
        assertFalse(isOtherAppsContent("content", "0@", own))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a refusal names the URI's scheme and authority, never its path`() {
        assertEquals("file URI of no authority", outsideOf("file", null))
        assertEquals("content URI of $OWN", outsideOf("content", OWN))
    }
}
