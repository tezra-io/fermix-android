package io.tezra.fermix.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import com.github.takahirom.roborazzi.CaptureResults
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * The task that fails when a run of Roborazzi's drew no preview, found no image in [SCREENSHOT_DIRECTORY] to compare
 * with, or left an image there that no preview drew ([previewsDrawnFailure]). Each of [DRAWING_TASKS] runs it, and
 * `check` never does.
 */
internal const val PREVIEWS_DRAWN = "checkPreviewsDrawn"

/** The variant whose previews are screenshot-tested, and the test task that draws them. */
private const val VARIANT = "debug"
private const val VARIANT_TEST = "testDebugUnitTest"

/**
 * Where Roborazzi writes its summary of the variant's run, under build/ (its RoborazziReportConst, which it keeps
 * internal). Should Roborazzi move it, [PREVIEWS_DRAWN] fails on the missing file, never passes.
 */
private const val RESULTS_SUMMARY = "test-results/roborazzi/$VARIANT/results-summary.json"

/** The tasks that write reference images: Roborazzi's record, and its verify-and-record. */
private val RECORD_TASKS = listOf("recordRoborazziDebug", "verifyAndRecordRoborazziDebug")

/** Roborazzi's tasks that draw every preview: its record, compare, verify and verify-and-record. */
private val DRAWING_TASKS = RECORD_TASKS + listOf("compareRoborazziDebug", "verifyRoborazziDebug")

/**
 * Roborazzi's copies from build/intermediates/roborazzi into its output directory, which here is the machine's own
 * references, untracked since the owner's decision of 2026-10-05. After a test task that did not run (up to date, or
 * from the build cache) they would write build state over them, a verify's new images among it, and bring back a
 * reference deleted since. A verify and a compare only read the references, and a record always runs the tests
 * ([runEveryRecord]), which write them.
 */
private const val SHARED_RESTORE = "restoreOutputDirRoborazzi"
private val VARIANT_COPIES = listOf("restoreOutputDirRoborazziDebug", "finalizeTestRoborazziDebug")

/**
 * Keeps [SCREENSHOT_DIRECTORY] what the last record drew there, and every run that draws honest about it:
 * Roborazzi's copies from build state are off, and [PREVIEWS_DRAWN] follows each run that draws.
 */
internal fun Project.guardScreenshotReferences() {
    tasks.named<Task>(SHARED_RESTORE) { enabled = false }
    // No group: alone it reads no run of Roborazzi's, so `./gradlew tasks` does not offer it.
    tasks.register<CheckPreviewsDrawn>(PREVIEWS_DRAWN) {
        description =
            "Fails when Roborazzi drew no preview, compared with nothing recorded, or left an image in " +
            "$SCREENSHOT_DIRECTORY none drew."
        screenshots.from(layout.projectDirectory.dir(SCREENSHOT_DIRECTORY).asFileTree)
        resultsSummary.set(layout.buildDirectory.file(RESULTS_SUMMARY))
        dependsOn(VARIANT_TEST)
    }
    val components = extensions.getByType<LibraryAndroidComponentsExtension>()
    // Roborazzi registers the variant's tasks in an onVariants callback of its own, which runs first:
    // callbacks run in the order they were added, and the plugin applies Roborazzi before calling this.
    // A function value with a named parameter, not an Action lambda (Signing.kt).
    val guardVariant: (LibraryVariant) -> Unit = { _ ->
        for (copy in VARIANT_COPIES) {
            tasks.named<Task>(copy) { enabled = false }
        }
        for (drawing in DRAWING_TASKS) {
            tasks.named<Task>(drawing) { dependsOn(PREVIEWS_DRAWN) }
        }
    }
    components.onVariants(components.selector().withBuildType(VARIANT), guardVariant)
}

/**
 * Whether this build records the module's references. A record writes them into [SCREENSHOT_DIRECTORY],
 * which no task declares as an output, so Gradle cannot see that they changed since the last record.
 */
internal fun Project.recordsReferences(): Provider<Boolean> {
    val recording = objects.property(Boolean::class.java)
    val records = RECORD_TASKS.map { name -> "$path:$name" }
    // A function value with a named parameter, not an Action lambda (Signing.kt).
    val onReady: (TaskExecutionGraph) -> Unit = { graph ->
        recording.set(records.any { record -> graph.hasTask(record) })
    }
    gradle.taskGraph.whenReady(onReady)
    return recording
}

