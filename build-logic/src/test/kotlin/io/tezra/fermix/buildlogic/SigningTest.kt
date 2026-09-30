package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class SigningTest {
    private val propertiesFile = File("/home/dev/.config/fermix-android/keystore.properties")

    private val releaseEnvironment =
        mapOf(
            "FERMIX_RELEASE_STORE_FILE" to "/keys/release.jks",
            "FERMIX_RELEASE_STORE_PASSWORD" to "environment store secret",
            "FERMIX_RELEASE_KEY_ALIAS" to "environment-release",
            "FERMIX_RELEASE_KEY_PASSWORD" to "environment key secret",
        )

    private val releaseProperties =
        mapOf(
            "release.storeFile" to "release.jks",
            "release.storePassword" to "file store secret",
            "release.keyAlias" to "file-release",
            "release.keyPassword" to "file key secret",
        )

    private fun inputs(
        environment: Map<String, String> = emptyMap(),
        properties: Map<String, String>? = null,
    ) = SigningInputs(
        { name -> environment[name] },
        propertiesFile,
        properties?.entries?.joinToString("\n") { (name, value) -> "$name=$value" },
    )

    private fun refusal(
        role: SigningRole,
        given: SigningInputs,
    ): String? = assertThrows<GradleException> { signingKey(role, given) }.message

    @Test
    fun `nothing described gives no key`() {
        assertNull(signingKey(SigningRole.RELEASE, inputs()))
        assertNull(signingKey(SigningRole.RELEASE, inputs(properties = emptyMap())))
    }

    @Test
    fun `each role reads only its own values`() {
        assertNull(signingKey(SigningRole.DEBUG, inputs(releaseEnvironment, releaseProperties)))
    }

    @Test
    fun `the environment wins over keystore properties`() {
        val key = signingKey(SigningRole.RELEASE, inputs(releaseEnvironment, releaseProperties))
        val expected =
            SigningKey(
                File("/keys/release.jks"),
                "environment store secret",
                "environment-release",
                "environment key secret",
            )
        assertEquals(expected, key)
    }

    @Test
    fun `keystore properties give the key when the environment describes none`() {
        val key = signingKey(SigningRole.RELEASE, inputs(properties = releaseProperties))
        val expected =
            SigningKey(
                File("/home/dev/.config/fermix-android/release.jks"),
                "file store secret",
                "file-release",
                "file key secret",
            )
        assertEquals(expected, key)
    }

    @Test
    fun `an absolute storeFile in keystore properties is kept`() {
        val properties = releaseProperties + ("release.storeFile" to "/keys/elsewhere.jks")
        val key = signingKey(SigningRole.RELEASE, inputs(properties = properties))
        assertEquals(File("/keys/elsewhere.jks"), key?.storeFile)
    }

    @Test
    fun `a relative store file in the environment fails`() {
        val environment = releaseEnvironment + ("FERMIX_RELEASE_STORE_FILE" to "release.jks")
        assertEquals(
            "FERMIX_RELEASE_STORE_FILE must be an absolute path.",
            refusal(SigningRole.RELEASE, inputs(environment)),
        )
    }

    @Test
    fun `an environment key without its store file fails and never falls back to the file`() {
        val environment = releaseEnvironment - "FERMIX_RELEASE_STORE_FILE"
        assertEquals(
            "The environment describes a signing key without FERMIX_RELEASE_STORE_FILE.",
            refusal(SigningRole.RELEASE, inputs(environment, releaseProperties)),
        )
    }

    @Test
    fun `a keystore properties key without its storeFile fails and names it`() {
        val properties = releaseProperties - "release.storeFile"
        assertEquals(
            "$propertiesFile describes a signing key without release.storeFile.",
            refusal(SigningRole.RELEASE, inputs(properties = properties)),
        )
    }

    @Test
    fun `every missing value is named`() {
        val environment = mapOf("FERMIX_DEBUG_STORE_PASSWORD" to "secret")
        assertEquals(
            "The environment describes a signing key without " +
                "FERMIX_DEBUG_STORE_FILE, FERMIX_DEBUG_KEY_ALIAS, FERMIX_DEBUG_KEY_PASSWORD.",
            refusal(SigningRole.DEBUG, inputs(environment)),
        )
    }

    @Test
    fun `a blank value counts as missing`() {
        val environment = releaseEnvironment + ("FERMIX_RELEASE_KEY_PASSWORD" to " ")
        assertEquals(
            "The environment describes a signing key without FERMIX_RELEASE_KEY_PASSWORD.",
            refusal(SigningRole.RELEASE, inputs(environment)),
        )
        val properties = releaseProperties + ("release.keyAlias" to "")
        assertEquals(
            "$propertiesFile describes a signing key without release.keyAlias.",
            refusal(SigningRole.RELEASE, inputs(properties = properties)),
        )
    }
}
