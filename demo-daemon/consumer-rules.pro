# The demo daemon is the debug app's alone (app/build.gradle.kts takes it as debugImplementation). Were a release
# to take it, every class of it would be kept under its own name, io.tezra.fermix.demo, so that the release
# policy's check 10, which reads the release's dex for that package, fails it rather than miss it renamed.
-keep class io.tezra.fermix.demo.** { *; }