/**
 * A record runs the tests every time, never up to date and never from the build cache, so that it draws every
 * preview and writes every reference afresh: with Roborazzi's copies from build state off, a record restored from
 * the cache, as CI's `screens` job could restore one, would write none. `-Proborazzi.cleanupOldScreenshots=true`
 * deletes the references of previews that are gone.
 */
internal fun runEveryRecord(
    test: Test,
    recording: Provider<Boolean>,
) {
    test.outputs.upToDateWhen { _ -> !recording.get() }
    test.outputs.doNotCacheIf("A record writes the reference images, which are no declared output.") { _ ->
        recording.get()
    }
}

/**
 * Fails a run of Roborazzi's that drew no preview, that compared with nothing recorded, or that left in
 * [SCREENSHOT_DIRECTORY] an image of a preview it did not draw: a preview that is gone, renamed, made private or
 * moved out of the module's package is drawn nowhere, and Roborazzi passes over what it does not find, as its
 * compare passes over a reference that is not there. On a fresh checkout, CI's record among them, the first is what
 * remains to tell: a module whose previews are no longer found; on a machine that recorded before a change, the
 * last names each preview the change lost, and keeps scripts/app_shots.sh from copying one.
 */
@DisableCachingByDefault(because = "It produces nothing: it reads the screenshots and the run's results.")
abstract class CheckPreviewsDrawn : DefaultTask() {
    /** The module's screenshots, the machine's own references. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val screenshots: ConfigurableFileCollection

    /** Roborazzi's summary of the run, which names the reference each preview it drew was written to or read from. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resultsSummary: RegularFileProperty

    @TaskAction
    fun check() {
        val results = CaptureResults.fromJsonFile(resultsSummary.get().asFile.path).captureResults
        // By name: a summary restored from the build cache holds another checkout's absolute paths.
        val drawn = results.map { result -> File(checkNotNull(result.goldenFile) { "No reference in $result" }).name }
        val failure = previewsDrawnFailure(screenshots.files.map { file -> file.name }, drawn)
        if (failure != null) {
            throw GradleException(failure)
        }
    }
}

/**
 * Why a run that drew the images [drawn] names fails [PREVIEWS_DRAWN] beside [screenshots], the files in
 * [SCREENSHOT_DIRECTORY], both by file name: it drew none, the directory holds no image (a compare or a verify with
 * nothing recorded, as a record always leaves one), or it holds one the run did not draw; null when none of these.
 * Only a PNG counts: Roborazzi writes PNGs there and its cleanup deletes only images, so a file a file manager leaves
 * there (`.directory`, `Thumbs.db`) is no preview's and no cleanup's.
 */
internal fun previewsDrawnFailure(
    screenshots: Collection<String>,
    drawn: Collection<String>,
): String? {
    val images = screenshots.filter { name -> name.endsWith(".png") }.toSet()
    val left = (images - drawn.toSet()).sorted()
    return when {
        drawn.isEmpty() -> NOTHING_DRAWN
        images.isEmpty() -> NOTHING_RECORDED
        left.isEmpty() -> null
        else -> leftBehind(left)
    }
}

private const val NOTHING_DRAWN =
    "Roborazzi drew no preview: it found none in the module's package, its android namespace, where a module " +
        "that applies fermix.android.library.compose keeps its previews. A preview that is gone, private or " +
        "outside that package draws nowhere."

private const val NOTHING_RECORDED =
    "$SCREENSHOT_DIRECTORY holds no image to compare with: nothing was recorded on this machine, and git tracks " +
        "none. Record before the change with `./gradlew recordRoborazziDebug`, then compare after it."

private fun leftBehind(left: List<String>): String {
    val images = if (left.size == 1) "1 image" else "${left.size} images"
    return "$SCREENSHOT_DIRECTORY holds $images that no preview drew in this run: ${left.joinToString()}. " +
        "A preview that is gone, renamed, made private or moved out of the module's package leaves its images " +
        "behind; bring it back, or record again with `./gradlew recordRoborazziDebug " +
        "-Proborazzi.cleanupOldScreenshots=true`, which deletes them. A run filtered with `--tests` draws only the " +
        "previews it names and fails here on every other: record or compare the whole module, and never filter a " +
        "record that cleans up, which deletes the images of every preview the filter left out."
}
