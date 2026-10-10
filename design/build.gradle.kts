import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.design"
    // MarkGeometryTest reads the vendored mark itself, and as a test resource it is an input of the test
    // task, so a changed byte or a re-vendor reruns the test, which an up-to-date or cached result would pass.
    sourceSets
        .getByName("test")
        .resources.directories
        .add("mark")
}

// WordmarkGeometryTest reads the vendored wordmark itself, an input of the unit tests by its own path, so a
// changed byte or a re-vendor reruns them. It is no second test resource directory: AGP copies every one to
// the classpath's root and keeps the last of two files of one name without a word, so its SOURCE.json would
// stand for mark's, which would no longer be an input (MarkGeometryTest fails on that).
tasks.withType<Test>().configureEach {
    inputs
        .dir("wordmark")
        .withPropertyName("vendoredWordmark")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // The theme hands Material 3's ColorScheme, Typography and Shapes to every screen, and
    // @FermixPreviews is a set of @Preview annotations, so callers compile against both.
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.window.core)

    // FontSourceTest reads the fonts' SOURCE.json.
    testImplementation(libs.kotlinx.serialization.json)
    // TintNamesTest holds data's TINT_NAMES to Tint: an instance record keeps its tint by name, and data
    // does not depend on this module.
    testImplementation(project(":data"))
}
