import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.demo"
    // A release that took this module would keep every class of it under its own name (consumer-rules.pro), so
    // check 10 of scripts/check_release_policy.sh, which reads the release's dex for the package, would see it.
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    // The demo is a daemon behind core-session's own boundary: it hands the app a Dialer whose Links it
    // answers, as core-protocol's events sealed by Noise, so callers compile against core-session's surface.
    api(project(":core-session"))

    testImplementation(libs.kotlinx.coroutines.test)
}
