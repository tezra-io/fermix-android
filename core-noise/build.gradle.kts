import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.noise"
    // The tests replay the vendored noise_vectors.json itself, never a copy (design section 12.6),
    // and as a test resource it is an input of the test task, so a re-vendor reruns them.
    sourceSets.getByName("test").resources.directories +=
        layout.settingsDirectory
            .dir("contracts/mobile")
            .asFile.path
}
