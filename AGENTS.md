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
                      session per instance, put aside 5 s out of sight), the RowAnnouncer, the
                      notifications' one owner (Notifications), FCM's service, PushInbox and
                      PushRegistrations, the app lock's gate, the navigator and its deep links, the
                      share entry (ShareTarget) and a share's route (Shares.kt), the launcher icon and
                      the system splash (the Fermix mark), and the activity;
                      its share tests on a device in src/androidTest; google-services.json is the
                      placeholder project's; src/debug wires the demo into the debug app alone
                      (DemoApplication, the "Fermix demo" launcher entry DemoEntry), src/testDebug
                      tests that wiring
build-logic/          convention plugins: fermix.android.application (the app, with its JVM,
                      Robolectric and instrumented tests), fermix.android.library,
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
demo-daemon/          the debug app's demo Fermix, io.tezra.fermix.demo (Android library, debug only):
                      six scripted daemons behind core-session's Dialer, in memory, seeded; and the
                      in-memory socket, Noise responder and frames core-session's tests' fake daemon uses
design/               design section 13.1 as code, with the M51 update's monochrome colour (section 1)
                      over it, io.tezra.fermix.design (Compose library): tokens,
                      FermixTheme, the bundled OFL fonts with SOURCE.json, the Fermix mark (FermixMark,
                      its geometry, motion tables and moments), @FermixPreviews, and the
                      specimens' previews in src/test; design/mark/ vendors the mark's SVG and
                      geometry JSON from fermix-design-docs, with SOURCE.json
data/                 the phone's durable state, io.tezra.fermix.data (Android library, JVM-tested
                      on the bundled SQLite): the instance records' DataStore, one Room database
                      per (instance, profile) with its schema in data/schemas, the media cache, the
                      launch check
push/                 a push before it is shown, io.tezra.fermix.push (Android library, no Firebase,
                      JVM-tested against push_vectors.json): the envelope's bounds, the push key, the
                      cipher, the trial over each instance's key, the typed plaintext, the
                      registration step and the diagnostics lines; its Keystore test in src/androidTest
feature-onboarding/   design section 13.3 as screens, io.tezra.fermix.onboarding (Compose library):
                      Welcome to Notifications and every failure, OnboardingViewModel over
                      core-session's pairing, the Navigation 3 entries, the scan's CameraX preview
                      and zxing-cpp reader, the paste sheet, and its previews in src/test; its
                      instrumented tests in src/androidTest, and the fakes both test sets compile in
                      src/sharedTest
feature-instance/     design section 13.7's Instance screen, io.tezra.fermix.instance (Compose library):
                      Link (a session's state and diagnostics as a row, a bar and the screen read
                      them, 1002 a protocol error and never revoked), the avatar and its dot,
                      InstanceViewModel, and its previews in src/test
feature-chats/        design sections 13.4 and 9.4 as screens, io.tezra.fermix.chats (Compose library):
                      the Chats list and its rows, the trust screens, the app lock's screens,
                      ChatsViewModel, the conversation shortcuts and channels (ConversationSync); its
                      instrumented tests in src/androidTest, the samples both test sets compile in
                      src/sharedTest
feature-chat/         design sections 8 and 13.5 to 13.7 as a screen, io.tezra.fermix.chat (Compose
                      library): the Chat screen, its timeline built in pure functions (chatItems,
                      ChatLive), the markdown, code and table cards, the composer, the message actions,
                      ChatViewModel, and its previews in src/test; its instrumented tests in
                      src/androidTest, the fake session and cache both test sets compile in src/sharedTest
gradle/               libs.versions.toml, verification-metadata.xml (sha256 of every dependency), the wrapper
policy/               permissions.txt and exported.txt: the permissions the release APK requests and
                      the components it exports, exactly
release-evidence/     schema.json, and per release vX.Y.Z.json: what a person saw a candidate do on real phones
docs/                 RELEASING.md: a release, from the tag to Play, and what only the owner does;
                      app-shots/, which git ignores, each preview's compact light and dark image
                      as app_shots.sh copies it from a record
scripts/              verify_protocol_contract.sh, check_release_policy.sh (the policy job), app_shots.sh
                      (docs/app-shots from a record, on this machine or in the screens job),
                      settle_emulator.sh (the ui job's wait for a booted emulator's home screen, which then
                      hides the system's error dialogs), stop_emulator.sh (the ui job's bounded end of its
                      emulator), the release
                      pipeline's steps (release_preflight.sh to play_upload.py), lint_workflows.sh and
                      check_workflows.py, check_git_isolation.sh (the tests' git, on a hostile machine and
                      under a hostile caller); their tests in scripts/tests, with a fake gh and cosign, and
                      the emulator scripts' fake adb, ps, ss and sleep in scripts/tests/fakes/emulator
