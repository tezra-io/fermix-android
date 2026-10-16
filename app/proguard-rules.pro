# The release's own rules, beside the convention's default ones (build-logic).
#
# Nothing of the debug app's demo (io.tezra.fermix.demo, README "The demo") belongs in a release. Were a class
# of it to reach one anyway, say written into a main source set and called from the app's code, R8 would keep it
# under a new name, where scripts/check_release_policy.sh's check 10, which reads the release's dex for the
# package, could not see it: so any class of the package that survives the shrinking keeps its own name.
-keepnames class io.tezra.fermix.demo.** { *; }

# The iroh rail (core-transport's IrohRail) calls iroh-ffi, UniFFI's Kotlin over JNA. JNA binds each native
# function to a Java method of the same name, reads a Structure's fields by name in their declared order, and
# calls a Callback's one method by reflection; a class R8 renamed or a member it removed is a native call that
# fails at run time, which no compile sees.
#
# JNA itself: its Native, Pointer, Structure and Callback machinery is found by name from libjnidispatch.so.
-keep class com.sun.jna.** { *; }
# The bindings: UniffiLib's methods are bound to the library's symbols by name, its RustBuffer and callback
# vtables are Structures and Callbacks JNA reflects on, and the error and record classes are built by the
# generated converters through them.
-keep class computer.iroh.** { *; }
# JNA's desktop helpers name AWT, which Android has not; nothing on Android reaches them.
-dontwarn java.awt.**
