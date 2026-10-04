package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

/** Where a module keeps its instrumented tests; a library module that has them builds them in `check`. */
internal const val INSTRUMENTED_TEST_SOURCES = "src/androidTest"

/**
 * A library module: the shared Android settings and gates, JVM unit tests on JUnit 5, and its instrumented
 * tests when it has any ([INSTRUMENTED_TEST_SOURCES]). A library has no targetSdk of its own, so lint and the
 * unit tests are given the app's.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply(QualityConventionPlugin::class.java)

            extensions.configure<LibraryExtension> {
                configureAndroidCommon()
                lint.targetSdk = TARGET_SDK
                testOptions.targetSdk = TARGET_SDK
                // A function value with a named parameter, not an Action lambda (Signing.kt).
                testOptions.unitTests.all { test -> test.useJUnitPlatform() }
            }
            configureKotlinAndroid()
            lintReleaseInCheck()
            addJUnit5()
            if (file(INSTRUMENTED_TEST_SOURCES).isDirectory) addInstrumentedTests()
        }
    }
}

/**
 * A library's instrumented tests (CI/CD design section 3, `ui`): AndroidX Test's runner and its JUnit 4
 * runner class, and `check` builds their APK ([INSTRUMENTED_TEST_APK]), so they compile under the module's
 * gates and every library they run on is verified. A module that draws adds Compose's test rule and
 * Espresso to the same (AndroidComposeLibraryConventionPlugin), and builds the APK whether it has tests or not.
 */
private fun Project.addInstrumentedTests() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    extensions.getByType<LibraryExtension>().defaultConfig.testInstrumentationRunner =
        "androidx.test.runner.AndroidJUnitRunner"
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-test-runner"))
    dependencies.addProvider("androidTestImplementation", libs.library("androidx-test-ext-junit"))
    tasks.named<Task>("check") { dependsOn(INSTRUMENTED_TEST_APK) }
}

/** JUnit 5 from the version catalog; Gradle 9 no longer supplies the platform launcher, so it is declared too. */
internal fun Project.addJUnit5() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.addProvider("testImplementation", dependencies.platform(libs.library("junit-bom")))
    dependencies.addProvider("testImplementation", libs.library("junit-jupiter"))
    dependencies.addProvider("testRuntimeOnly", libs.library("junit-platform-launcher"))
}

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no $alias library.") }
