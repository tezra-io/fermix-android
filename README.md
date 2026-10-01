# Fermix for Android

The Fermix companion app for Android phones, written in Kotlin with Jetpack Compose.

Fermix itself is a background service, the `fermix` daemon from the engine repository
([`tezra-io/fermix`](https://github.com/tezra-io/fermix)). This app is a client of that daemon's
mobile channel. It pairs with each of your Fermix daemons (a Mac, a Linux box, a development
checkout) through a QR code. After that it talks to each one over a TLS WebSocket, with Noise
running on top of it, keyed by a device key that lives in the phone's secure hardware and never
leaves it. Each daemon is its own trust domain, and the phone pairs with each one separately.

The repository is at its first stages: the build, its gates, the vendored wire contract, CI, the
Noise layer (`core-noise`), the wire codec (`core-protocol`), the transport (`core-transport`), the
session (`core-session`) and the design language with its screenshot tests (`design`) exist, and
the app draws its name and nothing else yet. The design documents are named below.

```
app/                    the application module (io.tezra.fermix)
build-logic/            the convention plugins every module applies, and their tests
config/detekt/          the detekt configuration; there is no baseline
contracts/mobile/       the engine's mobile wire contract, byte for byte, pinned by contracts/CHECKSUMS.txt and contracts/SOURCE.json
core-noise/             the Noise layer under every session (io.tezra.fermix.noise)
core-protocol/          the wire codec: frames, events and the pairing link (io.tezra.fermix.protocol)
core-transport/         the pinned TLS WebSocket, the candidate race and the network facts (io.tezra.fermix.transport)
core-session/           one paired session: hello, the outbox, the cursors, reconciliation and the turns (io.tezra.fermix.session)
design/                 the design language as code, its fonts, previews and screenshot references (io.tezra.fermix.design)
gradle/                 the version catalog, the dependency checksums and the wrapper
scripts/                verify_protocol_contract.sh
version.properties      versionName and versionCode
```

## Modules

`core-noise` (`io.tezra.fermix.noise`) is the Noise layer the phone runs under every session: the
initiator's side of `Noise_IK_25519_ChaChaPoly_SHA256` for a paired phone and of
`Noise_IKpsk2_25519_ChaChaPoly_SHA256` for pairing, with the clear `FXM1` prelude on message 1
only, the six-digit SAS, the 65,535-byte WebSocket message bound (message 1's prelude counted in
it), and each direction's rekey after exactly 2^20 frames. Its static key is an operation, not key
material: `KeystoreStaticKey` runs X25519 inside AndroidKeyStore, and the tests put a software key
in its place. Those JVM tests replay `contracts/mobile/noise_vectors.json`, read from the vendored
file itself: both handshakes byte for byte, both handshake hashes and SAS values, every transport
frame at its nonce, and the rekeyed key with the frame after it. The vectors pin only frames from
the phone, so the receive direction is checked against a test responder that derives its transport
keys with its own HKDF, apart from the module's Split; a daemon-to-phone frame in the vectors
would pin it outright. The tests also cover a second rekey in each direction, the size bounds, a
low-order daemon key, a session ended by a failed tag, a spent nonce or a close, its keys zeroed,
and a handshake zeroing its secrets however it ends. The Keystore path is proven on a real phone
with a freshly generated key, never in these tests (design section 12.6).

`core-protocol` (`io.tezra.fermix.protocol`) is the wire codec, plain Kotlin on the JVM with
kotlinx.serialization. A `Frame` is a header and a raw tail behind a uint32 big-endian length,
held to the 4,096-byte header, the 61,440-byte tail and the 65,519-byte frame. The envelope's `v`
(1 or 2) is handed to the caller, and its `seq` counts from 1 as a `ULong`, the schema's unsigned
64-bit range. Every protocol v1 client and server event has a model, and the protocol v2 changes
of design section 7 are in the same models: each field that only one version carries or requires
is listed with its versions, ignored as unknown in the other on decode and refused there on
encode. A server event the codec does not know comes back as `ServerEvent.Unknown`; an unknown
field is ignored, whatever it holds, and never written; a field the model has is never null, nor is
anything in a row's metadata; and a value outside a closed set is refused by field. A header is
held to RFC 8259 where the parser is looser: a raw control character inside a string, and a bare
value with a sign or a leading zero, are refused; a key named twice takes its last value. A header,
or a run's logical event, that nests deeper than 32 levels is refused before it is parsed, since the
parser recurses once per level; the bound is the codec's own, as the contract sets none. The number
of values is not bounded, by the contract or here: a 1 MiB logical event of small values takes tens
of MiB of heap while it is decoded, and a row holds its metadata's tree, which core-session budgets
for. `EventPartAssembler` joins `event_part` runs, one `count` and one `v` throughout and
consecutive `seq`s, and `PairingLink` reads the owner's QR code into a secret the caller zeroes. The
link's `v`, exactly `1` or `2`, is read first and then only that version's parameters; names and
values are form-decoded, a fragment is no part of the query, `name` and `profile` are neither blank
nor hold a control character, and the keys are canonical base64. Every refusal is a
`ProtocolException` that names what it refused and quotes nothing of the header but an event's name
and a field's path, since the header may carry an approval's token; a free-form metadata key in a
path is cut to its first 64 characters. Every event's rules and bounds are listed in `Rules.kt`,
next to the models, and `ShapeCheck.kt` reads each field's presence, type and closed set from the
models themselves; the frame's bounds are in `Frame.kt`, the JSON text's and the envelope's in
`Envelope.kt`, a run's in `EventPartAssembler.kt`, and the link's in `PairingLink.kt` and
`LinkQuery.kt`. The models are written by hand. Their field order is the fixtures', `v`, `t` and
`seq` first: the daemon writes its keys in an order of its own and reads any, so the byte-for-byte
gate pins the fixtures' text. Tests hold the models to `protocol.schema.json`: the event catalogue,
each event's fields, required fields and types, every closed set, and every bound the schema states,
each planted into a fixture frame and refused under its field's path. The tests read the vendored
files themselves: every client and server fixture line decodes and encodes back byte for byte, the
client events built in Kotlin encode to their lines, the binary frames split and rebuild from the
header text each line carries, the vendored run reassembles, and the pairing link parses. The
protocol v2 tests, their bounds and required fields among them, are provisional, in one file, until
the engine's v2 export is vendored (engine stage D1). Like every `fermix.jvm.library` module, it is
compiled against the Java 17 API, not only to Java 17 bytecode.

`core-transport` (`io.tezra.fermix.transport`) is how the phone reaches a daemon. It carries bytes
and network facts and knows nothing of Noise, events or sessions. It is an Android library, since
ConnectivityManager lives there, and every rule in it but `NetworkWatcher` is plain Kotlin, tested
on the JVM. `PinnedTrust` holds one instance's `tls_fp`: its `X509TrustManager` throws unless the
SHA-256 of the leaf certificate is the pin, consults no certificate authority and accepts no issuer,
and its `HostnameVerifier` re-checks the same digest; there is no `CertificatePinner` and no Network
Security Config pin-set (design section 12.3). `WebSocketConnector` opens
`wss://<candidate>:<port>/ws` with OkHttp over that trust alone: HTTP/1.1, TLS 1.3 or 1.2, no proxy,
no redirect, no retry, no WebSocket ping and no read timeout, with 10 s to connect and 25 s to the
upgrade. A `Connection` carries binary messages of at most 65,535 bytes in both directions, closes
with 1009 on a larger one and with 1003 on a text message, and ends with the close code and reason,
saying whether the daemon sent them, or with the failure; a daemon that accepts compression is
refused with 1002. OkHttp bounds no incoming message and buffers every fragment of one before it
hands the message on, so a larger message is refused once it is whole. OkHttp closes the socket with
1001 when its outgoing queue would pass 16 MiB, so `queuedBytes` shows a sender what waits to be
written. Whoever holds a `Connection` closes it, however it ended: closing drops the messages not
yet read, which lets the socket's reader thread go. The connector keeps no idle connection, since
the daemon serves one request per connection. An open fails typed: `PinMismatch`, the security event
that is never retried, found even under another address's failure; `Refused` with the HTTP status
and OkHttp's reason; `Closed`; or `Unreachable`. A name's addresses are OkHttp's to try one after
another, so a pin refusal on one of them is a `PinMismatch` only when every address fails: when
another address presents the pinned certificate, the open succeeds and the refusal goes unreported.
Until each address is a route of its own, a name candidate falls short of design section 13.3's "no
retry", and how to close that is the owner's decision. `candidateOrder` puts the last successful
candidate first, then the tailnet addresses, the MagicDNS names and the LAN addresses.
`CandidateRacer` starts the caller's attempts 250 ms apart (core-session's WSS open and Noise
handshake), takes the first to complete and cancels the rest, ends at once on a pin mismatch, and
closes every winner the caller does not get, however the race ends. It reads its clock from the
caller's coroutine context, so a test runs it on virtual time. kotlinx-coroutines-android is not a
dependency yet: nothing here needs `Dispatchers.Main`, and it arrives with the first module that
does. `Backoff` waits 1 s doubling to 30 s with jitter. `NetworkWatcher` reads `NetworkFacts` from
the default network's callback and from a second one that sees other apps' VPN networks;
`reachability` turns the facts into design section 5.2's verdicts, and `UnreachableTracker` says the
phone cannot reach an instance only when it has a network and every candidate has failed for 30 s.
The tests run the pinned TLS and the WebSocket against mockwebserver3 with okhttp-tls certificates,
one of them signed by an authority the JVM's default trust store is made to hold for that test, a
hand-written server where a message must be fragmented, an upgrade held back or the reading stopped,
the race on a virtual clock, and section 5.2's whole truth table. On the JVM, OkHttp's Android
artifact finds no Android: it prints a stack trace saying so ("Possibly running android unit test
without robolectric") and uses its JDK platform. So these tests prove the pin on the JDK's TLS, and
run android.* code inside OkHttp. The phone's own TLS stack is the device gate's (design sections
12.6 and 15.3), and that is still open: a certificate that is not pinned must come out as
`PinMismatch` there before core-session relies on it.

`core-session` (`io.tezra.fermix.session`) is one paired session with one daemon, for one profile,
from its first race until it ends. It is an Android library with no android.* in it, tested on the
JVM against a fake daemon that answers Noise IK and builds every event from core-protocol's models,
on a virtual clock. `Session.open` races the candidates, runs the IK handshake and sends `hello` at
seq 1 on the winner alone, with the stored cursors and `protocol_v: 2`; a `hello_ack` whose window
leaves out 2, or a protocol v1 daemon's `unsupported_protocol_version`, ends the session as
`OlderDaemon` or `NewerDaemon`. Every frame's seq is the last one's plus one, from 1 on each
connection, and a gap or a replay closes 1002; `event_part` runs are joined, and the reader decodes
one event ahead of the actor, so a connection holds at most two decoded events. A ping goes after 25
s with nothing sent, and two missed pongs reconnect: each pong answers the oldest ping, one that
comes late forgives nothing, and the time a slow announcer keeps the reader from reading pongs is
not held against the link. The hourly `1000 "Noise session lifetime reached"` close reconnects
without a word to the UI unless no link is up again within 2 s; any `1000` within 5 s of `hello_ack`
waits the backoff. 4001 is `Replaced`, 4003 and 4004 are `Revoked`, a certificate that is not pinned
or a Noise key that does not authenticate is `IdentityChanged`, and every other close reconnects
after core-transport's backoff; after 2,880 races in one run the session is `Suspended` until
`resume()`. The outbox lives in the app's `SessionStore`, which one session at a time owns: a
request is persisted, then sent; `accepted` clears it and `error{client_msg_id}` keeps it, failed,
until `remove()` takes it out; one persisted while the drain reads the store is sent after what the
drain read, and none goes twice on one connection, accepted or not. "Run again" is a new request
whose `retry_of` names the failed one, which the app passes, since a run that failed after
`accepted` left the outbox then; `RequestFailed.inOutbox` says which of the two failed. New rows
leave the session only through the app's `Announcer`, one at a time in timeline order; older pages
come as `OlderLoaded` events and are never announced. The session acks a row only once the announcer
has said what it did with it: a row the owner was not told of holds the ack until the owner reads it
(`tla/specs/mobile_push`, PUSH-2), and since the daemon pushes no row that is read, a read that
reaches it releases the ack, which then follows every announcement after it. The ack frontier and
the row that holds it are stored with the cursor, so a restart never acks past such a row; each new
socket hears it again, as the daemon's acked cursor is per socket; and on a page each row's ack goes
before the next row is announced. A row that lands on screen sends `read_state` at once, and the
read frontier only moves forward, never past the head. After each `hello_ack` the session reconciles
before the outbox drains: the ack and the read frontier, `active_turns`, the approval cards against
`pending_approvals` (a list `hello_ack` leaves out skips its step), `request_status` in batches of
32, whose states move the turns (queued or running shows the card, completed or failed ends it),
`mutations_pull` to its end or the rebuild on `mutations_gone`, and the first history pull, forward
from the cursor or, for an empty cache, the newest page backward. A page holding a row its pull did
not ask for, or a `mutations_page` whose `next` does not move on, closes 1002, so the pulls always
move on; a daemon that refuses `request_status` or `mutations_pull` has that step skipped; and only
the daemon's own time counts toward the 30 s it has to answer. `Connected.caughtUp` then ends the
subtitle's `Updating…`, and a silent reconnect keeps it. Each turn is design section 8.2's machine
as a pure reducer, whose table, every state against every event, is a test that fails on a missing
cell; a reconnect that finds a turn over leaves its machine idle, so a request that was only queued
opens again. `indicatorLine` is the working indicator's phrase (onboarding gotcha 19). `state` is a
`StateFlow<SessionState>`, `events` everything else the app is told, in order, `diagnostics` the
last 200 notable things, and `acks` the last 200 acks sent, with how long each waited. A store or
announcer that throws ends the session as `Failed`, and cancelling its scope as `Closed`. The app's
other client events, `push_register` and `push_unregister` first (design section 10,
"Registration"), have no path through the session yet; the push module adds one.

`design` (`io.tezra.fermix.design`) is design section 13.1's language as code, a Compose library
every screen builds on. `FermixTheme` provides it and hands it to Material 3 too, so that Material's
components draw in it: `FermixColors`, light and dark with the visual canon's values, and the six
`Tint`s, with every one of Material's colour roles built from them and never Dynamic Color, and
`textButtonColors(colors)`, the accent as ink for the text buttons Material would draw in the
accent's fill; `FermixType`, section 13.1's type scale in Google Sans Flex, with Google Sans Code
and tabular figures for code and the SAS, and tabular figures in label-small's timestamps;
`FermixShapes` and `bubbleShape(sender, position)`; `FermixSpacing`;
`FermixColumn`, section 13.11's centred column of 640 dp or 480 dp on a window 600 dp wide or more
and the whole window below that, with the window's width class from androidx.window, which needs
no Activity, so a preview gets the class its device has; `FermixMotion`, the named durations, the
standard spring scheme everywhere and the expressive one inside `ExpressiveMotion { }` for the SAS
reveal, Paired and the first chat, and `LocalReducedMotion`, true while the animator duration scale
is 0; `Modifier.controlPlane(edge, colors)`, the tonal surface with its hairline; and
`HapticFeedback.perform(view, use)`, which plays each `HapticUse` with its meaning (`Haptic`: Act,
Refuse, Arrive, Threshold) through its platform constant. The fonts are google/fonts' own files at
one commit, unmodified, under the SIL Open Font License 1.1: `src/main/res/font/` holds them,
`src/main/assets/fonts/` their `OFL.txt` and `TRADEMARKS.md`, which ship in the APK with them, and
`SOURCE.json` the commit and each file's upstream path and sha256, which a test checks. They add
about 2.6 MB to the APK, 2.4 MB of it Google Sans Flex, a variable font with all its axes.

Every preview in a `fermix.android.library.compose` module is also a screenshot test. Roborazzi
draws it on Robolectric, in the JVM, and `verifyRoborazziDebug`, which `check` runs, compares each
image with its reference in the module's `src/test/screenshots/`. Robolectric's Android runtime is a
pinned dependency with its checksum, so the tests never download one. A screen's previews are
annotated `@FermixPreviews`, which draws them twelve times: a compact (412×915 dp), a medium
(673×841 dp) and an expanded (841×673 dp) window, light and dark, at font scale 1.0 and 2.0, each in
`FermixPreviewTheme { }`. The first are the design module's own, in its tests: `SpecimenColour`
(the colours with their names, the six tints as avatars, and Material's Button, TextButton and filled
Card as the theme hands them the design), `SpecimenType` (the type scale with its sizes) and
`SpecimenShape` (a group of bubbles from each sender between the two control-plane surfaces), each
short enough to fit whole in the shortest window at font scale 2.0, so that every token is in all
twelve images. After a change that moves pixels, redraw the references with
`./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true`, which runs the tests every
time, never up to date or from the build cache, and deletes the reference of a preview that is gone;
look at each image that changed, and commit them with that change: a changed reference image is a
review item, since it is what the change looks like. A verify also fails on a reference that no
preview drew (`verifyNoOrphanScreenshots`, which `verifyRoborazziDebug` runs), since a preview that
was renamed, removed or is no longer found would otherwise leave references that look like coverage
and are compared with nothing. Roborazzi's own copies from `build/intermediates/roborazzi` into the
references are off, so a build never writes build state into the source tree. The references are
drawn on Linux x86-64, as CI's ubuntu-24.04 runner draws them. Robolectric does not render alike on
macOS or Windows, so there a verify, and `./gradlew build` with it, is not authoritative; record
references on Linux only. CI's `screens` job fails on an image that differs from its reference beyond
Roborazzi's default tolerance: a pixel whose RGBA moves by less than 0.007 (on 0 to 1) counts as
unchanged, so a colour nudged by a step or two passes there and fails its value test in
`FermixColorsTest` instead. A failure is reported under the name of its preview, which its twelve
windows share, so the job uploads the locator: each `*_compare.png`, named for its preview, window,
mode and font scale, with the reference, the difference and the new image side by side, and the HTML
report with the references it links to. Android's own screenshot plugin is not used: it needs
`android.experimental.enableScreenshotTest=true`, which the Android Gradle plugin 9.4 answers with a
`WARNING:` line on every build, and only a suppression would silence it.

## Build and check

You need JDK 21 to run Gradle and an Android SDK. The app compiles against API 37 (Android 17),
because the Compose libraries require it, and targets API 36. Once the SDK licences are accepted,
the build installs SDK platform 37 and build tools 36.0.0 by itself. Point the build at the SDK with
`ANDROID_HOME` or with a `local.properties` holding `sdk.dir=...` (it is ignored by git).

```bash
./gradlew build                                   # debug and release, lint, detekt, ktlint, dependency checksums, tests
./gradlew test :build-logic:test                  # the JVM tests, the build logic's included
./gradlew verifyRoborazziDebug                    # the screenshot tests against their reference images, and no stale one
./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true   # redraw the references (Linux), dropping stale ones
scripts/verify_protocol_contract.sh               # the vendored contract against its pins
scripts/verify_protocol_contract.sh --source ../fermix   # ... and byte for byte against an engine checkout
scripts/verify_protocol_contract.sh --pinned      # ... and against the pinned engine commit on GitHub
```

Warnings are errors everywhere: Kotlin (`allWarningsAsErrors`, and for the build scripts
`org.gradle.kotlin.dsl.allWarningsAsErrors` in `gradle.properties` and `build-logic/gradle.properties`),
Android lint on the debug and release variants (`warningsAsErrors`, no baseline file), detekt and
ktlint (any finding fails, no baseline), and Gradle itself (`org.gradle.warning.mode=fail`).
`./gradlew build` runs every one of them, over build-logic too. The Android Gradle plugin prints
its own warnings, a deprecated `android.*` option for one, as `WARNING:` lines and still exits 0,
so CI also fails a build whose log has a line starting with `WARNING:` or `w: `; a clean build
prints neither. `./gradlew ktlintFormat` fixes what ktlint can fix by itself.

The configuration cache is on, for speed. A run that reuses its entry skips configuration, and with
it any Gradle deprecation raised there, so a deprecation fails only the run that first meets it.
Before calling a change done, run `./gradlew --no-configuration-cache build`; CI always configures
from scratch.

Every dependency and plugin is checked against `gradle/verification-metadata.xml` (sha256). When
a version changes, regenerate that file from an empty Gradle home, review the diff, and build again:

```bash
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 --no-configuration-cache build
./gradlew build
```

The empty home matters. Gradle records a parent POM or a BOM only when it reads one, and a warm
cache skips them, so a file written from a warm cache fails on CI's cold one. The regeneration
also records only what this machine resolved. `aapt2` is resolved per operating system, so check
that the linux, osx and windows `aapt2` jars are all still listed; add any that went missing, with
the sha256 of the jar from Google's Maven repository. The file trusts without a checksum only the
`-sources` and `-javadoc` jars an IDE fetches for reading, which the build never runs.

## Developer keystore

The daemon refuses any build whose signing certificate is not in the engine's
`android_signers.json`, and it refuses the Android SDK's own debug key. So debug builds are signed
with a key of your own, one per developer machine. The build never uses the SDK key: without your
key, packaging a debug build fails with a sentence saying so. Linting and the tests still run.

Create the key once, outside the repository (`MILESTONE_51_ANDROID_APP_DEVELOPER_ONBOARDING.md`
section 2.5):

```bash
mkdir -p -m 700 ~/.config/fermix-android && cd ~/.config/fermix-android
keytool -genkeypair -v \
  -keystore fermix-dev.jks -alias fermix-dev \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -dname "CN=Fermix Android dev, O=Tezra, C=US"
```

Then describe it in `~/.config/fermix-android/keystore.properties` (mode 0600). A relative
`storeFile` is relative to that file:

```properties
debug.storeFile=fermix-dev.jks
debug.storePassword=...
debug.keyAlias=fermix-dev
debug.keyPassword=...
```

Or set the same four values in the environment instead. When any `FERMIX_DEBUG_*` variable is set,
the environment is used and the file is not read, and the store file must be an absolute path. A
key described in part, in either place, fails the build and names what is missing:

```bash
FERMIX_DEBUG_STORE_FILE=/abs/path/fermix-dev.jks FERMIX_DEBUG_STORE_PASSWORD=... \
FERMIX_DEBUG_KEY_ALIAS=fermix-dev FERMIX_DEBUG_KEY_PASSWORD=... ./gradlew assembleDebug
```

The release key is read the same way, from `release.*` in `keystore.properties` or from
`FERMIX_RELEASE_*`. It is the owner's alone, so on a developer machine it is not configured, and
the release build comes out unsigned. A release build is never debuggable and is shrunk by R8. The
R8 mapping is kept at `app/build/outputs/mapping/release/mapping.txt`.

Before a daemon will pair with your debug builds, your certificate's digest has to reach the
engine's `android_signers.json` through an engine change and an engine release
(`MILESTONE_51_ANDROID_CI_CD.md` section 4.5). Read the digest with
`keytool -list -v -keystore ~/.config/fermix-android/fermix-dev.jks -alias fermix-dev`, then take
the `SHA256:` line in lower case, without the colons. CI signs its debug build with a key it makes
for that run, which no daemon accepts (C5). Pairing needs a real phone with a locked bootloader:
an emulator cannot pair.

## The vendored contract

`contracts/mobile/` is a byte-for-byte copy of the engine's `apps/fermix_core/priv/mobile/`: the
wire protocol (`PROTOCOL.md`, `protocol.schema.json`), the golden fixtures (`fixtures/*.jsonl`), and
the Noise and push vectors that the device gate replays in the JVM tests. `contracts/CHECKSUMS.txt`
pins every file's sha256, and `contracts/SOURCE.json` records the engine commit it came from.
`scripts/verify_protocol_contract.sh` proves that the files, the checksums and the provenance agree,
and that the provenance names the engine repository and its `apps/fermix_core/priv/mobile`, and
states the protocol window the vendored schema declares. The repository and the directory it
compares against are fixed in the script, never read from `SOURCE.json`. CI also checks that the engine branch `SOURCE.json` names carries the pinned
commit, then compares every file with that commit in the public engine repository. Never edit a
vendored file by hand.

It is vendored from the engine's v0.12.1 release (`df8d8a4d` on `main`), and that is protocol v1.
The app is built against v2, which ships with engine stage D1, so the first real re-vendor comes
with it.

To re-vendor from an engine commit `<sha>` that origin carries:

```bash
engine=../fermix sha=<full sha>
rm -r contracts/mobile
git -C "$engine" ls-tree -r --name-only "$sha" -- apps/fermix_core/priv/mobile |
  while IFS= read -r path; do
    mkdir -p "contracts/$(dirname "${path#apps/fermix_core/priv/}")"
    git -C "$engine" cat-file blob "$sha:$path" > "contracts/${path#apps/fermix_core/priv/}"
  done
(cd contracts && find mobile -type f | LC_ALL=C sort | xargs shasum -a 256 > CHECKSUMS.txt)
```

Then update `contracts/SOURCE.json` in the same change: `upstream.commit`, `upstream.branch`,
`retrieved_at`, `protocol_version` and `supported_version_range` (from the schema's
`x-protocol-version` and `x-supported-version-range`), the `provenance_note` saying what changed,
and one `files` entry per file with its digest; the script refuses a protocol window the schema
does not declare, and a branch that does not carry the commit. Prove it with
`scripts/verify_protocol_contract.sh --source "$engine"` (with the engine checked out at `<sha>`)
and with `--pinned`. The daemon ships first: the app never pins a protocol that no released daemon
serves.

## Design

The design is the owner's record. It sits in an engine checkout's `docs/design/`, which the engine
repository does not track, so these files are not on GitHub:

- `MILESTONE_51_ANDROID_COMPANION_APP.md`: the design authority, covering architecture, pairing,
  the wire, UX and the stages.
- `MILESTONE_51_ANDROID_APP_DEVELOPER_ONBOARDING.md`: preparing daemons, Firebase, signing keys,
  the dev loop and gotchas.
- `MILESTONE_51_ANDROID_CI_CD.md`: this repository's pipelines and the release rules.
- `MILESTONE_51_ANDROID_COMPANION_APP_UI.html`: the visual canon.
- `MILESTONE_51_ANDROID_APP_PUBLISHING_CHECKLIST.md`: getting to Play.

## Licence

MIT, see [LICENSE](LICENSE).