.github/workflows/    ci.yml: contract, build, unit, screens, ui, policy, release-scripts, and gate, the one
                      required check; candidate.yml on a vX.Y.Z tag; promote.yml by hand
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
  `MILESTONE_51_ANDROID_MONOCHROME_AND_WELCOME_MOTION.md` updates it and wins where they disagree: its
  section 1, monochrome, replaces section 13.1's colour (README's Design section says where it is).

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
  configures from scratch. `scripts/verify_protocol_contract.sh` must pass too, and so must
  `./gradlew recordRoborazziDebug`, whatever the change: the build draws no preview, and in a plain
  `test` a preview's test passes without composing it, so only a record shows that every preview
  still draws (Screenshots, below). Never add a baseline, a suppression or `ignoreFailures` to get
  there.
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
- A task that writes Kotlin under a module's directory, `build/` included (KSP, Roborazzi's generated
  screenshot test), is one the module's lint analyses run after (`lintAfterKotlinWriters`): lint analyses a
  test component with the whole module directory as a source root and reads every `.kt` file in it, so a
  writer restored from the build cache beside it once failed the gate with a FileNotFoundException.
- A dependency arrives with the module that needs it: pinned in `gradle/libs.versions.toml`, with
  `gradle/verification-metadata.xml` regenerated in the same change (`README.md`).
- The build logic's tests are in `build-logic/src/test`. `./gradlew build` runs them through
  `check`; the root `test` does not reach the included build, so CI's unit job names
  `:build-logic:test`.
