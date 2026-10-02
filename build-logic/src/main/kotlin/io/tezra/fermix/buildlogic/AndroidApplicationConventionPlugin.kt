package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * The phone app: Compose, the shared Android settings, the version from version.properties,
 * signing from outside the repository, an R8-shrunk release that is never debuggable, and unit tests on
 * JUnit 5 and on Robolectric, as a Compose library's, for what only the app does: its window, its
 * activity's lifecycle and its foreground service.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply(QualityConventionPlugin::class.java)

            val versionFile = rootProject.layout.projectDirectory.file("version.properties")
            val version = parseAppVersion(providers.fileContents(versionFile).asText.get())
            val signing = readSigningInputs()
            val debugKey = signingKey(SigningRole.DEBUG, signing)
            val releaseKey = signingKey(SigningRole.RELEASE, signing)
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val robolectricSdk = robolectricSdk(libs)

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon()
                buildFeatures.compose = true
                defaultConfig.targetSdk = TARGET_SDK
                defaultConfig.versionCode = version.code
                defaultConfig.versionName = version.name
                configureSigning(debugKey, releaseKey)
                // A named receiver, not an Action lambda, for detekt's type resolution: see
                // refuseDebugPackagingWithoutKey in Signing.kt.
                val release = buildTypes.getByName("release")
                release.isDebuggable = false
                release.isMinifyEnabled = true
                release.isShrinkResources = true
                release.proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                // androidx.graphics:graphics-path ships this library already stripped for every ABI.
                // Packaged as it is, it needs no NDK to strip it again and the build reports nothing.
                packaging.jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
                // Robolectric starts the app's own activity and service, from its merged manifest.
                testOptions.unitTests.isIncludeAndroidResources = true
                // A function value with a named parameter, not an Action lambda (Signing.kt).
                testOptions.unitTests.all { test ->
                    test.useJUnitPlatform()
                    configureRobolectric(test, robolectricSdk)
                }
            }
            addJUnit5()
            addRobolectric(libs)
            configureKotlinAndroid()
            lintReleaseInCheck()
            if (debugKey == null) refuseDebugPackagingWithoutKey(signing.propertiesFile)
        }
    }
}
