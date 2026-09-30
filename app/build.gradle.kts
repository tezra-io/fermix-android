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
}
