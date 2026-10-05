plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.roborazzi.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.room.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.google.services.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The root build's check reaches these through build-logic's check, and CI's unit job runs
// :build-logic:test by name, because the root `test` does not reach into an included build.
tasks.test {
    useJUnitPlatform()
    // AGENTS.md: the workers run the product's git describe, in their own JVM and in the probe builds they start,
    // so they are given none of the caller's GIT_ variables, which point git at another repository's objects
    // (GIT_COMMON_DIR, GIT_OBJECT_DIRECTORY) or stop its describe short (GIT_SHALLOW_FILE), and none of the
    // machine's git configuration. Taken from the environment the task runs in, as it runs, so the configuration
    // cache stores no environment.
    doFirst {
        val test = this as Test
        test.environment = test.environment.filterKeys { !it.startsWith("GIT_") } +
            mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null")
    }
}

// The same gates the convention plugins put on every module (QualityConventionPlugin).
detekt {
    config.setFrom(file("../config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Info
    baseline.unset()
    baseline.unsetConvention()
}

ktlint {
    version = libs.versions.ktlint.get()
    baseline.unset()
    baseline.unsetConvention()
}

tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "fermix.android.application"
            implementationClass = "io.tezra.fermix.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "fermix.android.library"
            implementationClass = "io.tezra.fermix.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidComposeLibrary") {
            id = "fermix.android.library.compose"
            implementationClass = "io.tezra.fermix.buildlogic.AndroidComposeLibraryConventionPlugin"
        }
        register("androidRoomLibrary") {
            id = "fermix.android.library.room"
            implementationClass = "io.tezra.fermix.buildlogic.AndroidRoomLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "fermix.jvm.library"
            implementationClass = "io.tezra.fermix.buildlogic.JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "fermix.quality"
            implementationClass = "io.tezra.fermix.buildlogic.QualityConventionPlugin"
        }
    }
}
