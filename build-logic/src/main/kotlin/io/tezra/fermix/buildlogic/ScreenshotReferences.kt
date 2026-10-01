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

/** The task that fails on a reference image no preview drew; [SCREENSHOT_VALIDATION] runs it. */
internal const val ORPHAN_CHECK = "verifyNoOrphanScreenshots"

/** The variant whose previews are screenshot-tested, and the test task that draws them. */
private const val VARIANT = "debug"
private const val VARIANT_TEST = "testDebugUnitTest"

/**
 * Where Roborazzi writes its summary of the variant's run, under build/ (its RoborazziReportConst, which
 * it keeps internal). Should Roborazzi move it, the check fails on the missing file, never passes.
 */
private const val RESULTS_SUMMARY = "test-results/roborazzi/$VARIANT/results-summary.json"

/** The tasks that write reference images: Roborazzi's record, and its verify-and-record. */
private val RECORD_TASKS = listOf("recordRoborazziDebug", "verifyAndRecordRoborazziDebug")

/**
 * Roborazzi's copies from build/intermediates/roborazzi into its output directory, which here is the
 * committed references. After a test task that did not run (up to date, or from the build cache) they
 * would write build state into the source tree, and bring back a reference deleted since. A verify only
 * reads the references, and a record always runs the tests ([runEveryRecord]), which write them.
 */
private const val SHARED_RESTORE = "restoreOutputDirRoborazzi"
private val VARIANT_COPIES = listOf("restoreOutputDirRoborazziDebug", "finalizeTestRoborazziDebug")

/**
 * Keeps [SCREENSHOT_DIRECTORY] exactly what the last record drew: Roborazzi's copies from build state are
 * off, and [SCREENSHOT_VALIDATION] also fails on a reference that no preview drew.
 */
internal fun Project.guardScreenshotReferences() {
    tasks.named<Task>(SHARED_RESTORE) { enabled = false }
    tasks.register<VerifyNoOrphanScreenshots>(ORPHAN_CHECK) {
        group = "verification"
        description = "Fails on a reference image in $SCREENSHOT_DIRECTORY that no preview drew."
        references.from(layout.projectDirectory.dir(SCREENSHOT_DIRECTORY).asFileTree)
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
        tasks.named<Task>(SCREENSHOT_VALIDATION) { dependsOn(ORPHAN_CHECK) }
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
 * A record runs the tests every time, never up to date and never from the build cache, so that it
 * writes every reference afresh, and `-Proborazzi.cleanupOldScreenshots=true` deletes the stale ones.
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
 * Fails when the references hold an image that no preview drew in the verify before it: the reference of
 * a preview that is gone, was renamed, or is no longer found. Roborazzi's verify compares only the
 * previews it finds, so such a reference would look like coverage that nothing checks.
 */
@DisableCachingByDefault(because = "It produces nothing: it reads the references and the verify's results.")
abstract class VerifyNoOrphanScreenshots : DefaultTask() {
    /** The module's reference images. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val references: ConfigurableFileCollection

    /** Roborazzi's summary of the verify, which names the reference each preview was compared with. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resultsSummary: RegularFileProperty

    @TaskAction
    fun verify() {
        val results = CaptureResults.fromJsonFile(resultsSummary.get().asFile.path).captureResults
        // By name: a summary restored from the build cache holds another checkout's absolute paths.
        val drawn = results.map { result -> File(result.goldenFile).name }
        val orphans = orphanedReferences(references.files.map { file -> file.name }, drawn)
        if (orphans.isNotEmpty()) {
            throw GradleException(
                "$SCREENSHOT_DIRECTORY holds ${orphans.size} reference images that no preview drew: " +
                    "${orphans.joinToString()}. A preview that is gone, renamed or no longer found leaves " +
                    "its references behind; bring it back, or redraw with `./gradlew recordRoborazziDebug " +
                    "-Proborazzi.cleanupOldScreenshots=true`, which deletes them.",
            )
        }
    }
}

/** The references, by file name, that no preview drew, in order. */
internal fun orphanedReferences(
    references: Collection<String>,
    drawn: Collection<String>,
): List<String> = (references.toSet() - drawn.toSet()).sorted()
