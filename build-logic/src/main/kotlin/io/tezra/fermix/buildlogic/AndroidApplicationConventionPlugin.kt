package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

/**
 * The phone app: Compose, the shared Android settings, the versionName from the nearest release tag and the
 * versionCode from version.properties ([appVersion]), signing from outside the repository, an R8-shrunk release
 * that is never debuggable, and unit tests on JUnit 5 and on Robolectric, as a Compose library's, for what only
 * the app does: its window, its activity's lifecycle and its foreground service, with the bundled SQLite library
 * its screens' databases open on. Google's services plugin reads the Firebase project from `google-services.json`
 * into the app's resources (design section 10): the stub with a placeholder project in `app/`, or a developer's or
 * the release's own file in `app/src/<build type>/`, which the plugin reads first and git never takes.
 * Instrumented tests in [INSTRUMENTED_TEST_SOURCES], when the app has any, run on what a Compose library's run on,
 * and `check` builds their APK ([INSTRUMENTED_TEST_APK]).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("com.google.gms.google-services")
            pluginManager.apply(QualityConventionPlugin::class.java)

            val versionFile = rootProject.layout.projectDirectory.file("version.properties")
            val version = providers.appVersion(rootDir, providers.fileContents(versionFile).asText.get())
            val signing = readSigningInputs()
            val debugKey = signingKey(SigningRole.DEBUG, signing)
            val releaseKey = signingKey(SigningRole.RELEASE, signing)
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val robolectricSdk = robolectricSdk(libs)
            // The app's tests draw the Chats list, which reads the data module's Room databases on the
            // bundled SQLite driver, as a Room module's tests do (BundledSqliteNative.kt).
            val sqliteNative = registerSqliteNative(libs)
            val instrumented = file(INSTRUMENTED_TEST_SOURCES).isDirectory

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon()
                buildFeatures.compose = true
                defaultConfig.targetSdk = TARGET_SDK
                defaultConfig.versionCode = version.code
                defaultConfig.versionName = version.name
                if (instrumented) defaultConfig.runsInstrumentedTests()
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
                    loadSqliteNative(test, sqliteNative)
                }
            }
            addJUnit5()
            addRobolectric(libs)
            if (instrumented) {
                addComposeInstrumentedTests(libs)
                tasks.named<Task>("check") { dependsOn(INSTRUMENTED_TEST_APK) }
            }
            configureKotlinAndroid()
            lintReleaseInCheck()
            if (debugKey == null) refuseDebugPackagingWithoutKey(signing.propertiesFile)
        }
    }
}
