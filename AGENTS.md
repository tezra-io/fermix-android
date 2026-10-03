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
app/                  the application module, io.tezra.fermix: AppServices, the SessionSupervisor (one
                      session per instance, put aside 5 s out of sight), the RowAnnouncer, the app
                      lock's gate, the navigator and its deep links, and the activity
build-logic/          convention plugins: fermix.android.application (the app, with its JVM and
                      Robolectric tests), fermix.android.library,
                      fermix.android.library.compose (Compose and the Roborazzi screenshot tests),
                      fermix.android.library.room (Room under KSP, the bundled SQLite, and its
                      desktop build for the JVM tests), fermix.jvm.library, fermix.quality (detekt
                      and ktlint); their tests are in build-logic/src/test
config/detekt/        detekt's configuration; there is no baseline
contracts/mobile/     the engine's apps/fermix_core/priv/mobile/, byte for byte, pinned by
                      contracts/CHECKSUMS.txt and contracts/SOURCE.json
core-noise/           the Noise layer, io.tezra.fermix.noise: IK and IKpsk2 initiator, gated on
                      contracts/mobile/noise_vectors.json in JVM tests
core-protocol/        the wire codec, io.tezra.fermix.protocol (pure JVM): frames, v1 and v2 events,
                      event_part runs, the pairing link; gated on the vendored fixtures and schema
core-transport/       the transport, io.tezra.fermix.transport (Android library): the pinned TLS
                      WebSocket, the candidate race, the network facts; JVM-tested but NetworkWatcher
attest/               the device key, io.tezra.fermix.attest (Android library): the hardware gate,
                      DeviceKeys over AndroidKeyStore, the attestation challenge, key aliases, the
                      chain's shape check; JVM-tested but the Keystore paths, which are the device gate's
core-session/         the pairing ceremony and one paired session, io.tezra.fermix.session (Android
                      library, no android.*, JVM-tested against a fake daemon): Pairing over attest's
                      DeviceKeyFacade, hello, the outbox, cursors and acks, reconnect reconciliation,
                      keepalive and close codes, the turn machines
design/               design section 13.1 as code, io.tezra.fermix.design (Compose library): tokens,
                      FermixTheme, the bundled OFL fonts with SOURCE.json, @FermixPreviews, and the
                      screenshot references in design/src/test/screenshots
data/                 the phone's durable state, io.tezra.fermix.data (Android library, JVM-tested
                      on the bundled SQLite): the instance records' DataStore, one Room database
                      per (instance, profile) with its schema in data/schemas, the media cache, the
                      launch check
feature-onboarding/   design section 13.3 as screens, io.tezra.fermix.onboarding (Compose library):
                      Welcome to Notifications and every failure, OnboardingViewModel over
                      core-session's pairing, the Navigation 3 entries, the scan's CameraX preview
                      and zxing-cpp reader, the paste sheet, and the screenshot references in
                      feature-onboarding/src/test/screenshots; its instrumented tests in
                      src/androidTest, and the fakes both test sets compile in src/sharedTest
feature-instance/     design section 13.7's Instance screen, io.tezra.fermix.instance (Compose library):
                      Link (a session's state and diagnostics as a row, a bar and the screen read
                      them, 1002 a protocol error and never revoked), the avatar and its dot,
                      InstanceViewModel, and the screenshot references in its src/test/screenshots
feature-chats/        design sections 13.4 and 9.4 as screens, io.tezra.fermix.chats (Compose library):
                      the Chats list and its rows, the trust screens, the app lock's screens,
                      ChatsViewModel, the conversation shortcuts and channels (ConversationSync); its
                      instrumented tests in src/androidTest, the samples both test sets compile in
                      src/sharedTest
feature-chat/         design sections 8 and 13.5 to 13.7 as a screen, io.tezra.fermix.chat (Compose
                      library): the Chat screen, its timeline built in pure functions (chatItems,
                      ChatLive), the markdown, code and table cards, the composer, the message actions,
                      ChatViewModel, and the screenshot references in its src/test/screenshots; its
                      instrumented tests in src/androidTest, the fake session and cache both test sets
                      compile in src/sharedTest
gradle/               libs.versions.toml, verification-metadata.xml (sha256 of every dependency), the wrapper
policy/               permissions.txt: the permissions the release APK requests, exactly
scripts/              verify_protocol_contract.sh, check_release_policy.sh (the policy job),
                      settle_emulator.sh (the ui job's wait for a booted emulator's home screen)
.github/workflows/    ci.yml: contract, build, unit, screens, ui, policy, and gate, the one required check
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
- detekt runs no compiler plugin either, so the `serializer()` that kotlinx.serialization generates
  on a `@Serializable` class is unresolved to it, with the same silent "compiler errors found during
  analysis". Look a serializer up with the library's `serializer<T>()` instead.
- The one exception, pending the owner's decision on dependency updates (CI/CD design section 9,
  question 7): `AndroidCommon.kt` disables lint's `AndroidGradlePluginVersion` and
  `GradleDependency`. They report that a newer version has been published, so their verdict
  changes with upstream releases while the code stands still, and they cannot gate a build.
  And one by design: `core-transport` disables lint's `CustomX509TrustManager`, which fires on
  any class implementing `X509TrustManager`, because the pinning trust manager of design sections
  12.2 and 12.3 is such a class. `TrustAllX509TrustManager`, which catches an accept-all body,
  stays on, and the module's tests prove the pin refuses every other certificate.
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
  vector key: the on-device Keystore vectors, pairing, attestation, locked-phone push and the pinned
  TLS on the phone's own stack, recorded per handset and OS build (design sections 12.6 and 15.3). A
  test-only key import stays out of the acceptance gate.
