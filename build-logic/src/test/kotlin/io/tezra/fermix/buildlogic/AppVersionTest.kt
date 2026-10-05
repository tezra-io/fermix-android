package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class AppVersionTest {
    @TempDir
    lateinit var scratch: File

    private val repository by lazy { scratch.resolve("repository").also { it.mkdirs() } }

    private fun refusal(text: String): String? = assertThrows<GradleException> { parseVersionCode(text) }.message

    private fun sentence(code: String) =
        "version.properties needs versionCode as a whole number from 1; it has versionCode '$code'."

    @Test
    fun `a versionCode parses`() {
        assertEquals(1, parseVersionCode("# comment\nversionCode=1\n"))
        assertEquals(2_000, parseVersionCode("versionCode=2000\n"))
    }

    @Test
    fun `a versionCode that is not a whole number from 1 fails`() {
        listOf("0", "-1", "1.5", "one", "").forEach { code ->
            assertEquals(sentence(code), refusal("versionCode=$code\n"))
        }
    }

    @Test
    fun `an empty file fails`() {
        assertEquals(sentence(""), refusal(""))
    }

    @Test
    fun `a versionName in version properties fails, as the tag names the version`() {
        assertEquals(
            "version.properties holds versionCode alone: versionName is the nearest release tag without its v " +
                "(CI/CD design section 4.4), so remove versionName=0.1.0 from it.",
            refusal("versionName=0.1.0\nversionCode=1\n"),
        )
    }

    @Test
    fun `a release tag names the version, and a commit with no tag behind it the unreleased one`() {
        assertEquals("1.2.3", versionNameOf("v1.2.3\n"))
        assertEquals("12.40.7", versionNameOf("v12.40.7"))
        assertEquals(UNRELEASED_VERSION_NAME, versionNameOf("01d40ec4f52be7b1e43a546bff94add4dd316e72\n"))
        assertEquals("0.0.0-dev", UNRELEASED_VERSION_NAME)
    }

    @Test
    fun `a nearest tag that is not a release tag fails`() {
        listOf("v1.2.3-rc.1", "v1.2.3.4", "v01.2.x", "", "release").forEach { described ->
            val message = assertThrows<GradleException> { versionNameOf(described) }.message.orEmpty()
            val sentence = "The nearest tag, '$described', is not a release tag vMAJOR.MINOR.PATCH"
            assertTrue(message.startsWith(sentence), message)
        }
    }

    // A real repository and a real git, run through the provider the plugin runs it with.
    @Test
    fun `over a repository, the version is the nearest release tag, or the unreleased one with none`() {
        git("init", "--quiet")
        commit("first")
        assertEquals(UNRELEASED_VERSION_NAME, nearestVersionName())
        git("tag", "v1.2.3")
        assertEquals("1.2.3", nearestVersionName())
        commit("second")
        assertEquals("1.2.3", nearestVersionName())
        git("tag", "-a", "v1.3.0", "-m", "v1.3.0")
        assertEquals("1.3.0", nearestVersionName())
        // Describe never matches a tag its glob does not.
        git("tag", "build-7")
        assertEquals("1.3.0", nearestVersionName())
    }

    @Test
    fun `over a repository whose nearest tag the glob matches but is no release tag, the build fails`() {
        git("init", "--quiet")
        commit("first")
        git("tag", "v1.2.3")
        commit("second")
        git("tag", "v1.2.4-rc.1")
        val message = assertThrows<GradleException> { nearestVersionName() }.message.orEmpty()
        assertTrue(message.startsWith("The nearest tag, 'v1.2.4-rc.1', is not a release tag"), message)
    }

    @Test
    fun `outside a git checkout the build fails and says why`() {
        val message = assertThrows<GradleException> { nearestVersionName() }.message.orEmpty()
        val sentence = "versionName is the nearest release tag, and git describe failed in $repository"
        assertTrue(message.startsWith(sentence), message)
        assertTrue("not a git repository" in message, message)
    }

    private fun nearestVersionName(): String {
        val project = ProjectBuilder.builder().withProjectDir(repository).build()
        return versionNameOf(project.providers.describeNearestReleaseTag(repository))
    }

    private fun commit(message: String) = git(*COMMITTER, "commit", "--quiet", "--allow-empty", "-m", message)

    // Bounded: git's output goes to a file, so the wait never blocks on a pipe it has not drained.
    private fun git(vararg arguments: String) {
        val log = scratch.resolve("git.log")
        val process =
            ProcessBuilder(listOf("git", "-C", repository.path) + arguments)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${arguments.toList()} did not finish in $GIT_TIMEOUT_SECONDS s")
        }
        check(process.exitValue() == 0) { "git ${arguments.toList()} failed: ${log.readText()}" }
    }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
        val COMMITTER = arrayOf("-c", "user.name=Probe", "-c", "user.email=probe@example.com")
    }
}
