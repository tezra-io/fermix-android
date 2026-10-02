import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.attest"
}

dependencies {
    // DeviceKeyFacade hands out the device key as core-noise's StaticKey, so callers compile against it.
    api(project(":core-noise"))
}
