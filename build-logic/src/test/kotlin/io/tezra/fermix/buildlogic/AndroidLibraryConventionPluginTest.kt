package io.tezra.fermix.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidLibraryConventionPluginTest {
    @TempDir
    lateinit var projectDir: File

    private fun configured(): List<String> {
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
        return result.output.lines()
    }

    // A real build of one module that applies the plugin, configured only, on the repository's own catalog
    // (AndroidComposeLibraryConventionPluginTest).
    @Test
    fun `a library with instrumented tests runs them on AndroidX Test's runner and builds them in check`() {
        projectDir.resolve(INSTRUMENTED_TEST_SOURCES).mkdirs()
        val printed = configured()
        assertTrue("instrumented tests run on: androidx.test.runner.AndroidJUnitRunner" in printed, printed.toString())
        assertTrue("check builds $INSTRUMENTED_TEST_APK: true" in printed, printed.toString())
        assertTrue("runs on the test runner: true" in printed, printed.toString())
    }

    @Test
    fun `a library with none builds no test APK`() {
        val printed = configured()
        assertTrue("instrumented tests run on: null" in printed, printed.toString())
        assertTrue("check builds $INSTRUMENTED_TEST_APK: false" in printed, printed.toString())
    }
}

private val PROBE_BUILD =
    """
    plugins {
        id("fermix.android.library")
    }

    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "io.tezra.fermix.probe"
    }

    afterEvaluate {
        val android = extensions.getByType<com.android.build.api.dsl.LibraryExtension>()
        val check = tasks.getByName("check")
        println("instrumented tests run on: " + android.defaultConfig.testInstrumentationRunner)
        println("check builds $INSTRUMENTED_TEST_APK: " + ("$INSTRUMENTED_TEST_APK" in check.dependsOn))
        val declared = configurations.getByName("androidTestImplementation").dependencies
        println("runs on the test runner: " + declared.any { it.group == "androidx.test" && it.name == "runner" })
    }
    """.trimIndent()
