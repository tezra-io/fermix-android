plugins {
    id("fermix.android.application")
}

android {
    namespace = "io.tezra.fermix"
    defaultConfig {
        applicationId = "io.tezra.fermix"
    }
    // The release's own R8 rules (proguard-rules.pro): a class of the debug app's demo that reached a release
    // keeps its name, so the release policy's check 10 sees it.
    buildTypes.getByName("release").proguardFiles("proguard-rules.pro")
    // The ABIs the app's native libraries ship for, iroh's 14 to 16 MB a piece among them (core-transport's rail):
    // arm64-v8a, the phones' 64-bit ARM, and x86_64, the emulators' and the one lint asks of a build that names its
    // ABIs (ChromeOsAbiSupport). armeabi-v7a and x86, 25 MB of iroh's alone, are left out; per-ABI splits would
    // change the release pipeline's one APK (README's departures).
    defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    // iroh's JVM jar, which its Android AAR depends on, carries the desktops' libraries as resources, 72 MB that no
    // phone loads: the AAR's jni libraries are the app's.
    packaging.resources.excludes += setOf("win32-x86-64/**", "linux-aarch64/**", "linux-x86-64/**", "darwin-aarch64/**")
    // LauncherIconTest holds the icon and the splash to design's vendored mark, and as a test resource the
    // mark is an input of the test task, so a changed byte or a re-vendor reruns it, which an up-to-date or
    // cached result would pass.
    val mark =
        layout.settingsDirectory
            .dir("design/mark")
            .asFile.path
    sourceSets
        .getByName("test")
        .resources.directories
        .add(mark)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // The app shows the back stack with Navigation 3's NavDisplay (design section 12.1), onboarding's
    // entries over its ViewModel, in the design's theme.
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // The App lock setting asks again whether the phone can hold the lock each time it is resumed.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":design"))
    implementation(project(":feature-onboarding"))
    // The Chats list, the trust states, the app lock's screens and the conversations (feature-chats), a
    // chat (feature-chat), and the Instance screen (feature-instance), over the sessions the app keeps.
    implementation(project(":feature-chats"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-instance"))
    // The sessions are put aside when the process leaves sight (design section 12.5).
    implementation(libs.androidx.lifecycle.process)
    // What onboarding runs on, which the app makes: the records (data), the device keys and the
    // hardware gate (attest), and the pairing's dialer and parts (core-session).
    implementation(project(":attest"))
    implementation(project(":core-session"))
    implementation(project(":data"))
    // A push's envelope, keys, trial and plaintext (push), and FCM, which delivers it (design section 10).
    implementation(project(":push"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // The demo (README, "The demo"): the debug app's alone, which its own application and launcher entry in
    // src/debug wire in. The release never takes it; scripts/check_release_policy.sh holds its APK to that.
    debugImplementation(project(":demo-daemon"))

    // The device tests' daemon end of the iroh rail: an iroh endpoint of their own (IrohTestDaemon). JNA at the
    // catalog's 16 KB aligned version, as core-transport takes it, which its constraint does not carry here.
    androidTestImplementation(libs.iroh.android)
    constraints { androidTestImplementation(libs.jna) }

    // MainActivityTest starts the activity under Compose's test rule, on Robolectric.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The supervisor's and the navigator's tests run on virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
}
