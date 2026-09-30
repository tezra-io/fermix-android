package io.tezra.fermix.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.RegularFileProperty
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * The type-resolved detekt tasks. They run the rules that need the compile classpath, which the plain
 * `detekt` task skips: UnusedVariable among them, since Kotlin 2 no longer warns about an unused local.
 */
private val DETEKT_FULL_ANALYSIS = setOf("detektMain", "detektTest")

/**
 * detekt and ktlint in `check`: each plugin wires its own tasks, and this adds detekt's type-resolved
 * ones. Any finding fails the build, and neither tool reads a baseline (CI/CD design C11).
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("dev.detekt")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            // named<Task> takes a Kotlin receiver lambda, which detekt's type resolution sees; an Action
            // lambda's receiver it does not (refuseDebugPackagingWithoutKey in Signing.kt).
            pluginManager.withPlugin("lifecycle-base") {
                tasks.named<Task>("check") { dependsOn(tasks.named { name -> name in DETEKT_FULL_ANALYSIS }) }
            }
            val ktlintVersion =
                extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findVersion("ktlint")
                    .orElseThrow { GradleException("gradle/libs.versions.toml has no ktlint version.") }
                    .requiredVersion
            extensions.configure<DetektExtension> {
                config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
                buildUponDefaultConfig.set(true)
                failOnSeverity.set(FailOnSeverity.Info)
                baseline.refuse()
            }
            extensions.configure<KtlintExtension> {
                version.set(ktlintVersion)
                baseline.refuse()
            }
        }
    }
}

/** Each tool has a default baseline file it would read if one appeared; neither may read any. */
private fun RegularFileProperty.refuse() {
    unset()
    unsetConvention()
}
