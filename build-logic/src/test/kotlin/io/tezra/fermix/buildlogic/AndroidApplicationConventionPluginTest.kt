package io.tezra.fermix.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidApplicationConventionPluginTest {
    @TempDir
    lateinit var projectDir: File

    private fun configured(): List<String> {
        projectDir.resolve("gradle").mkdirs()
        File("../gradle/libs.versions.toml").copyTo(projectDir.resolve("gradle/libs.versions.toml"))
        File("../version.properties").copyTo(projectDir.resolve("version.properties"))
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

    // A real build of an app that applies the plugin, configured only, on the repository's own catalog and
    // version file (AndroidComposeLibraryConventionPluginTest).
    @Test
    fun `an app with instrumented tests runs them on what a Compose library's run on and builds them in check`() {
        projectDir.resolve(INSTRUMENTED_TEST_SOURCES).mkdirs()
        val printed = configured()
        assertTrue("instrumented tests run on: $INSTRUMENTATION_RUNNER" in printed, printed.toString())
        assertTrue("runner arguments: {timeout_msec=$INSTRUMENTED_TEST_TIMEOUT_MILLIS}" in printed, printed.toString())
        assertTrue("check builds $INSTRUMENTED_TEST_APK: true" in printed, printed.toString())
        val runsOn =
            listOf(
                "androidx.compose.ui:ui-test-junit4",
                "androidx.test.espresso:espresso-core",
                "androidx.test.ext:junit",
                "androidx.test:runner",
            )
        assertTrue("runs on: $runsOn" in printed, printed.toString())
    }

    @Test
    fun `an app with none builds no test APK`() {
        val printed = configured()
        assertTrue("instrumented tests run on: null" in printed, printed.toString())
        assertTrue("runner arguments: {}" in printed, printed.toString())
        assertTrue("check builds $INSTRUMENTED_TEST_APK: false" in printed, printed.toString())
        assertTrue("runs on: []" in printed, printed.toString())
    }
}

private val PROBE_BUILD =
    """
    plugins {
        id("fermix.android.application")
    }

    extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
        namespace = "io.tezra.fermix.probe"
    }

    afterEvaluate {
        val android = extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
        val check = tasks.getByName("check")
        println("instrumented tests run on: " + android.defaultConfig.testInstrumentationRunner)
        println("runner arguments: " + android.defaultConfig.testInstrumentationRunnerArguments)
        println("check builds $INSTRUMENTED_TEST_APK: " + ("$INSTRUMENTED_TEST_APK" in check.dependsOn))
        val declared = configurations.getByName("androidTestImplementation").dependencies
        println("runs on: " + declared.map { it.group + ":" + it.name }.filter { it != "androidx.compose:compose-bom" }.sorted())
    }
    """.trimIndent()
