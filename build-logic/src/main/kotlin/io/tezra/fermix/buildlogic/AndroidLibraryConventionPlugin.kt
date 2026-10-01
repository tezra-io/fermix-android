package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * A library module: the shared Android settings and gates, and JVM unit tests on JUnit 5. A library
 * has no targetSdk of its own, so lint and the unit tests are given the app's.
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
        }
    }
}

/** JUnit 5 from the version catalog; Gradle 9 no longer supplies the platform launcher, so it is declared too. */
private fun Project.addJUnit5() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.addProvider("testImplementation", dependencies.platform(libs.library("junit-bom")))
    dependencies.addProvider("testImplementation", libs.library("junit-jupiter"))
    dependencies.addProvider("testRuntimeOnly", libs.library("junit-platform-launcher"))
}

private fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no $alias library.") }
