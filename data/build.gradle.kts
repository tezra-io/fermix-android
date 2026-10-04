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
    // requests, and an instance record holds core-protocol's caps and core-transport's candidates, so
    // callers compile against all three. The records' and the settings' DataStores are their stores'
    // own (InstanceStore, AppSettingsStore), so no caller compiles against DataStore.
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    implementation(libs.androidx.datastore.core)
    // The records' and the settings' storage (DataStores.kt's atomicDataStore): DataStore's own file storage
    // deletes the file before it renames the written one over it, OkioStorage renames over it in one step.
    implementation(libs.androidx.datastore.core.okio)

    testImplementation(libs.kotlinx.coroutines.test)
}
