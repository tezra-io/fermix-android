# Fermix for Android

The Fermix companion app for Android phones, written in Kotlin with Jetpack Compose.

Fermix itself is a background service, the `fermix` daemon from the engine repository
([`tezra-io/fermix`](https://github.com/tezra-io/fermix)). This app is a client of that daemon's
mobile channel. It pairs with each of your Fermix daemons (a Mac, a Linux box, a development
checkout) through a QR code. After that it talks to each one over a TLS WebSocket, with Noise
running on top of it, keyed by a device key that lives in the phone's secure hardware and never
leaves it. Each daemon is its own trust domain, and the phone pairs with each one separately.

The repository is at its first stage: the build, its gates, the vendored wire contract, CI, the
Noise layer (`core-noise`) and the wire codec (`core-protocol`) exist, and the app draws its name
and nothing else yet. The design documents are named below.

```
app/                    the application module (io.tezra.fermix)
build-logic/            the convention plugins every module applies, and their tests
config/detekt/          the detekt configuration; there is no baseline
contracts/mobile/       the engine's mobile wire contract, byte for byte, pinned by contracts/CHECKSUMS.txt and contracts/SOURCE.json
core-noise/             the Noise layer under every session (io.tezra.fermix.noise)
core-protocol/          the wire codec: frames, events and the pairing link (io.tezra.fermix.protocol)
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

## Build and check

You need JDK 21 to run Gradle and an Android SDK. The app compiles against API 37 (Android 17),
because the Compose libraries require it, and targets API 36. Once the SDK licences are accepted,
the build installs SDK platform 37 and build tools 36.0.0 by itself. Point the build at the SDK with
`ANDROID_HOME` or with a `local.properties` holding `sdk.dir=...` (it is ignored by git).

```bash
./gradlew build                                   # debug and release, lint, detekt, ktlint, dependency checksums, tests
./gradlew test :build-logic:test                  # the JVM tests, the build logic's included
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
