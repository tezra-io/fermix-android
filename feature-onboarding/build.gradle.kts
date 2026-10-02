import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.onboarding"
    // The previews' SAS is held to the vendored noise_vectors.json itself, never a copy (design section
    // 12.6), and as a test resource it is an input of the test task, so a re-vendor reruns the check.
    sourceSets.getByName("test").resources.directories +=
        layout.settingsDirectory
            .dir("contracts/mobile")
            .asFile.path
    // The fake ceremony, records and clip, which the JVM tests and the instrumented tests both drive.
    sourceSets.getByName("test").kotlin.directories += "src/sharedTest/kotlin"
    sourceSets.getByName("androidTest").kotlin.directories += "src/sharedTest/kotlin"
}

dependencies {
    // The ViewModel takes the hardware gate's result, starts core-session's pairing with core-protocol's
    // link, reads core-transport's network facts and commits the record to data's InstanceStore, and the
    // entries are built over Navigation 3's EntryProviderScope, so callers compile against all of them.
    api(project(":attest"))
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    api(project(":data"))
    api(libs.androidx.navigation3.runtime)
    api(libs.androidx.lifecycle.viewmodel.compose)
    implementation(project(":design"))
    // The notification and camera permissions are asked for through an activity result.
    implementation(libs.androidx.activity.compose)
    // The scan (design sections 12.1 and 13.3): CameraX's preview and analysis bound to the screen's
    // lifecycle, and zxing-cpp reading each frame in process.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.cpp.android)

    testImplementation(libs.kotlinx.coroutines.test)
    // CodeAndCountdownTest reads the previews' SAS from the vendored noise_vectors.json.
    testImplementation(libs.kotlinx.serialization.json)
    // The instrumented tests show the screens in Navigation 3's NavDisplay, as the app does.
    androidTestImplementation(libs.androidx.navigation3.ui)
}
