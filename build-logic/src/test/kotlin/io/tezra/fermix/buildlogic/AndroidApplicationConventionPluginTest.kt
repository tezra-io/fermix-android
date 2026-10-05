package io.tezra.fermix.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidApplicationConventionPluginTest {
    @TempDir
    lateinit var projectDir: File

    // The probe is a git repository of one commit, as the versionName is its nearest release tag, if any. Its build
    // runs in the worker's environment, which holds no git variable of the caller's (build-logic's build script),
    // without the caller's FERMIX_ signing keys, as one described in part fails the build, and with [environment]
    // over it.
    private fun configured(
        tag: String? = null,
        environment: Map<String, String> = emptyMap(),
    ): List<String> {
        projectDir.resolve("gradle").mkdirs()
        File("../gradle/libs.versions.toml").copyTo(projectDir.resolve("gradle/libs.versions.toml"))
        File("../version.properties").copyTo(projectDir.resolve("version.properties"))
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"probe\"\n")
        projectDir.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        projectDir.resolve("build.gradle.kts").writeText(PROBE_BUILD)
        git("init", "--quiet")
        git("commit", "--quiet", "--allow-empty", "-m", "probe")
        if (tag != null) git("tag", tag)
        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withEnvironment(System.getenv().filterKeys { !it.startsWith("FERMIX_") } + environment)
                .withArguments("--warning-mode=fail", "help")
                .build()
        return result.output.lines()
    }

    private fun git(vararg arguments: String) =
        scratchGit(projectDir, projectDir.resolve("build/git.log").also { it.parentFile.mkdirs() }, *arguments)

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
        assertTrue("version: $UNRELEASED_VERSION_NAME, code $repositoryVersionCode" in printed, printed.toString())
    }

    // CI/CD design section 4.4: the name is the tag without its v, the code version.properties' own.
    @Test
    fun `an app takes its versionName from the nearest release tag and its versionCode from version properties`() {
        val printed = configured(tag = "v3.4.5")
        assertTrue("version: 3.4.5, code $repositoryVersionCode" in printed, printed.toString())
    }

    // Git exports GIT_DIR to a hook or an alias it runs in a linked worktree, and GIT_DIR wins over -C: the version
    // is still the build's own checkout's, never the caller's repository's.
    @Test
    fun `an app takes its version from its own checkout whatever repository GIT_DIR names`(
        @TempDir other: File,
    ) {
        val log = other.resolve("git.log")
        scratchGit(other, log, "init", "--quiet")
        scratchGit(other, log, "commit", "--quiet", "--allow-empty", "-m", "other")
        scratchGit(other, log, "tag", "v9.9.9")
        val printed = configured(tag = "v3.4.5", environment = mapOf("GIT_DIR" to other.resolve(".git").path))
        assertTrue("version: 3.4.5, code $repositoryVersionCode" in printed, printed.toString())
    }

    private val repositoryVersionCode by lazy { parseVersionCode(File("../version.properties").readText()) }
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
        println("version: " + android.defaultConfig.versionName + ", code " + android.defaultConfig.versionCode)
    }
    """.trimIndent()
