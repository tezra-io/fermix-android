package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidComposeLibraryConventionPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `Robolectric is pointed at the directory of the runtime jar Gradle resolved`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val jar = projectDir.resolve("files/android-all-instrumented-16.jar")
        assertEquals(
            listOf("-Drobolectric.dependency.dir=${jar.parentFile}"),
            RobolectricSdkDirectory(project.files(jar)).asArguments(),
        )
    }

    @Test
    fun `a module with no namespace is refused, since its previews are found by it`() {
        assertEquals("io.tezra.fermix.design", requireNamespace("io.tezra.fermix.design", ":design"))
        val refusal = assertThrows<GradleException> { requireNamespace(null, ":feature-chat") }
        assertEquals(":feature-chat sets no android namespace; its previews are found by it.", refusal.message)
    }

    @Test
    fun `a reference that no preview drew in the verify is an orphan, named in order`() {
        val references = listOf("c.png", "b.png", "a.png")
        assertEquals(listOf("a.png", "c.png"), orphanedReferences(references, drawn = listOf("b.png")))
        // A preview with no reference yet is the verify's own failure, not an orphan.
        assertEquals(emptyList<String>(), orphanedReferences(listOf("b.png"), drawn = listOf("b.png", "d.png")))
    }

    // A real build of one module that applies the plugin, configured only: a ProjectBuilder project
    // has no version catalog for the plugin to read. The catalog is the repository's own.
    @Test
    fun `a module that applies it draws with Compose and validates its screenshots in check`() {
        projectDir.resolve("gradle").mkdirs()
        File("../gradle/libs.versions.toml").copyTo(projectDir.resolve("gradle/libs.versions.toml"))
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"probe\"\n")
        projectDir.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        projectDir.resolve("build.gradle.kts").writeText(PROBE_BUILD)
        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("--warning-mode=fail", "help")
                .build()
        val printed = result.output.lines()
        assertTrue("compose: true" in printed, result.output)
        assertTrue("check runs $SCREENSHOT_VALIDATION: true" in printed, result.output)
        assertTrue("$SCREENSHOT_VALIDATION exists: true" in printed, result.output)
        assertTrue("$SCREENSHOT_VALIDATION runs $ORPHAN_CHECK: true" in printed, result.output)
        for (copy in ROBORAZZI_COPIES) {
            assertTrue("$copy enabled: false" in printed, result.output)
        }
        assertTrue("instrumented tests run on: androidx.test.runner.AndroidJUnitRunner" in printed, result.output)
        assertTrue("check builds $INSTRUMENTED_TEST_APK: true" in printed, result.output)
    }
}

// Roborazzi's copies from build state into its output directory, the committed references here.
private val ROBORAZZI_COPIES =
    listOf("restoreOutputDirRoborazzi", "restoreOutputDirRoborazziDebug", "finalizeTestRoborazziDebug")

// afterEvaluate runs after the Android Gradle plugin's own, which creates the variants and with them
// Roborazzi's tasks.
private val PROBE_BUILD =
    """
    plugins {
        id("fermix.android.library.compose")
    }

    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "io.tezra.fermix.probe"
    }

    afterEvaluate {
        val android = extensions.getByType<com.android.build.api.dsl.LibraryExtension>()
        val check = tasks.getByName("check")
        println("compose: " + android.buildFeatures.compose)
        println("check runs $SCREENSHOT_VALIDATION: " + ("$SCREENSHOT_VALIDATION" in check.dependsOn))
        println("$SCREENSHOT_VALIDATION exists: " + ("$SCREENSHOT_VALIDATION" in tasks.names))
        val verify = tasks.getByName("$SCREENSHOT_VALIDATION")
        println("$SCREENSHOT_VALIDATION runs $ORPHAN_CHECK: " + ("$ORPHAN_CHECK" in verify.dependsOn))
        for (copy in listOf(${ROBORAZZI_COPIES.joinToString { copy -> "\"$copy\"" }})) {
            println(copy + " enabled: " + tasks.getByName(copy).enabled)
        }
        println("instrumented tests run on: " + android.defaultConfig.testInstrumentationRunner)
        println("check builds $INSTRUMENTED_TEST_APK: " + ("$INSTRUMENTED_TEST_APK" in check.dependsOn))
    }
    """.trimIndent()
