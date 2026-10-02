import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.data"
    // The tests read the vendored fixtures and vectors themselves, never a copy (design section 12.6),
    // and as a test resource they are an input of the test task, so a re-vendor reruns them.
    sourceSets.getByName("test").resources.directories +=
        layout.settingsDirectory
            .dir("contracts/mobile")
            .asFile.path
}

dependencies {
    // RoomSessionStore is core-session's SessionStore over core-session's rows and core-protocol's
    // requests, an instance record holds core-protocol's caps and core-transport's candidates, and
    // InstanceStore keeps its records in a DataStore the app makes, so callers compile against all four.
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    api(libs.androidx.datastore.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
