import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type: AGP 9.4 types a library's `android` accessor as its internal
// implementation, whose source sets fail a cast to the API it declares.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.design"
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
