package io.tezra.fermix.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

// The bundled fonts are google/fonts' files, unmodified, as SOURCE.json records them. Unit tests run
// in the module's directory, where SOURCE.json's paths start.
class FontSourceTest {
    private val source = Json.parseToJsonElement(File("src/main/assets/fonts/SOURCE.json").readText()).jsonObject
    private val files: List<JsonObject> =
        source
            .getValue("fonts")
            .jsonArray
            .flatMap { font -> font.jsonObject.getValue("files").jsonArray }
            .map { it.jsonObject }

    @Test
    fun `the fonts come from one commit of google fonts`() {
        val upstream = source.getValue("upstream").jsonObject
        assertEquals("google/fonts", upstream.getValue("repository").jsonPrimitive.content)
        assertTrue(Regex("[0-9a-f]{40}").matches(upstream.getValue("commit").jsonPrimitive.content))
    }

    @Test
    fun `every vendored file has its recorded digest`() {
        for (file in files) {
            val path = file.getValue("path").jsonPrimitive.content
            assertEquals(file.getValue("sha256").jsonPrimitive.content, sha256(File(path)), path)
        }
    }

    @Test
    fun `res font holds the recorded fonts and nothing else`() {
        val recorded =
            files.map { it.getValue("path").jsonPrimitive.content }.filter { it.startsWith("src/main/res/font/") }
        val present = File("src/main/res/font").listFiles().orEmpty().map { "src/main/res/font/${it.name}" }
        assertEquals(recorded.toSet(), present.toSet())
    }

    @Test
    fun `both fonts are under the OFL and were not modified`() {
        for (font in source.getValue("fonts").jsonArray.map { it.jsonObject }) {
            assertEquals("OFL-1.1", font.getValue("license").jsonPrimitive.content)
            assertEquals("false", font.getValue("modified").jsonPrimitive.content)
        }
    }

    private fun sha256(file: File): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
}
