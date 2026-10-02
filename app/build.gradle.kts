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
    implementation(project(":design"))
    implementation(project(":feature-onboarding"))
    // What onboarding runs on, which the app makes: the records (data), the device keys and the
    // hardware gate (attest), and the pairing's dialer and parts (core-session).
    implementation(project(":attest"))
    implementation(project(":core-session"))
    implementation(project(":data"))

    // MainActivityTest starts the activity under Compose's test rule, on Robolectric.
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
