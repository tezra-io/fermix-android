# The release's own rules, beside the convention's default ones (build-logic).
#
# Nothing of the debug app's demo (io.tezra.fermix.demo, README "The demo") belongs in a release. Were a class
# of it to reach one anyway, say written into a main source set and called from the app's code, R8 would keep it
# under a new name, where scripts/check_release_policy.sh's check 10, which reads the release's dex for the
# package, could not see it: so any class of the package that survives the shrinking keeps its own name.
-keepnames class io.tezra.fermix.demo.** { *; }
