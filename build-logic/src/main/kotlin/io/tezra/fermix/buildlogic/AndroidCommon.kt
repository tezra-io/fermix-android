package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// minSdk 35 is owner decision D18; targetSdk 36 is design section 12.1. The Compose BOM the
// design names (2026.09, section 17) is built for API 37, so its libraries require compileSdk 37;
// compiling against 37 changes no runtime behaviour, which targetSdk alone opts into.
internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 35
internal const val TARGET_SDK = 36

internal val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

/** The SDK levels, the Java level and the lint gate every Android module shares. */
internal fun CommonExtension.configureAndroidCommon() {
    compileSdk = COMPILE_SDK
    defaultConfig.minSdk = MIN_SDK
    compileOptions.sourceCompatibility = JAVA_VERSION
    compileOptions.targetCompatibility = JAVA_VERSION
    // Warnings are errors and there is no baseline file (CI/CD design C11).
    lint.warningsAsErrors = true
    lint.abortOnError = true
    // These two report that a newer Gradle, plugin or library has been published. Their verdict
    // changes with upstream releases while the code stands still, so they cannot gate a build;
    // updates are a choice of their own (CI/CD design section 9, question 7).
    lint.disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
}

/**
 * `check` lints the release variant too. AGP puts only `lint` in `check`, and `lint` covers the debug
 * variant alone, so release-only sources would pass unlinted. The task is named, not matched, so a
 * build that loses it fails instead of skipping the lint.
 */
internal fun Project.lintReleaseInCheck() {
    tasks.named<Task>("check") { dependsOn("lintRelease") }
}

/** Lint's analysis tasks: one per variant and per component, and the release's vital one. */
private val LINT_ANALYSIS = Regex("lint(Vital)?Analyze.*")

/**
 * Every lint analysis of the module runs after the tasks that write Kotlin under it, [writers] by name. Lint
 * analyses a test component apart, with no main source folders, and lint then takes the whole module directory
 * as a source root (lint 32.4, UastEnvironment.Module), build/ included: its Kotlin index lists every .kt file
 * there and reads each. A writer that runs, or is restored from the build cache, beside it deletes its files
 * first, and the index fails on one it listed (FileNotFoundException). Order only: neither makes the other run.
 */
internal fun Project.lintAfterKotlinWriters(writers: (String) -> Boolean) {
    val written = tasks.named { name -> writers(name) }
    // A function value with a named parameter, not an Action lambda (Signing.kt).
    val afterWriters: (Task) -> Unit = { analysis -> analysis.mustRunAfter(written) }
    tasks.named { name -> LINT_ANALYSIS.matches(name) }.configureEach(afterWriters)
}

/** AGP compiles Kotlin itself (built-in Kotlin); every warning it reports fails the build. */
internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}
