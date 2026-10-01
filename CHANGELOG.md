# Changelog

All notable changes to the Fermix Android app are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). A release is a `vX.Y.Z` tag on `main`
whose version matches `version.properties`.

## [Unreleased]

### Added

- **The repository.** A Gradle build with convention plugins in `build-logic`, and the app,
  `io.tezra.fermix`, which draws its name on one Material 3 surface, edge to edge, with predictive
  back. It runs on Android 15 and later (minSdk 35) and targets API 36, holds no permissions, and
  keeps nothing in cloud backup or device transfer.
- **Every warning fails the build.** Kotlin in the sources and the build scripts, Android lint on
  both variants, detekt, ktlint and Gradle itself, with no baseline anywhere; CI also fails on a
  warning the Android Gradle plugin prints. Every dependency and plugin is checked against its
  sha256 checksum.
- **Builds are signed with the right key.** A debug build is signed with the developer's own key,
  kept outside the repository, and never with the Android SDK's; a debug build without that key
  fails and says what to create, and a key described only in part fails and names what is
  missing. A release build is shrunk by R8 and is never debuggable.
- **The engine's mobile wire contract is vendored.** `contracts/mobile/` is protocol v1 from the
  engine's v0.12.1 release (`df8d8a4d`), pinned by checksums and provenance, and `scripts/verify_protocol_contract.sh`
  proves it against those pins, an engine checkout, or the pinned commit on GitHub.
- **CI.** Every pull request runs the contract, build and unit jobs and the one required check,
  `gate`.
- **The Noise layer.** `core-noise` runs the phone's side of the IK and IKpsk2 handshakes and the
  session after them, with its static key kept in AndroidKeyStore, and its JVM tests replay the
  engine's Noise vectors byte for byte.
