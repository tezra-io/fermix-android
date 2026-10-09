import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.session"
    // The pairing tests hold their SAS derivation to the vendored noise_vectors.json itself, never a copy
    // (design section 12.6), and as a test resource it is an input of the test task, so a re-vendor reruns them.
    sourceSets.getByName("test").resources.directories +=
        layout.settingsDirectory
            .dir("contracts/mobile")
            .asFile.path
}

dependencies {
    // A session takes a StaticKey and Candidates, sends ClientEvents and hands on ServerEvents, and
    // shows its state as a StateFlow, so callers compile against all four; a pairing takes attest's
    // DeviceKeyFacade, so callers compile against that too.
    api(project(":attest"))
    api(project(":core-noise"))
    api(project(":core-protocol"))
    api(project(":core-transport"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    // The tests' daemon frames, seals and answers a handshake as the demo daemon does, with its code: the
    // in-memory link, the responder and the frames are written once, there (demo-daemon's README section).
    testImplementation(project(":demo-daemon"))
}
