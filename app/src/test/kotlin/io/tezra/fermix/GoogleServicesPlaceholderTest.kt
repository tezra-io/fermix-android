package io.tezra.fermix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Where to put a real google-services.json, which the placeholder must never be replaced by (AGENTS.md). */
private const val WHERE_A_REAL_ONE_GOES =
    "app/google-services.json is the placeholder project's and is tracked: put a real one in app/src/debug/ " +
        "or app/src/release/, which .gitignore keeps out, and restore the placeholder"

/**
 * The one google-services.json in the tree is the placeholder project's (CI/CD design section 3; AGENTS.md):
 * pull requests build with it, and only the release build gets the real file, from the `release` environment.
 * A developer who drops their own project's file at Firebase's default path replaces a tracked file, so the
 * build refuses it here, naming where a real one goes. The test runs from the app module's directory.
 */
class GoogleServicesPlaceholderTest {
    @Test
    fun `app's google-services json names the placeholder project, its number, app id and key all zeros`() {
        val file = File("google-services.json")
        assertTrue(file.isFile, "${file.absolutePath} is missing: $WHERE_A_REAL_ONE_GOES")
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val project = json.getValue("project_info").jsonObject
        val client =
            json
                .getValue("client")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("fermix-placeholder", project.text("project_id"), WHERE_A_REAL_ONE_GOES)
        assertTrue(project.text("project_number").all { it == '0' }, WHERE_A_REAL_ONE_GOES)
        val appId = client.getValue("client_info").jsonObject.text("mobilesdk_app_id")
        assertEquals("1:000000000000:android:0000000000000000", appId, WHERE_A_REAL_ONE_GOES)
        val keys = client.getValue("api_key").jsonArray.map { it.jsonObject.text("current_key") }
        assertTrue(keys.all { it.removePrefix("AIzaSyA").all { digit -> digit == '0' } }, WHERE_A_REAL_ONE_GOES)
    }
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
