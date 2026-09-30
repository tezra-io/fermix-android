package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppVersionTest {
    private fun refusal(text: String): String? = assertThrows<GradleException> { parseAppVersion(text) }.message

    private fun sentence(
        name: String,
        code: String,
    ) = "version.properties needs versionName as MAJOR.MINOR.PATCH and versionCode as a whole number " +
        "from 1; it has versionName '$name' and versionCode '$code'."

    @Test
    fun `a release version parses`() {
        assertEquals(AppVersion("0.1.0", 1), parseAppVersion("# comment\nversionName=0.1.0\nversionCode=1\n"))
        assertEquals(AppVersion("12.40.7", 2_000), parseAppVersion("versionName=12.40.7\nversionCode=2000\n"))
    }

    @Test
    fun `a versionName that is not three whole numbers fails`() {
        listOf("1.2", "v1.2.3", "1.2.3-rc.1", "1.2.x", "").forEach { name ->
            assertEquals(sentence(name, "1"), refusal("versionName=$name\nversionCode=1\n"))
        }
    }

    @Test
    fun `a versionCode that is not a whole number from 1 fails`() {
        listOf("0", "-1", "1.5", "one", "").forEach { code ->
            assertEquals(sentence("0.1.0", code), refusal("versionName=0.1.0\nversionCode=$code\n"))
        }
    }

    @Test
    fun `an empty file fails`() {
        assertEquals(sentence("", ""), refusal(""))
    }
}
