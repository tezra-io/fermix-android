package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The phone app: Compose, the shared Android settings, the version from version.properties,
 * signing from outside the repository, and an R8-shrunk release that is never debuggable.
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
            }
            configureKotlinAndroid()
            lintReleaseInCheck()
            if (debugKey == null) refuseDebugPackagingWithoutKey(signing.propertiesFile)
        }
    }
}
