package io.tezra.fermix.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidRoomLibraryConventionPluginTest {
    @TempDir
    lateinit var projectDir: File

    // A real build of one module that applies the plugin, configured only, on the repository's own catalog
    // (AndroidComposeLibraryConventionPluginTest).
    @Test
    fun `every lint analysis of a Room module runs after every KSP task of the module`() {
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
        val ksp = printed.single { line -> line.startsWith("ksp: ") }.removePrefix("ksp: ")
        assertTrue("kspReleaseKotlin" in ksp, result.output)
        val analyses = printed.filter { line -> line.startsWith("analysis ") }
        // The two analyses that read the whole module directory, build/ included, among them.
        for (component in listOf("lintAnalyzeDebugUnitTest", "lintAnalyzeDebugAndroidTest")) {
            assertTrue(analyses.any { line -> line.startsWith("analysis $component ") }, result.output)
        }
        for (analysis in analyses) {
            assertEquals(ksp, analysis.substringAfter(" runs after: "), analysis)
        }
    }
}

// afterEvaluate runs after the Android Gradle plugin's own, which creates the variants and with them the
// lint and KSP tasks.
private val PROBE_BUILD =
    """
    plugins {
        id("fermix.android.library.room")
    }

    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "io.tezra.fermix.probe"
    }

    afterEvaluate {
        val ksp = tasks.names.filter { name -> Regex("ksp.*Kotlin").matches(name) }.sorted()
        println("ksp: " + ksp)
        for (name in tasks.names.filter { name -> Regex("lint(Vital)?Analyze.*").matches(name) }) {
            val analysis = tasks.getByName(name)
            val after = analysis.mustRunAfter.getDependencies(analysis).map { task -> task.name }.sorted()
            println("analysis " + name + " runs after: " + after)
        }
    }
    """.trimIndent()
