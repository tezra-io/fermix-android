import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.transport"
    // The design requires a pinning trust manager of our own (sections 12.2 and 12.3), and this
    // check fires on the class's existence, whatever its body does. TrustAllX509TrustManager, the
    // check that catches an accept-all body, stays on. The exception is recorded in AGENTS.md.
    lint.disable += "CustomX509TrustManager"
}

dependencies {
    // A connection's messages and end, the race and the network facts are channels, deferreds and
    // flows in the public API, so callers compile against them.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // The iroh route's endpoint and streams (IrohRail): iroh's Android binding, its Kotlin over JNA, whose AAR its
    // metadata asks for, with each one's native library per Android ABI. JNA at the catalog's version, whose
    // libraries are 16 KB aligned, where the 5.15.0 the binding names has an x86_64 one that is not.
    implementation(libs.iroh.android)
    constraints { implementation(libs.jna) }

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
