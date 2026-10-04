import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.push"
    // The tests replay the vendored push_vectors.json itself, never a copy (design section 12.6), and as a
    // test resource it is an input of the test task, so a re-vendor reruns them.
    sourceSets.getByName("test").resources.directories +=
        layout.settingsDirectory
            .dir("contracts/mobile")
            .asFile.path
}

dependencies {
    // A push is opened with an instance record's keys (data), its agreement run by the device key (attest)
    // and its key derived with core-noise's HKDF, and its plaintext is JSON. Callers hand in records and keys,
    // so they compile against data and attest.
    api(project(":attest"))
    api(project(":data"))
    implementation(project(":core-noise"))
    implementation(libs.kotlinx.serialization.json)
}
