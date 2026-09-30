# Fermix for Android

A Kotlin and Jetpack Compose app for Android phones (minSdk 35, targetSdk 36, compiled against API
37), built by Gradle with the Android Gradle plugin. It is only a client of the `fermix` daemon's
mobile channel. It pairs with each daemon by QR code, then talks to it over a TLS WebSocket, with
Noise running on top of it, keyed by a device key in the phone's secure hardware. The engine
repository (`tezra-io/fermix`) owns that wire; this repository vendors it.

This is the repo's only agent-instruction file. Never add a `CLAUDE.md`, `.claude/CLAUDE.md` or
`CLAUDE.local.md`.

## Layout
```
app/                  the application module, io.tezra.fermix
build-logic/          convention plugins: fermix.android.application, fermix.quality (detekt and
                      ktlint); their tests are in build-logic/src/test
config/detekt/        detekt's configuration; there is no baseline
contracts/mobile/     the engine's apps/fermix_core/priv/mobile/, byte for byte, pinned by
                      contracts/CHECKSUMS.txt and contracts/SOURCE.json
gradle/               libs.versions.toml, verification-metadata.xml (sha256 of every dependency), the wrapper
scripts/              verify_protocol_contract.sh
.github/workflows/    ci.yml: contract, build, unit, and gate, the one required check
```

## The contract with the engine
- `contracts/mobile/` is a byte-identical copy of the engine's `apps/fermix_core/priv/mobile/` at
  the commit `contracts/SOURCE.json` pins. `scripts/verify_protocol_contract.sh` checks the pins,
  `--source <fermix checkout>` byte-compares against an engine tree, and `--pinned` compares
  against the pinned commit in `tezra-io/fermix` on GitHub, once the branch `SOURCE.json` names is
  shown to carry it; CI does that on every pull request. Never edit a vendored file: re-vendor as
  `README.md` says, with `CHECKSUMS.txt` and `SOURCE.json` in the same change.
- The daemon ships first. A wire change lands in the engine, the engine releases it, and then it is
  re-vendored here. The app never pins a protocol that no released daemon serves.
- The design is the owner's record. It sits in an engine checkout's `docs/design/`, which the engine
  repository does not track, so it is not on GitHub. `MILESTONE_51_ANDROID_COMPANION_APP.md` is the
  authority; the pipelines are in `MILESTONE_51_ANDROID_CI_CD.md`, and developer setup and signing
  keys are in `MILESTONE_51_ANDROID_APP_DEVELOPER_ONBOARDING.md`.

## Working rules
- Done means `./gradlew --no-configuration-cache build` is green with zero warnings: Kotlin
  `allWarningsAsErrors` in the sources, and in the build scripts through
  `org.gradle.kotlin.dsl.allWarningsAsErrors` (in `gradle.properties` and again in
  `build-logic/gradle.properties`, which the root file does not reach); Android lint
  `warningsAsErrors` on the debug and the release variant, with no baseline; detekt and ktlint with
  no baseline; Gradle `--warning-mode fail`; dependency verification; and the build logic's tests.
  The Android Gradle plugin's own warnings exit 0, so the log must also hold no line that starts
  with `WARNING:` or `w: `; CI fails on one. A reused configuration cache entry skips
  configuration, and with it any Gradle deprecation raised there, hence the flag; CI always
  configures from scratch. `scripts/verify_protocol_contract.sh` must pass too. Never add a
  baseline, a suppression or `ignoreFailures` to get there.
- In `build-logic/src`, never use a member of a Gradle `Action` lambda's implicit receiver, such as
  the task in `tasks.register("x") { ... }`, `.configureEach { ... }` or
  `tasks.named("check") { ... }`. detekt resolves types without kotlin-dsl's SAM-with-receiver
  compiler plugin, so it cannot see that receiver: it prints "compiler errors found during
  analysis" and still passes, and `group`, `description` or `name` inside such a lambda resolve
  against the `Project`. Name the receiver: `register<T>` and `named<T>` take a Kotlin receiver
  lambda, and a `(Task) -> Unit` value names its parameter. Revisit when detekt can fail on
  analysis errors.
- The one exception, pending the owner's decision on dependency updates (CI/CD design section 9,
  question 7): `AndroidCommon.kt` disables lint's `AndroidGradlePluginVersion` and
  `GradleDependency`. They report that a newer version has been published, so their verdict
  changes with upstream releases while the code stands still, and they cannot gate a build.
- Every module applies one convention plugin. What modules share lives in `build-logic`, never
  copied into module scripts. A convention plugin lands with the first module that applies it.
- A dependency arrives with the module that needs it: pinned in `gradle/libs.versions.toml`, with
  `gradle/verification-metadata.xml` regenerated in the same change (`README.md`).
- The build logic's tests are in `build-logic/src/test`. `./gradlew build` runs them through
  `check`; the root `test` does not reach the included build, so CI's unit job names
  `:build-logic:test`.
- JVM tests never touch a real daemon, the network or the phone's Keystore. The deterministic
  vectors carry fixed private keys, and the JVM gate replays them and the vendored fixtures. The
  hardware half runs on a real phone with a freshly generated Keystore key, never an imported
  vector key: the on-device Keystore vectors, pairing, attestation and locked-phone push, recorded
  per handset and OS build (design section 12.6). A test-only key import stays out of the
  acceptance gate.
- No secret enters the tree: no keystore, `keystore.properties`, `google-services.json` or service
  account. Debug builds sign with the developer's own key from outside the repository, never with
  the SDK's.
- Code: linear flow, small functions, no fallbacks, surgical changes.
- Work on `dev`. `main` moves only by pull request, and a release is a `vX.Y.Z` tag on `main`
  (CI/CD design C1). Never tag unless the owner asks.
- No AI attribution anywhere: commits, pull requests or docs. Never push to `main` without a pull
  request. Never commit or push unless the owner says so.
