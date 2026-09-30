plugins {
    base
    id("fermix.quality")
}

// build-logic passes the same gates as the modules it configures.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
