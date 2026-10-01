plugins {
    id("fermix.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // A timeline row's metadata is a JSON object in the public models, so callers compile against it.
    api(libs.kotlinx.serialization.json)
}

// The tests read the vendored contract itself, never a copy (design section 12.6), and as a test
// resource it is an input of the test task, so a re-vendor reruns them.
sourceSets.named("test") {
    resources.srcDir(layout.settingsDirectory.dir("contracts/mobile"))
}
