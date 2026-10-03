import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type, as feature-onboarding's: AGP 9.4 types a library's `android` accessor as its
// internal implementation.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.chats"
    // The records and rows the JVM tests and the instrumented tests both draw.
    sourceSets.getByName("test").kotlin.directories += "src/sharedTest/kotlin"
    sourceSets.getByName("androidTest").kotlin.directories += "src/sharedTest/kotlin"
}

dependencies {
    // A row is a record of data's, its session's link as feature-instance reads it from core-session, and
    // its last message, draft and unread count from the profile's database; feature-instance also draws the
    // avatar and holds the rename and unpair dialogs the long-press menu opens.
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    api(project(":data"))
    api(project(":design"))
    api(project(":feature-instance"))
    api(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    // The instrumented tests' host activity draws the screens with activity-compose, as the app's does.
    androidTestImplementation(libs.androidx.activity.compose)
}
