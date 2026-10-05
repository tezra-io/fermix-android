package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.DefaultConfig
import com.android.build.api.dsl.LibraryExtension
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.process.CommandLineArgumentProvider

/**
 * The task that renders every preview of the module on Robolectric and compares each image with its
 * reference under [SCREENSHOT_DIRECTORY]. A changed or missing image fails it (CI/CD design section 3,
 * `screens`), within Roborazzi's default comparator: a pixel whose colour moves by less than 0.007 (the
 * distance between RGBA values on 0 to 1) counts as unchanged, so a token nudged by a step or two passes
 * here and fails its value test instead (FermixColorsTest pins every colour). A reference that no preview
 * drew fails it too ([ORPHAN_CHECK]). A failing test names its preview, and the twelve windows of one preview
 * share that name; the images it leaves under build/outputs/roborazzi are named for the preview, its
 * window, mode and font scale. `recordRoborazziDebug` writes the references, on Linux x86-64 only.
 */
internal const val SCREENSHOT_VALIDATION = "verifyRoborazziDebug"

/** Where a module keeps its reference images, committed with the change that moved them. */
internal const val SCREENSHOT_DIRECTORY = "src/test/screenshots"

/**
 * A library module that draws: the library convention, Compose, a screenshot test of every `@Preview` in
 * the module's package, validated by `check`, and instrumented tests, built by `check`. The design module
 * and every feature module apply it.
 */
class AndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(AndroidLibraryConventionPlugin::class.java)
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("io.github.takahirom.roborazzi")

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val robolectricSdk = robolectricSdk(libs)
            val android = extensions.getByType<LibraryExtension>()
            android.buildFeatures.compose = true
            // Robolectric reads the module's resources, the bundled fonts among them, and starts the
            // Activity the screenshot tests draw in from the merged test manifest.
            android.testOptions.unitTests.isIncludeAndroidResources = true
            val recording = recordsReferences()
            // A function value with a named parameter, not an Action lambda (Signing.kt).
            android.testOptions.unitTests.all { test ->
                configureRobolectric(test, robolectricSdk)
                runEveryRecord(test, recording)
            }
            configurePreviewScreenshots(android)
            guardScreenshotReferences()
            addComposeDependencies(libs)
            configureInstrumentedTests(android, libs)
            // The tasks are named, not matched, so a build that loses one fails instead of skipping it.
            tasks.named<Task>("check") { dependsOn(SCREENSHOT_VALIDATION, INSTRUMENTED_TEST_APK) }
        }
    }
}

/**
 * The task that builds a module's instrumented tests into their APK. `check` runs it, so `build` compiles
 * them under the gates the rest of the module passes, and resolves, and so verifies, every library they run
 * on; CI's `ui` job runs them on the emulator.
 */
internal const val INSTRUMENTED_TEST_APK = "assembleDebugAndroidTest"

/** AndroidX Test's runner, which every module's instrumented tests run on. */
internal const val INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

/**
 * How long one instrumented test may run, in milliseconds (AndroidJUnitRunner's `timeout_msec`): past it the test fails
 * by its name, with its thread's stack, and the run goes on to the next. Three times the longest test's own span (a
 * landing's minute, ShareDeviceTest), well inside CI's `ui` job's 25 minutes: a wait with no bound of its own once hung
 * a fold test for 15 minutes (Task 14c, Pixel_Fold_API_36.1: Espresso's idle after the fold, the main thread idle).
 */
internal const val INSTRUMENTED_TEST_TIMEOUT_MILLIS = 180_000L

/** Instrumented tests on AndroidX Test's runner, each bounded in time ([INSTRUMENTED_TEST_TIMEOUT_MILLIS]). */
internal fun DefaultConfig.runsInstrumentedTests() {
    testInstrumentationRunner = INSTRUMENTATION_RUNNER
    testInstrumentationRunnerArguments["timeout_msec"] = INSTRUMENTED_TEST_TIMEOUT_MILLIS.toString()
}

/**
 * Instrumented tests in `src/androidTest` (CI/CD design section 3, `ui`): AndroidX Test's runner, its
 * JUnit 4 runner class, and Compose's test rule, on the Espresso that runs on API 36.
 */
private fun Project.configureInstrumentedTests(
    android: LibraryExtension,
    libs: VersionCatalog,
) {
    android.defaultConfig.runsInstrumentedTests()
    addComposeInstrumentedTests(libs)
}

/**
 * What instrumented tests that draw run on: AndroidX Test's runner and its JUnit 4 runner class, Compose's test
 * rule and Espresso, for a Compose library's and the app's.
 */
