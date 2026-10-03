import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type, as feature-onboarding's: AGP 9.4 types a library's `android` accessor as its
// internal implementation.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.instance"
}

dependencies {
    // The Instance screen reads a record from data's InstanceStore and its chat's settings, a session's
    // state and diagnostics from core-session, a candidate's scope from core-transport and the daemon's
    // push platforms from core-protocol, so callers compile against all of them.
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    api(project(":data"))
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(project(":design"))

    testImplementation(libs.kotlinx.coroutines.test)
}
