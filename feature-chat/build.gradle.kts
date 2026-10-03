import com.android.build.api.dsl.LibraryExtension

plugins {
    id("fermix.android.library.compose")
}

// Through the public DSL type, as feature-onboarding's: AGP 9.4 types a library's `android` accessor as its
// internal implementation.
extensions.configure<LibraryExtension> {
    namespace = "io.tezra.fermix.chat"
    // The fake session and the fixed chats the JVM tests and the instrumented tests both drive.
    sourceSets.getByName("test").kotlin.directories += "src/sharedTest/kotlin"
    sourceSets.getByName("androidTest").kotlin.directories += "src/sharedTest/kotlin"
}

dependencies {
    // The chat reads a record from data's InstanceStore and its rows, outbox, draft and read frontier from the
    // profile's database, sends through core-session's Session in core-protocol's requests, shows the path from
    // core-transport's network facts, and draws its bar with feature-instance's avatar and link words, so callers
    // compile against all of them.
    api(project(":core-protocol"))
    api(project(":core-session"))
    api(project(":core-transport"))
    api(project(":data"))
    api(project(":design"))
    api(project(":feature-instance"))
    api(libs.androidx.lifecycle.viewmodel.compose)
    // Design section 8.3's renderer: its Material 3 Markdown, the highlighted code the code card tints with
    // highlights' lexers, and JetBrains' parser, whose tree the plain words and the raw-HTML rule read.
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.jetbrains.markdown)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Back puts down a lifted message, the selection and the palette before it leaves the chat.
    implementation(libs.androidx.activity.compose)
    // A link preview's tap opens its page in a Custom Tab, tinted with the instance's colour.
    implementation(libs.androidx.browser)

    testImplementation(libs.kotlinx.coroutines.test)
}
