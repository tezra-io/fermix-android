import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.session"
}

dependencies {
    // A session takes a StaticKey and Candidates, sends ClientEvents and hands on ServerEvents, and
    // shows its state as a StateFlow, so callers compile against all four.
    api(project(":core-noise"))
    api(project(":core-protocol"))
    api(project(":core-transport"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