- Instrumented tests (`src/androidTest`, CI's `ui` job) need no daemon and no camera, and never get
  one. The seams are the fake pairing control (`FakeStarter` in `feature-onboarding/src/sharedTest`, the
  JVM tests' too) behind `PairingStarter`, and the stub preview behind `ScanCamera`, whose `allowed`
  stands in for the camera permission, since a connected test's APK is installed with every permission
  granted; the camera's prompt is answered by `PromptRegistry`, an `ActivityResultRegistry` in
  `src/sharedTest`, and the clip is `FakeClip`; the chat's are the fake session and cache (`FakeChatSession`,
  `FakeChatStore` in `feature-chat/src/sharedTest`) behind `ChatSession` and `ChatStore`. `check` builds
  them, so they pass every gate; run them with `./gradlew :feature-onboarding:connectedDebugAndroidTest`,
  `:feature-chats:connectedDebugAndroidTest` and `:feature-chat:connectedDebugAndroidTest`, one Gradle run
  each, on an emulator (`README.md`), settled first
  by `scripts/settle_emulator.sh`, and on CI's own Google APIs images when the run is evidence for CI. A
  test that needs the window's focus (a key event, the clipboard) waits for it with `awaitWindowFocus`. A
  test that changes the device (the animator scale, the rotation, a fold) puts it back however it ends.
  What needs a real camera, `QrPreview`'s torch and its unbinding, is the device gate's. Stop an
  emulator with `adb -s <serial> emu kill` and wait for its pid, never by matching a process's name.
- Screenshots: every preview in a `fermix.android.library.compose` module is a screenshot test, and
  `verifyRoborazziDebug` (in `check`, and CI's `screens` job) compares it with its reference image
  in the module's `src/test/screenshots/`, and fails on a reference no preview drew. A reference
  image changes only with the change that moved it, in the same commit: redraw with
  `./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true`, which always runs the
  tests and drops the reference of a preview that is gone, on Linux x86-64 only, as CI draws them;
  look at every image that changed, and never re-record to turn a failing verify green without that
  change. Never turn Roborazzi's copies from `build/intermediates/roborazzi` back on: they write build
  state into the references. A screen's previews use `@FermixPreviews` and `FermixPreviewTheme { }`.
- No secret enters the tree: no keystore, `keystore.properties`, `google-services.json` or service
  account. Debug builds sign with the developer's own key from outside the repository, never with
  the SDK's.
- A Room entity change commits the schema the build exports into `data/schemas` in the same change;
  once a version has shipped, the change is a new database version with its migration, never an edit
  of a shipped version's file. CI's build job fails when the build leaves `data/schemas` changed.
- A profile's database or media cache is read through `ProfileDatabases`' `observe`, `withDatabase` or
  `withMediaCache`, never held across a removal: Room ends no flow as its database closes, so `delete` closes
  it only once each such reader has ended. A session alone holds one, its store and its announcer, and the
  supervisor orders it instead: it closes each session it keeps before a removal, and `Session.close` returns
  only once the run and every request made before it (`send`, `retry`, `markRead`, `remove`, in the caller's
  coroutine) have ended. A pairing's session is the supervisor's only from `adopt`, and "Pair again" merges
  only into a row in a trust state, whose run is over.
- The phone reaches no address but its daemon's. A link preview's thumbnail, like any blob, comes only
  through `Session.fetchMedia`, never from the URL an event names, and `feature-chat`'s own code holds no
  HTTP client, socket, URL fetch or web view (`ChatMediaTest` scans its sources and build script, and the
  class names its compiled code refers to). An approval's token and routes stay in core-session, which
  answers a card by its id. Its answer travels as an outbox `command` (`approval-answer:`), which holds the
  token until the daemon accepts it and which the chat never draws; the daemon's row of it is kept without
  its words (`keptMessage`), so no cache, announcement or index holds the token, and no search shows it.
- In `src/sharedTest`, detekt's `TooManyFunctions` applies, as its default excludes only `test` and
  `androidTest`: a fake of a wide interface delegates a part (`SessionStore, RowEdits by NoRowEdits`). The
  type-resolving tasks `build` runs (`detektDebug`, `detektRelease`, `detektDebugUnitTest`,
  `detektDebugAndroidTest`) find what a bare `detekt` does not, `InjectDispatcher` and
  `ImplicitDefaultLocale` among them, so check with them.
- A permission the app starts to request lands in `policy/permissions.txt` in the same change, with
  the design section that asks for it; CI's `policy` job fails a release APK whose permissions differ
  from that file's in either direction (`scripts/check_release_policy.sh`).
- Code: linear flow, small functions, no fallbacks, surgical changes.
- Work on `dev`. `main` moves only by pull request, and a release is a `vX.Y.Z` tag on `main`
  (CI/CD design C1). Never tag unless the owner asks.
- No AI attribution anywhere: commits, pull requests or docs. Never push to `main` without a pull
  request. Never commit or push unless the owner says so.
