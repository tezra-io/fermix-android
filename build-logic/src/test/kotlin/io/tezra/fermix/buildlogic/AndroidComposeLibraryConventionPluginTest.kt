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
    fun `a run that drew no preview, or left an image no preview drew, fails naming why`() {
        val none = previewsDrawnFailure(screenshots = listOf("a.png"), drawn = emptyList())
        assertTrue(none!!.startsWith("Roborazzi drew no preview: it found none in the module's package"), none)
        assertEquals(none, previewsDrawnFailure(screenshots = emptyList(), drawn = emptyList()))
        val left = previewsDrawnFailure(screenshots = listOf("c.png", "b.png", "a.png"), drawn = listOf("b.png"))
        val named = "$SCREENSHOT_DIRECTORY holds 2 images that no preview drew in this run: a.png, c.png."
        assertTrue(left!!.startsWith(named), left)
        assertTrue("`./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true`" in left, left)
        // A run filtered with --tests draws only the previews it names, and leaves the others' images behind.
        assertTrue("`--tests`" in left, left)
        val one = previewsDrawnFailure(screenshots = listOf("a.png", "b.png"), drawn = listOf("b.png"))
        val single = "$SCREENSHOT_DIRECTORY holds 1 image that no preview drew in this run: a.png."
        assertTrue(one!!.startsWith(single), one)
        assertEquals(null, previewsDrawnFailure(screenshots = listOf("b.png"), drawn = listOf("b.png")))
        // A preview with no image yet, a compare's "added", is drawn, not left behind.
        assertEquals(null, previewsDrawnFailure(screenshots = listOf("a.png"), drawn = listOf("a.png", "b.png")))
    }

    @Test
    fun `a compare with nothing recorded fails naming the record, and a file that is no image is not one`() {
        val nothing = "$SCREENSHOT_DIRECTORY holds no image to compare with: nothing was recorded on this machine"
        val empty = previewsDrawnFailure(screenshots = emptyList(), drawn = listOf("b.png"))
        assertTrue(empty!!.startsWith(nothing), empty)
        assertTrue("`./gradlew recordRoborazziDebug`" in empty, empty)
        // What a file manager writes beside the images, which Roborazzi's cleanup, deleting images alone, leaves.
        val strays = listOf(".directory", "Thumbs.db", "desktop.ini")
        assertEquals(empty, previewsDrawnFailure(screenshots = strays, drawn = listOf("b.png")))
        assertEquals(null, previewsDrawnFailure(screenshots = strays + "b.png", drawn = listOf("b.png")))
    }

    // A real build of one module that applies the plugin, configured and planned, never run: a ProjectBuilder
    // project has no version catalog for the plugin to read. The catalog is the repository's own.
    @Test
    fun `a module that applies it draws with Compose, records into its screenshots, and check compares none`() {
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
                .withArguments("--warning-mode=fail", "--dry-run", "check")
                .build()
        val printed = result.output.lines()
        assertTrue("compose: true" in printed, result.output)
        // No screenshot is tracked (the owner, 2026-10-05), so a clone has none to compare with: nothing check
        // runs, however it is reached, draws or reads one, and CI's screens job records. The one Roborazzi task in
        // its plan is the test's finalizer, disabled below.
        val planned = printed.filter { line -> line.endsWith(" SKIPPED") }.map { line -> line.substringBefore(" ") }
        assertTrue(":test SKIPPED" in printed, result.output)
        val drawing = planned.filter { task -> "Roborazzi" in task || task.endsWith(PREVIEWS_DRAWN) }
        assertEquals(listOf(":finalizeTestRoborazziDebug"), drawing, result.output)
        for (task in ROBORAZZI_TASKS) {
            assertTrue("$task exists: true" in printed, result.output)
            // A run that draws then checks what it drew, the record in CI's screens job among them.
            assertTrue("$task runs $PREVIEWS_DRAWN: true" in printed, result.output)
        }
        // The check reads the summary the run's test task writes, so it runs after that task; and it is no task of
        // its own to run, as alone it reads no run (no group lists it in `./gradlew tasks`).
        assertTrue("$PREVIEWS_DRAWN runs after: [testDebugUnitTest]" in printed, result.output)
        assertTrue("$PREVIEWS_DRAWN group: null" in printed, result.output)
        assertTrue("records into: $SCREENSHOT_DIRECTORY" in printed, result.output)
        for (copy in ROBORAZZI_COPIES) {
            assertTrue("$copy enabled: false" in printed, result.output)
        }
        assertTrue("instrumented tests run on: androidx.test.runner.AndroidJUnitRunner" in printed, result.output)
        assertTrue("runner arguments: {timeout_msec=$INSTRUMENTED_TEST_TIMEOUT_MILLIS}" in printed, result.output)
        assertTrue("check builds $INSTRUMENTED_TEST_APK: true" in printed, result.output)
        // The generated screenshot test is Kotlin under build/, which a test component's analysis reads.
        assertTrue("lintAnalyzeDebugUnitTest runs after: [$PREVIEW_TESTS]" in printed, result.output)
    }
}

// Roborazzi's task that writes the module's screenshot test, one per preview.
private const val PREVIEW_TESTS = "generateDebugComposePreviewRobolectricTests"

// Roborazzi's tasks for the variant: a record draws every preview, a compare and a verify read what one drew.
private val ROBORAZZI_TASKS =
    listOf("recordRoborazziDebug", "compareRoborazziDebug", "verifyRoborazziDebug", "verifyAndRecordRoborazziDebug")

// Roborazzi's copies from build state into its output directory, this machine's own references here.
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
        for (task in listOf(${ROBORAZZI_TASKS.joinToString { task -> "\"$task\"" }})) {
            println(task + " exists: " + (task in tasks.names))
            println(task + " runs $PREVIEWS_DRAWN: " + ("$PREVIEWS_DRAWN" in tasks.getByName(task).dependsOn))
        }
        val previews = tasks.getByName("$PREVIEWS_DRAWN")
        val before = previews.taskDependencies.getDependencies(previews).map { task -> task.name }.sorted()
        println("$PREVIEWS_DRAWN runs after: " + before)
        println("$PREVIEWS_DRAWN group: " + previews.group)
        val roborazzi = extensions.getByType<io.github.takahirom.roborazzi.RoborazziExtension>()
        println("records into: " + roborazzi.outputDir.get().asFile.relativeTo(projectDir).path)
        for (copy in listOf(${ROBORAZZI_COPIES.joinToString { copy -> "\"$copy\"" }})) {
            println(copy + " enabled: " + tasks.getByName(copy).enabled)
        }
        println("instrumented tests run on: " + android.defaultConfig.testInstrumentationRunner)
        println("runner arguments: " + android.defaultConfig.testInstrumentationRunnerArguments)
        println("check builds $INSTRUMENTED_TEST_APK: " + ("$INSTRUMENTED_TEST_APK" in check.dependsOn))
        val analysis = tasks.getByName("lintAnalyzeDebugUnitTest")
        val after = analysis.mustRunAfter.getDependencies(analysis).map { task -> task.name }.sorted()
        println("lintAnalyzeDebugUnitTest runs after: " + after)
    }
    """.trimIndent()
