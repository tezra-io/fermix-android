package io.tezra.fermix.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * A plain Kotlin library for code that needs nothing from Android: the gates every module has, the
 * app's Java level, and JVM tests on JUnit 5. The Java level is the API compiled against as well as
 * the bytecode's: an Android module is compiled against android.jar and linted for API levels, a JVM
 * module is not, so without it a Java 21 method would compile here and fail on a phone.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply(QualityConventionPlugin::class.java)

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.allWarningsAsErrors.set(true)
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(JAVA_VERSION.toString()))
                compilerOptions.freeCompilerArgs.add("-Xjdk-release=$JAVA_VERSION")
            }
            // A function value with a named parameter, not an Action lambda (refuseDebugPackagingWithoutKey).
            val javaApiLevel: (JavaCompile) -> Unit = { compile ->
                compile.options.release.set(JAVA_VERSION.majorVersion.toInt())
            }
            tasks.withType(JavaCompile::class.java).configureEach(javaApiLevel)
            tasks.named<Test>("test") { useJUnitPlatform() }
            addJUnit5()
        }
    }
}