internal fun Project.addComposeInstrumentedTests(libs: VersionCatalog) {
    val bom = dependencies.platform(libs.library("androidx-compose-bom"))
    dependencies.addProvider("androidTestImplementation", bom)
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-compose-ui-test-junit4"))
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-test-runner"))
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-test-ext-junit"))
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-test-espresso-core"))
}

/** Robolectric's Android runtime as Gradle resolves it: checked against verification-metadata.xml. */
internal fun Project.robolectricSdk(libs: VersionCatalog): FileCollection {
    val sdk = configurations.detachedConfiguration(dependencies.create(libs.library("robolectric-android-all").get()))
    sdk.isTransitive = false
    return sdk
}

/**
 * What Robolectric's "Running with Java 17 and higher" asks of the JVM, so that it can reach the JDK
 * internals it intercepts (java.io.FileDescriptor's, through jdk.internal.access, among them).
 */
private val ROBOLECTRIC_JVM_ARGS =
    listOf(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )

/**
 * Robolectric runs offline on the runtime Gradle verified, so a test never downloads code at run time,
 * and draws with the hardware pixel copy, which Roborazzi asks for so the images are faithful.
 */
internal fun configureRobolectric(
    test: Test,
    sdk: FileCollection,
) {
    test.jvmArgs(ROBOLECTRIC_JVM_ARGS)
    test.systemProperty("robolectric.offline", "true")
    test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    test.inputs
        .files(sdk)
        .withPropertyName("robolectricSdk")
        .withNormalizer(ClasspathNormalizer::class.java)
    test.jvmArgumentProviders.add(RobolectricSdkDirectory(sdk))
}

/** Robolectric looks the runtime jar up by its file name in this directory. */
internal class RobolectricSdkDirectory(
    // An input through Test.inputs, by content; the directory itself differs from machine to machine.
    @get:Internal val sdk: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Drobolectric.dependency.dir=${sdk.singleFile.parentFile}")
}

/**
 * One generated test per preview in the module's own package, on the targetSdk, with the reference
 * images committed under [SCREENSHOT_DIRECTORY] and a failed comparison's images under build/.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun Project.configurePreviewScreenshots(android: LibraryExtension) {
    val roborazzi = extensions.getByType<RoborazziExtension>()
    roborazzi.outputDir.set(layout.projectDirectory.dir(SCREENSHOT_DIRECTORY))
    roborazzi.compare.outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
    val previews = roborazzi.generateComposePreviewRobolectricTests
    previews.enable.set(true)
    previews.packages.set(provider { listOf(requireNamespace(android.namespace, path)) })
    previews.robolectricConfig.set(mapOf("sdk" to "[$TARGET_SDK]"))
    // The generated test is Kotlin under build/.
    lintAfterKotlinWriters { name -> PREVIEW_TESTS.matches(name) }
}

/** Roborazzi's task that writes the module's screenshot test: `generateDebugComposePreviewRobolectricTests`. */
private val PREVIEW_TESTS = Regex("generate.*ComposePreviewRobolectricTests")

/** The module's namespace, the package its previews are looked for in; a module without one fails. */
internal fun requireNamespace(
    namespace: String?,
    projectPath: String,
): String = namespace ?: throw GradleException("$projectPath sets no android namespace; its previews are found by it.")

/**
 * The Compose BOM for the module and its tests, and what the generated screenshot tests run on:
 * Roborazzi's preview tester, the preview scanner, Robolectric, Compose's JUnit 4 test rule, and JUnit 4
 * through the Vintage engine.
 */
private fun Project.addComposeDependencies(libs: VersionCatalog) {
    val bom = dependencies.platform(libs.library("androidx-compose-bom"))
    dependencies.addProvider("implementation", bom)
    dependencies.addProvider("testImplementation", bom)
    dependencies.addProvider("testImplementation", libs.library("roborazzi-compose-preview-scanner-support"))
    dependencies.addProvider("testImplementation", libs.library("composable-preview-scanner"))
    dependencies.addProvider("testImplementation", libs.library("androidx-compose-ui-test-junit4"))
    addRobolectric(libs)
}

/** Robolectric, and the JUnit 4 it runs tests on, through the JUnit Platform's Vintage engine. */
internal fun Project.addRobolectric(libs: VersionCatalog) {
    dependencies.addProvider("testImplementation", libs.library("robolectric"))
    dependencies.addProvider("testImplementation", libs.library("junit4"))
    dependencies.addProvider("testRuntimeOnly", libs.library("junit-vintage-engine"))
}
