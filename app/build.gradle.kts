plugins {
    id("fermix.android.application")
}

android {
    namespace = "io.tezra.fermix"
    defaultConfig {
        applicationId = "io.tezra.fermix"
    }
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
    // The Chats list, the trust states, the app lock's screens and the conversations (feature-chats), and
    // the Instance screen (feature-instance), over the sessions the app keeps.
    implementation(project(":feature-chats"))
    implementation(project(":feature-instance"))
    // The sessions are put aside when the process leaves sight (design section 12.5).
    implementation(libs.androidx.lifecycle.process)
    // What onboarding runs on, which the app makes: the records (data), the device keys and the
    // hardware gate (attest), and the pairing's dialer and parts (core-session).
    implementation(project(":attest"))
    implementation(project(":core-session"))
    implementation(project(":data"))

    // MainActivityTest starts the activity under Compose's test rule, on Robolectric.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The supervisor's and the navigator's tests run on virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
}