- The demo (`demo-daemon`, README's "The demo") is the debug app's alone. The app takes it as
  `debugImplementation` and wires it in `app/src/debug` only; core-session takes it as `testImplementation`.
  Never let the release see it: no `implementation` of it, no reference to `io.tezra.fermix.demo` from a main
  source set, and `scripts/check_release_policy.sh`'s check 10 refuses a release APK that holds a class, a
  component or a `demo_` resource of it; the release's R8 rules (`app/proguard-rules.pro`) keep the name of any
  class of the package that reaches a release, so the check sees one in the dex wherever it came from, and given
  the release's R8 mapping, as CI's `policy` job and `verify_candidate.sh` give it, it refuses a class of the
  package under any name R8 gave it, as R8 renames what no rule keeps; the debug source set names each demo
  resource `demo_`. A change to `core-protocol`'s events or to core-session's surface (`Dialer`, `Link`,
  `Pairing`, `Session`, the states and events) updates the demo and its tests in the same change: core-session's
  `DemoDaemonTest` runs the demo against core-session's own pairing and session, and the app's `DemoWiringTest`
  and `DemoDeviceTest` the debug app's wiring.
- JVM tests never touch a real daemon, the network or the phone's Keystore. The deterministic
  vectors carry fixed private keys, and the JVM gate replays them and the vendored fixtures. The
  hardware half runs on a real phone with a freshly generated Keystore key, never an imported
  vector key: the on-device Keystore vectors, pairing, attestation, locked-phone push and the pinned
  TLS on the phone's own stack, recorded per handset and OS build (design sections 12.6 and 15.3). A
  test-only key import stays out of the acceptance gate.
- A git a test starts takes nothing from the machine's git configuration, attributes or template or the caller's
  `GIT_` variables, and runs and writes only under the test's scratch directory: the tests' own (`scratchGit` in
  `build-logic/src/test`, `git_environment` and `ScriptTest.script` in `scripts/tests/support.py`), which give an
  identity to the test's own set-up alone, as CI's runner names none, and the product's describe, which build-logic's
  test task runs with neither (`build-logic/build.gradle.kts`). A tag whose tagger the owner's configuration named
  failed on the runner alone, and `GIT_DIR`, which git exports to a hook or an alias in a linked worktree, wins over
  `-C`, so a test run under it committed into and tagged the repository it named (Task 16b;
  `scripts/check_git_isolation.sh` runs the tests on such a machine and under such a caller).
- Instrumented tests (`src/androidTest`, CI's `ui` job) need no daemon and no camera, and never get
  one; the app's `DemoDeviceTest` pairs with the debug app's demo, in the app's own process. The seams are the fake pairing control (`FakeStarter` in `feature-onboarding/src/sharedTest`, the
  JVM tests' too) behind `PairingStarter`, and the stub preview behind `ScanCamera`, whose `allowed`
  stands in for the camera permission, since a connected test's APK is installed with every permission
  granted; the camera's prompt is answered by `PromptRegistry`, an `ActivityResultRegistry` in
  `src/sharedTest`, and the clip is `FakeClip`; the chat's are the fake session and cache (`FakeChatSession`,
  `FakeChatStore` in `feature-chat/src/sharedTest`) behind `ChatSession` and `ChatStore`, and, beyond its
  screen, `ChatOutside`: the activities it starts, its permissions, the camera's screen and the attach
  sheet's grid, whose system Photo Picker tile the rig's `PickerRegistry` answers. The embedded Photo Picker
  is the system's surface, which no test or screenshot draws. `check` builds
  them, so they pass every gate; run them with `./gradlew :feature-onboarding:connectedDebugAndroidTest`,
  `:feature-chats:connectedDebugAndroidTest`, `:feature-chat:connectedDebugAndroidTest`,
  `:push:connectedDebugAndroidTest` and `:app:connectedDebugAndroidTest`, one Gradle run each, on an emulator
  (`README.md`), the app's on an app with nothing paired, as its share tests pair records of their own and
  remove them, and set a screen lock and take it away again, settled first
  by `scripts/settle_emulator.sh`, which then hides the system's error dialogs (`hide_error_dialogs`: a slow
  runner once made the launcher hang, and its "isn't responding" dialog held the focus through eighteen tests;
  the device keeps the setting, so the script takes only an emulator's `ANDROID_SERIAL`, `emulator-<port>`), and
  on CI's own Google APIs images when the run is evidence for CI. A
  test that needs the window's focus (a key event, the clipboard) waits for it with `awaitWindowFocus`;
  onboarding's names a hung or stopped app's dialog as the cause when one has the focus (`noFocusWords`), while the
  app's `DemoDeviceTest` wait does not yet; no test answers a system dialog, which would hide what happened. A
  test that changes the device (the animator scale, the rotation, a fold) puts it back however it ends.
  What needs a real camera, `QrPreview`'s torch and its unbinding, is the device gate's. Stop an
  emulator with `adb -s <serial> emu kill` and wait for its pid, never by matching a process's name:
  `scripts/stop_emulator.sh` finds it as the listener of the serial's console port and ends it with SIGKILL past
  30 s, and every line of CI's emulator script stops the emulator through it on its way out, as the emulator
  runner's step stays open until the emulator's process exits, and its own end is a single `emu kill`.
- A test asserts on state only after waiting on that state, bounded — never after an idle that does not
  know what lands it. Compose's `waitForIdle` on Robolectric drains the main looper alone, so state landed
  after a hop to `Dispatchers.IO` races the assertion that follows it, and loses on a loaded machine (CI's
  build job, Task 13c): wait for the state with a bounded `waitUntil` on a node, which idles the looper as it
  looks, or put the hop on a dispatcher the idle drains, as `ComposerMediaUiTest.partsOf` runs `ChatParts.io`
  on `Dispatchers.Main` for the tests whose assertions a hop lands (the module's other `ChatRoute` tests
  assert nothing a hop to `io` lands). A step of the system's is as unknown to it: over the window a rotation
  makes again, the soft keyboard a typed draft brought up stays, or comes back, until that window takes the
  focus, on Android 15 and 16 alike, and a phone on its side has no room for the list under it, so
  `WindowChangeTest` waits, bounded, for the row it asserts on to be displayed. A fold shares that assertion
  and its wait; the fold's one failure in Task 13 was never reproduced (48 runs before the fix). Every assertion
  on what a rotation, a fold or a relaunch draws waits so, and a wait reads the rule's activity again at each
  poll, as the one a test held may be gone (`awaitWindowFocus`). Compose's own idle after a rotation or a fold is
  waited for with a bound too (`idleWithin`): Espresso's idle has none, and once, after a fold on
  `Pixel_Fold_API_36.1`, it never came back (Task 14c, README). Every instrumented test runs under
  AndroidJUnitRunner's `timeout_msec` from the convention plugins (`INSTRUMENTED_TEST_TIMEOUT_MILLIS`), so a wait
  that hangs anyway fails its test by name. The rename dialog of the Chats list and the Instance screen is taller
  than the room above a phone's landscape keyboard, and after a rotation the platform pans it to keep its field in
  view, its title out of sight in about a third of runs and its buttons in the rest (Task 14c; what decides which
  was not shown, only the settled state): a product defect for the owner to settle
  (README), not a rule for tests. Until it is settled `WindowChangeTest` asserts that dialog there and its field
  displayed with the name, and says why beside it. A sleep is for a span the product or the test defines (the
  lock's grace, a take's length), said so beside it; anything the system makes ready is waited for, bounded, a
  system prompt among it: its field takes the focus while the prompt still slides in, and a PIN it matches before
  the slide ends comes back as a cancel, so `ShareDeviceTest` types once the focused field has held still (Task
  14c).
- Screenshots: git tracks none (the owner, 2026-10-05: "git ignore those images"): `.gitignore` holds each module's
  `src/test/screenshots/` and `docs/app-shots/`, so a clone has no reference, and neither `check` nor CI compares
  an image. Every preview in a `fermix.android.library.compose` module is a screenshot test, which draws only
  when Roborazzi records or compares: in a plain `test` it passes without composing its preview, one that throws
  among them, so a green `test` or `build` says nothing of the previews. `./gradlew recordRoborazziDebug`, part of
  every change's done, draws every preview at its twelve windows into its module's `src/test/screenshots/`,
  running the tests every time, never from the build cache, and a preview that throws fails it by name. Each
  record, compare and verify then runs `checkPreviewsDrawn`, which fails a module that drew no preview (its
  previews are no longer found) and a compare with nothing recorded to compare with, which Roborazzi alone
  passes, and names each image in `src/test/screenshots/` that the run did not draw (a preview gone, renamed,
  made private or moved out of the module's package), so a green record leaves there what it drew and nothing
  else. A run filtered with `--tests` fails that check on every preview it left out: record and compare whole
  modules, and never filter a record that carries the cleanup flag below, which deletes every image the run did
  not draw. CI's `screens` job records on every run, with `--continue`, then runs `scripts/app_shots.sh` and
  uploads `docs/app-shots`, each preview's compact light and dark image, as its `app-shots` artifact; a red
  record's go up as `app-shots-incomplete`, without the previews that threw. A change to what a screen draws is
  shown and reviewed so: record before the change, with `-Proborazzi.cleanupOldScreenshots=true`, which drops the
  image of a preview that is gone; make the change; then `./gradlew compareRoborazziDebug` leaves a
  `*_compare.png` under the module's `build/outputs/roborazzi/` for each window that moved, the old image, the
  difference and the new side by side, and fails naming the images of a preview the change lost.
  `./gradlew verifyRoborazziDebug` fails naming each preview that moved, but names a lost preview's images only
  when nothing else moved, as a verify that fails stops before the check: compare first. Look at every image
  that moved, name the previews in the change's report, and point its reviewer at the `app-shots` artifact of
  the change's CI run, the screens as they now are; the closing record draws the machine's references anew, with
  the cleanup flag when a preview is gone. The record before and the compare after run on one machine:
  Robolectric does not draw alike on Linux, macOS and Windows, and CI draws on Linux x86-64. Never commit a
  screenshot, and never turn Roborazzi's copies from `build/intermediates/roborazzi` back on: they write build
  state over the machine's own references. A screen's previews use `@FermixPreviews` and
  `FermixPreviewTheme { }`. A preview draws a fixed pose, never a clock: a screen that moves is a stateless
  `…At` composable over the pose and the time, which its stateful shell reads off a clock and a preview passes
  fixed, since the preview's tester sets no inspection mode and would draw whatever a clock had reached.
- The Fermix mark's geometry is vendored, never edited: `design/mark/` holds the design's
  `fermix-mark.svg` and `fermix-mark-geometry.json` byte for byte with `SOURCE.json` (the fermix-design-docs
  commit, each file's upstream path and sha256), and `MarkGeometry.kt` is written from the JSON, which
  `MarkGeometryTest` pins value for value and each file to its digest; nothing reads the files at run time. The
  directory is a test resource of design's and the app's unit tests, so a changed byte is an input that runs them
  again: a file a test reads from outside its sources, never declared, let a cached green pass a broken pin. A
  new drawing from the design is re-vendored whole, `SOURCE.json` and `MarkGeometry.kt` in the same change, and
  the launcher icon's and the splash's paths follow it, as `LauncherIconTest` holds them to the SVG (README, the
  design module, says how each is written). The mark's motion is one table per moment in `MarkMotion.kt`, asserted
  key by key against the update and its reference player, each field of a pose held to its own table at every key
  and every segment's middle (a property wired to another table that starts and ends where it does once passed
  every other test), and each moment runs on one clock (`rememberMarkMoment`).
- Onboarding's motion (the M51 update's 7.2 to 7.5): a screen change's spec lives on its entry, in the entry's
  metadata (`ScreenChanges.metadataFor`: NavDisplay's `TransitionKey`, `PopTransitionKey` and
  `PredictivePopTransitionKey`), never on NavDisplay itself, so the app's other screens keep NavDisplay's own and an
  entry added to onboarding brings its changes with it (README's departures from 7.2 to 7.5 say why, where 7.2's
  "Where" puts them on NavDisplay); under Remove animations each is a cut. An entry's metadata is equal each time
  NavDisplay asks for it: a value in it, a change's lambda among them, is made once, never in the call, or each scene
  is unequal to the last for the same screen and a back swipe let go at its end plays the change again. Every time,
  distance and easing is a table in design (`OnboardingMotion.kt`, `CeremonyMotion.kt`, `MarkEyes.kt`), written once,
  asserted against the update's literal or the player's, and read from there by the screens. A moment plays once
  on `rememberMoment`, its saved flag set on its first frame (`LaunchedEffect(Unit) { played = true }`), never at
  its end, so a screen restored mid-moment stands at its end; its test restores it mid-way. A moment's saved flag
  belongs to its entry and its pairing alone: each onboarding entry but the root's goes by a name its screen takes
  anew each time it is pushed (`EntryNames`, the entry's `contentKey`), since NavDisplay forgets a popped entry's
  state only while it draws, and the app lock hides it; a moment that plays again each time its screen comes back,
  the scan's settle, keys its flag to the ViewModel's count of those returns (`OnboardingUi.scanVisit`). A wait
  that hands a screen's work on (the scan's 250 ms after a Fermix code, Notifications' check after a grant) is the
  ViewModel's, so a rotation keeps it and back ends it, and what the wait hands on that the owner gave, the grant,
  is given as it comes, never at the wait's end; a back swipe under way holds the wait until the swipe ends
  (`BackSwipe`, fed by `FollowBackSwipe`), as Navigation 3 hears a swipe only once it is let go, and a stack
  changed under a swipe makes the swipe let go take back the screen the wait brought. What a screen shows while a
  back swipe draws it under the top is what it shows once the swipe is let go: the ViewModel moves a screen's state
  on as it leaves the top, not as it comes back, and the screen going out holds what it showed last.
  A loop is for ongoing work alone and ends with its screen or its work: it runs on `rememberLoop(running)`,
  never under Remove animations, with a bound, and its test shows that no frame is asked for once its screen has
  left (`MotionRig.framesAsked`); the countdown's sweep is a moment a second, keyed by the second, and its test shows
  the same. A preview draws a pose, never a clock (Screenshots, above).
- Colour: no colour but the tokens of `FermixColors` (the M51 update's section 1, monochrome), with
  Material's roles built from them; a screen names no colour of its own but a fixed surface's, as the
  camera's frame and the code card have. A fill in the ink has `onInk` on it, and a wash on the ink is
  `onInk` at the selection's alpha, never `selection`, which is the ink. Blue is `signal`, Fermix blue, and
  marks only what is unread (a Chats row's count, the unread divider, the scroll-to-latest pill's count); it
  is never text on the canvas, a button, a link, focus, progress, a selection or a caret (the owner,
  2026-10-05). A link is the ink and underlined, and so is a text action that only the old accent told
  from the words around it; a line a selected message's row draws on its canvas is the ink, as the
  selection's wash takes the grey and the error text under their floor. `ContrastTest` measures the
  update's table, holds each pair of tokens it lists to its WCAG floor, and pins at what it measures each
  pair of tokens under its floor that README's departures name for the owner; a new pair of tokens the app
  draws goes in it, held or, named in README, pinned.
- Focus: every focusable takes the focus ring (the M51 update's 1.3), and only the design's modifier draws
  it: a new button, chip, field, card, bubble or row that can take the focus takes `Modifier.focusRing(shape,
  on)` in its own shape, `Modifier.rowFocusRing(on, shape)` when it spans its column, sheet, menu or window, as a
  list, a scroll or a card clips a ring outside it, or, a Material `IconButton`, `Modifier.iconFocusRing(on)`;
  a control wider than what it draws (an agent's message in its 88 %) takes `Modifier.contentFocusRing(shape,
  on)` and marks what the ring goes round with `Modifier.ringedContent()`, and keeps its own width, which is
  what a tap and TalkBack reach. The modifier comes before the `clickable`, `toggleable`, `selectable`, field
  or Material component's own focus in the chain, as it hears only what follows it; `on` names where it lies
  (`Surface`, `Ink` on an ink fill, `Dark` on a surface dark in both modes, where its ring must lie whole,
  `Picture` on an image or over content of any colour). A control whose ring lies over what a level above its
  own draws (a list's item beside it, a preview under a message, the next part of its message) has that level
  take `Modifier.raisedWhileFocused()`. It shows only under keyboard input while the window has the
  focus, and changes no colour, size, layout, motion or focus order. A preview draws it with nothing focused
  only through the design module's `internal` `ring(…, shown = true)`; nothing else forces it. What is not
  focusable takes none, and a change that makes something focusable rings it; what cannot take it (a link
  among a message's words) shows its focus another way, and README's departures name it.
- No secret enters the tree: no keystore, `keystore.properties`, `google-services.json` or service
  account. The one `google-services.json` is `app/google-services.json`, the placeholder project's,
  which holds no real key; a real one goes in `app/src/debug/` or `app/src/release/`, which `.gitignore`
  keeps out, and never replaces the placeholder (`GoogleServicesPlaceholderTest` fails the build on one
  that is not the placeholder's). Debug builds sign with the developer's own key from outside the
  repository, never with the SDK's.
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
- A DataStore, the records' or the settings', is read and written only through its store, `InstanceStore` or
  `AppSettingsStore`, which makes it from its file and holds it alone: no other module is handed one or compiles
  against DataStore. Every read, `first()` or a collection, is taken under the store's write lock
  (`lockedReads`: `updateData` with a transform that hands back what it was given, which writes nothing),
  never from DataStore's `data` for its values, and every transform, a read's or a write's, runs in place on
  the store's thread (`locked`): DataStore runs it in its caller's context while it holds the lock, so one
  queued for a busy main thread holds every read behind it. DataStore 1.2.1's `data`, started while a write is
  under way, reads the file without the lock and keeps what it read until the next write, so a collector
  started then never sees that write (`ReadsDuringWritesTest`), and a read that lands as the write moves its
  file in finds none and answers with the serializer's default: no paired Fermix, the app lock off (a probe on
  Robolectric counted 3 to 17 in a run of 20,000 writes, some six million reads, on two cores). A DataStore is
  made through `atomicDataStore`, over OkioStorage, whose one rename puts the written file in place:
  DataStore's own storage deletes the old file first, so a process killed in that instant leaves none. Its
  serializer emits what it wrote before `writeTo` returns: OkioStorage syncs the file then, before the move, and
  a byte still in the sink's buffer reaches the file only after that sync, so a power cut after the move could
  leave an empty or a partial file in the old one's place (`SyncedWritesTest`). A read started after a write has
  returned shows it, so a test reads once after a write of its own has returned; it waits, bounded, on the store's
  flow or a `stateIn` only for a write someone else makes, and never on a predicate that an empty list satisfies.
- The phone reaches no address but its daemon's. A link preview's thumbnail, like any blob, comes only
  through `Session.fetchMedia`, never from the URL an event names, and `feature-chat`'s own code holds no
  HTTP client, socket, URL fetch or web view (`ChatMediaTest` scans its sources and build script, and the
  class names its compiled code refers to). An approval's token and routes stay in core-session, which
  answers a card by its id. Its answer travels as an outbox `command` (`approval-answer:`), which holds the
  token until the daemon accepts it and which the chat never draws; the daemon's row of it is kept without
  its words (`keptMessage`), so no cache, announcement or index holds the token, and no search shows it.
- A string from the wire or from another app never names a path or picks what the app reads. A path is built
  from names the app derived, a digest or a name with no separator, control character or lone surrogate in it,
  never `.` or `..`, and its canonical path is checked to lie under the directory it was meant for before
  anything is written there (`sharedFile`, `liesUnder`, comparing strings, as ART throws on a lone surrogate in
  a java.nio path); a deletion there never follows a link. PROTOCOL.md gives a blob's `ref` no form, and a
  `../../no_backup/` once named the instance records. A wire string a system API takes, an intent's or a
  MediaStore entry's type or name, is bounded first (`mediaTypeOf`, `fileNameOf`) and picks nothing it was not
  checked for (Save puts a blob in Pictures/Fermix only when its kind and its type both say image; anything
  else goes to Download/Fermix), and that API's refusal, a name the media store numbers no further among them,
  is logged, never a stopped app. What enters the chat from outside it goes through the one check, `mayRead`:
  another app's `content:` URI whose authority, without its `user@` prefix, names no provider of the app's own
  package, which the app would read with its own rights, or a `file:` URI the chat made under its cache; a
  refusal is logged by scheme and authority, never by path. A clip's URI is never opened for its text
  (onboarding's Paste takes the URI's own words). A link from the wire opens a web address or nothing
  (`MessageLinks`): `http` or `https` of at most 2,048 bytes, a link preview's bound, in the Custom Tab, any
  other scheme or a longer address never handed to the system, its tap logged by the scheme alone; a tapped
  address is never looked up to another (`DefinedLinks`: a destination that is a label in brackets is no
  definition); a link that opens nothing, a reference's among them, draws as its words with no link on
  them (`LiteralMarkup`); and a link's touch target is its words alone, so the words beside it stay the
  message's (`InlineLinks`).
- A size, a count or a time that another app or the wire gives is never the only bound. What lands from outside
  has a bound of the app's own beside the other side's number: a landing copy's bytes stop at the app's
  `LANDING_MAX_BYTES` whatever the daemon's `caps.max_media_bytes` says (any integer, or none before its first
  `hello_ack`) and whatever the provider's size column says; an item's name and type, which go into its
  `attach_begin` and the chat's saved state, are held to a file's name (`fileNameOf`, 255 bytes) and a
  `type/subtype` (`mediaTypeOf`); the tray takes ten, and another app's landings (a paste's, the keyboard's, a
  share's) run one at a time, so each counts the room once those before it have landed, each waiting its turn
  `LANDING_WAIT_MILLIS` at most and then landing within as long, on the app's `LANDING_THREADS`, what had not
  landed left out, logged, its provider's call cancelled and its stream closed; the owner's own picks wait behind
  none of it, on `io`; and what a landing waits for of the chat's own (its record, its draft) is waited for
  `READ_WAIT_MILLIS` at most. An image the tray or Send decodes is refused from its decoder's header listener,
  before a pixel is decoded, past `MAX_IMAGE_PIXELS`: a decode reads every pixel whatever size it draws at, so its
  time is the image's. What goes out is weighed as the codec encodes it: a request, each `attach_begin`, an
  approval's answer (`ApprovalAnswer.TooLong`) and a `media_fetch` of a ref from the wire, before the session takes
  it, and a command the daemon names past the wire's rule for a name is never offered; the outbox's 128 is a
  refusal the caller hears (`Session.send`'s false), never a throw. What the owner pastes is the clipboard's, and
  never enters a saved state, which goes through the binder as the app stops, whose 1 MB a paste outgrows: the
  composer's `TextFieldState` is remembered, not saved, and the chat brings its words back; search saves the query
  it took. A path that takes something new from outside names its three bounds in
  the same change, with a test that feeds it the other side's worst: a stream that never ends, many at once, a call
  that never answers, a string past one frame. On the device the other app is the test APK's own provider
  (`EndlessProvider`), written in Java: the platform runs it in the test APK's own process, which has no Kotlin
  runtime, as the build leaves it out of the test APK. The paths this rule does not yet hold are listed for the
  owner in README (Task 14c): Send's copy of a Photo Picker or Files pick (`PhoneMedia.copyInto`), a fetched blob's
  size past its `media_begin`'s own word, a bubble's image read whole into memory and decoded with no bound on its
  pixels, a provider's thumbnail of a pick (`ContentResolver.loadThumbnail`), the field's and the draft's length, the
  ids from the wire a screen saves across a rotation (a selection's, the viewer's, the turns an arrival played), and
  another app's provider that ignores its cancel holding the landing threads for other apps' shares.
- The app's exported components are these four, as the merged release manifest has them, and no other: the
  activity, `MainActivity`, for the launcher (`MAIN`/`LAUNCHER`), which acts only on a chat's
  `fermix://chat/{instance}/{profile}` link naming a paired Fermix and on "Add Fermix", and never on an intent
  replayed from Recents; the share entry, the activity `ShareTarget`, with no window, no affinity and no place
  in Recents, whose filters take `SEND` and `SEND_MULTIPLE` of `image/*`, `video/*`, `text/plain` and `*/*`,
  and which does nothing but take a share (a share sent to the activity itself is nothing); Firebase's
  `FirebaseInstanceIdReceiver`, behind `com.google.android.c2dm.permission.SEND`; and androidx's
  `ProfileInstallReceiver`, behind `android.permission.DUMP`. `scripts/check_release_policy.sh` holds the
  release APK to exactly these, each with its permission and its filters whole (their actions, categories and
  data, and the filter's own attributes), as `policy/exported.txt` lists them: a component exported, or an
  action, a category (`BROWSABLE` lets a web page's link fire it) or data added to one, by the app or a library, is
  a change there and here in the same commit, after reading the merged release manifest.
- The activity stays `singleTop`: `singleTask` would clear what it has open over it (the Files picker, a
  Custom Tab, a permission's prompt) at a launcher tap. A share reaches it from the entry in the process
  (`AppServices.shares`), on an intent shaped as the launcher's (`shareForward`), never on the share's own intent:
  first with no grant, then with the read grants the entry holds in its `ClipData` (`shareForwards`). The intent
  that starts a task stays the task's own, which Recents starts again once the activity is gone, and a start that
  names a grant the app no longer holds fails and drops the task from Recents, so no intent that carries a grant
  ever starts the app's task (`ShareDeviceTest` brings such a task back with `AppTask.moveToFront`).
- A share enters through `mayRead`, as a paste does: each URI another app shares is read only as another app's
  `content:` URI, at its entry (`sharedOf`) and again as it lands (`readableUri` with `PickedFrom.SHARE`); a
  `file:` URI, the app's own providers (`ownsProvider`, with or without a `user@` prefix) and any other scheme
  are refused, logged by scheme and authority alone. At most ten are weighed, each copied into the chat's own
  file as it lands, while the grant the activity took from the entry holds. A landing copy is bounded, once the
  chat's record is read (waited for, bounded): an item past the room the tray has, or whose provider says it is
  past what its copy may hold, is never copied, and the copy stops a byte past that (`copyAtMost`); what a copy may
  hold is the daemon's limit under the app's own `LANDING_MAX_BYTES`, that bound alone while the record holds no
  caps, and for an image, which goes as a JPEG made from it, `LANDING_MAX_BYTES` whatever the limit (the rule
  above). A provider's `RuntimeException` carried across the
  binder, from describing or copying an item, drops that item, logged by its class alone (its message is the
  provider's), never the app, and a `SecurityException` there is logged by its class alone too (the platform's
  message names the URI whole). Its words are words, bounded to what one `msg` carries; a Direct Share's shortcut
  id is looked up among the paired records, never made into a chat; only a Fermix the phone still trusts is a
  share's target, on the sheet and by its route alike (`shareRowsOf`, `shareTargetsOf`); no extra of a share's intent is passed on or used as an intent; with the
  app lock on the lock comes first, the sheet is never drawn over it, and a share is lost once the app leaves
  with the lock not passed; and nothing is sent until the owner taps Send.
- In `src/sharedTest`, detekt's `TooManyFunctions` applies, as its default excludes only `test` and
  `androidTest`: a fake of a wide interface delegates a part (`SessionStore, RowEdits by NoRowEdits`). The
  type-resolving tasks `build` runs (`detektDebug`, `detektRelease`, `detektDebugUnitTest`,
  `detektDebugAndroidTest`) find what a bare `detekt` does not, `InjectDispatcher` and
  `ImplicitDefaultLocale` among them, so check with them.
- A call that needs an SDK extension (the embedded Photo Picker's, extension 15 of API 34) sits under a
  positive check inline in the same function, `if (SdkExtensions.getExtensionVersion(...) >= 15)`, and the
  function that builds what needs it carries `@RequiresExtension`: lint reads no other form, and a helper
  or an early return reads to it as no check at all.
- A permission the app starts to request lands in `policy/permissions.txt` in the same change, with
  the design section that asks for it; CI's `policy` job fails a release APK whose permissions differ
  from that file's in either direction (`scripts/check_release_policy.sh`). A library's manifest counts:
  read the merged release manifest after adding or upgrading a dependency (Firebase Messaging brings
  `WAKE_LOCK` and `com.google.android.c2dm.permission.RECEIVE`), and remove one the app does not need
  with `tools:node="remove"` rather than list it.
- A token, a key, a salt or a word of a push's plaintext never reaches a log or the push diagnostics. The
  instance a notification names, its channel, shortcut and tap come from the record whose key verified
  the push, never from its plaintext, whose fields are shown as text only; every PendingIntent is
  explicit and `FLAG_IMMUTABLE`. An FCM message's `notification` block is never rendered, on two paths:
  the manifest turns Firebase's notification delegation off
  (`firebase_messaging_notification_delegation_enabled`), so Play services never shows the block as the app
  without calling it, and the messaging service takes the block out of the intent before Firebase's own code
  could show it (`handleIntent`). Firebase reads that flag once per install and keeps Play services as the
  delegate of an install that ran without it, so never drop it, even for one build
  (`FermixMessagingServiceTest` reads it); with both, what a sender who learns the token writes there
  reaches no notification, past the app lock or otherwise.
- Code: linear flow, small functions, no fallbacks, surgical changes.
- Work on `dev`. `main` moves only by pull request, and a release is a `vX.Y.Z` tag on `main`
  (CI/CD design C1). Never tag unless the owner asks.
- A release goes through `candidate.yml` and `promote.yml` alone (`docs/RELEASING.md`). `versionName` is the nearest
  `vX.Y.Z` tag, read by the build from git, and never a line of `version.properties`, which holds `versionCode`
  alone; the release pull request raises it and writes the version's `CHANGELOG.md` entry, and a discarded
  candidate's numbers are never reused (C7).
- No workflow takes an input, a variable or a flag that lets a release past a check: no "skip", "force",
  "override" or "bypass", by that name or any other (CI/CD design section 8). `check_workflows.py` refuses an input
  named so, and holds `promote.yml` to the tag as its one input and `candidate.yml` to none, so no other name
  carries one either. A refusal is fixed where it is true, never routed around.
- `release-evidence/vX.Y.Z.json` lands on `main` only by pull request, once its candidate exists, written by the
  person who ran the device gate and the scenarios on real phones. It records what was seen, a failed scenario
  as failed, and is never edited to make `promote.yml` pass: a candidate that failed is discarded.
- The release keystore, its passwords, the Play service account and the owner's `google-services.json` live in
  the `release` environment alone, never in the tree, a log or a repository secret. A real `google-services.json`
  goes in `app/src/debug/` or `app/src/release/`, which `.gitignore` keeps out, never over the tracked
  placeholder `app/google-services.json`. `candidate.yml`'s `sign` writes the owner's file into
  `app/src/release/` of a clean checkout for its build and shreds it on every way out, then writes the keystore
  under the runner's temporary directory and shreds it on every way out. Gradle never holds the key: the build
  ends unsigned, and `apksigner` and `jarsigner` sign it. The build and the key do share that job's runner, as
  section 4.5 has it, so no dependency or action enters without its checksum or commit (C12).
- A release workflow's logic lives in a script under `scripts/`, with a test in `scripts/tests` that plants each
  refusal it makes; the workflow only wires them. Every workflow names its `permissions:`, a job that needs more
  names its own, and a job that reads a draft release holds `contents: write`, as GitHub shows a draft to no
  other token; every job has `timeout-minutes`, every action is pinned by its full commit with its version in a
  comment, nothing runs on `pull_request_target`, and nothing retries. The workflows keep the house's block
  layout, every value whole on its line, with no anchor, alias, tag, flow mapping or escape: each of those can
  carry what the text does not show, so `check_workflows.py` refuses what it cannot place.
  `scripts/lint_workflows.sh` (actionlint, shellcheck, ruff and `check_workflows.py`) runs in CI's
  `release-scripts` job.
- No AI attribution anywhere: commits, pull requests or docs. Never push to `main` without a pull
  request. Never commit or push unless the owner says so.
