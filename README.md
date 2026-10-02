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
device key and its attestation (`attest`), the pairing ceremony and the session (`core-session`), the
design language with its screenshot tests (`design`) and the phone's
durable state (`data`) exist, and the app draws its name and nothing else yet. The design documents
are named below.

```
app/                    the application module (io.tezra.fermix)
build-logic/            the convention plugins every module applies, and their tests
config/detekt/          the detekt configuration; there is no baseline
contracts/mobile/       the engine's mobile wire contract, byte for byte, pinned by contracts/CHECKSUMS.txt and contracts/SOURCE.json
core-noise/             the Noise layer under every session (io.tezra.fermix.noise)
core-protocol/          the wire codec: frames, events and the pairing link (io.tezra.fermix.protocol)
core-transport/         the pinned TLS WebSocket, the candidate race and the network facts (io.tezra.fermix.transport)
attest/                 the device key: the hardware gate, the Keystore key and its attestation chain's shape (io.tezra.fermix.attest)
core-session/           the pairing ceremony, and one paired session: hello, the outbox, the cursors, reconciliation and the turns (io.tezra.fermix.session)
design/                 the design language as code, its fonts, previews and screenshot references (io.tezra.fermix.design)
data/                   the instance records, each profile's database and media cache, the launch check (io.tezra.fermix.data)
gradle/                 the version catalog, the dependency checksums and the wrapper
policy/                 permissions.txt, the permissions the release APK requests, exactly
scripts/                verify_protocol_contract.sh, check_release_policy.sh
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
link's `v`, exactly `1` or `2`, is read first and then only that version's parameters, and a plain
decimal past 2 is refused as `NewerLinkVersion`, the scan's "Newer Fermix"; names and
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
`linkCandidate` classifies a pairing link's host, which carries no scope, by the ranges the daemon
draws them from, from the text alone: 100.64.0.0/10 and a MagicDNS name under `ts.net` are the
tailnet, 10.0.0.0/8, 172.16.0.0/12 and 192.168.0.0/16 the LAN, and anything else is no candidate.
`CandidateRacer` starts the caller's attempts 250 ms apart (core-session's WSS open and Noise
handshake), takes the first to complete and cancels the rest, ends at once on a pin mismatch, and
closes every winner the caller does not get, however the race ends. It reads its clock from the
caller's coroutine context, so a test runs it on virtual time. Nothing here needs `Dispatchers.Main`,
so `core-transport` does not depend on kotlinx-coroutines-android; `data` brings that library in,
through Room's Android runtime. `Backoff` waits 1 s doubling to 30 s with jitter. `NetworkWatcher` reads `NetworkFacts` from
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

`attest` (`io.tezra.fermix.attest`) is the phone's device key as pairing makes it, on core-noise.
`HardwareGate.check` is design section 6.1's gate, run at "Get started": Android 15 or newer and
`FEATURE_HARDWARE_KEYSTORE` at version 200, hardware Curve25519, else `SdkBelowFloor` or
`NoHardwareCurve25519`, with no software key to fall back to. `DeviceKeys` is the only code that
makes or deletes a device key: `generate(alias, challenge)` makes an X25519 key for AGREE_KEY alone
in AndroidKeyStore (`KeyPairGenerator` `XDH` on `ECGenParameterSpec("x25519")`, which android.jar 37
names no constant for), attested with the challenge, and returns its chain, leaf first, as an
`AttestedKey`, deleting the key if anything after its generation fails, with that failure the one
thrown and a failed clean-up attached to it; `staticKey` is the
`KeystoreStaticKey` the handshake runs. Each of its calls refuses an alias outside `fermix.device.`
before it reaches the Keystore, so no other key of the app's is ever touched there. It implements `DeviceKeyFacade`, which core-session's pairing
takes, so the JVM tests put a software key in its place. `AttestationChallenge.of(secret)` is
`SHA-256("fermix-mobile-attest-v1" ‖ secret)`, the challenge the daemon derives from its window's
secret. `AliasNames.next` is section 6.1's alias, `fermix.device.`, the first 8 bytes of
`sha256(gateway_pk)` and 8 random bytes, both in hex: one prefix per daemon and a new alias per
attempt, so a second scan never touches a working key. `Chain.validateShape` checks what the phone
can before any socket opens (section 6.2, check 1): one to six certificates of at most 16 KiB
together, each one DER SEQUENCE, the leaf's SubjectPublicKeyInfo id-X25519 with the device key, each
refusal a typed `ChainShapeException`; the signatures, roots, KeyMint facts, challenge, boot state and
revocation are the daemon's. The JVM tests hold the challenge to digests computed by hand, the
vendored link's secret among them, the alias to the vendored `gateway_pk`, the gate to its table, and
the shape check to certificates assembled byte by byte, which the JDK reads as X.509, and to every
root the JDK trusts, and `DeviceKeys` to its refusals of another alias. The Keystore paths are the device gate's, never these tests' (design section
12.6): `DeviceKeys.generate` on a real phone with a fresh alias, the `XDH` name and the curve
accepted, a chain whose leaf carries the key and the challenge and passes `validateShape`, a
`KeystoreStaticKey` agreement with it, `delete` and `exists`, and `HardwareGate.check` on a phone
that reports version 200 and on one that does not. The key's flags of section 6.1 have no JVM check
either, since `KeyGenParameterSpec.Builder` is the framework's: the device gate reads the leaf's KeyMint
extension on the handset and records purpose `{AGREE_KEY}` alone, the security level
`TrustedEnvironment` (never StrongBox), no `USER_SECURE_ID` and no unlocked-device tag, and one
agreement made with the screen locked after the first unlock.

`core-session` (`io.tezra.fermix.session`) is one paired session with one daemon, for one profile,
from its first race until it ends. It is an Android library with no android.* in it, tested on the
JVM against a fake daemon that answers Noise IK and IKpsk2 and builds every event from core-protocol's models,
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

`Pairing.start(link, keys, identity, parts, scope)` runs the pairing ceremony (design section 6.3,
PROTOCOL.md "Noise modes and pairing") as a `PairingHandle` whose `StateFlow<PairingState>` is the
onboarding screens' (section 13.3); a scope that ended already is refused. In order: the link is
checked on the phone, a version-1 link
ending as `OlderFermix` and a candidate outside the LAN and tailnet ranges as `InvalidLink` before
any key exists; a new alias is generated with the challenge of the link's secret, and its chain's
shape checked (`NoSecureHardware` otherwise, nothing dialed), where an alias the Keystore holds
already fails loud and is never deleted, and a key the Keystore made is recorded before a cancel can
land, so a pairing cancelled while the key is made still deletes it; every Keystore call runs on `PairingParts.keystore`, off the
main thread; the candidates are raced with IKpsk2 over the dialer `PairingParts.dialerFor` makes for
the link's own port and `tls_fp` pin (`Reaching`, `Checking` once a pinned socket opens), and the
link's secret is zeroed once the race is over, since each handshake took its copy; `pair_request`
goes at seq 1 with the chain as its raw tail and `cert_lengths` (`Securing`, which `Verify` replaces
at once, so the Connecting screen paces its third line itself), then `Verify` shows the SAS and the
120 s countdown. The device name, `rename()`, lands only before `pair_request` goes out, which is as
the handshake completes and before `Verify` shows; section 13.3, step 5, and the visual canon put the
rename on `Verify`, and which of the two gives is the owner's decision. While the owner decides, a
ping goes every 25 s and two missed pongs are `LostMidWait`. `pair_approved` at protocol v2, naming
the session's profile, gives `Approved`: the instance record's facts and a paired session that took
over the same connection, whose `hello` goes at its next seq (PROTOCOL.md, step 3; design section
6.3's diagram and onboarding section 3 reconnect with `FXM1·01` first, which the wire contract and the
engine do not). `commit(store)` runs the caller's store of the record, which returns the key alias of
the record its write replaced, as data's `InstanceStore.upsert` and `merge` return that record, and
then deletes that key, refusing one that is no device key's or the pairing's own; once commit has
taken the approval, both run to the end whatever becomes of its caller, with no `cancel()` between
them, a caller cancelled before the take takes nothing, and a store that fails undoes the approval.
An approval or a `CannotReach` the caller has not taken on is abandoned when the handle's scope ends:
the secret zeroed, an approval's session closed and its key deleted, `Cancelled`. The ceremony runs
even on a scope that ended before it was first dispatched, so it still releases what it holds and
shows `Cancelled`; one cancelled or failed on the way out zeroes the secret, and a `CannotReach` whose
ceremony failed is never retried. Each call decides and takes what it acts on in one step on the
handle's dispatcher, so a `retry()` or `commit()` that comes while `cancel()` ends the ceremony takes
nothing. Every other ending zeroes the secret first, closes the socket, `1002` after a
protocol error, and deletes the attempt's key, never the old one, publishing the ending even when
that delete throws. Each row of section 13.3's failure table the wire can produce is an ending:
`pair_denied` by reason (`denied` and `cancelled` are `Denied`, `timeout` `Expired`,
`device_disconnected` `LostMidWait`, `attestation` and `platform_unsupported` `AttestationRefused`,
`attestation_unavailable` `AttestationUnavailable`); a pairing handshake the daemon closes `1002`, or
whose message 2 does not authenticate, `Expired`; a `1002` after `pair_request`
`AnotherPairingInProgress`, the likeliest reading, since the engine closes every pairing error with
that same `1002`; a `4001` while the owner decides `LostMidWait`; a pin mismatch `WrongMachine`; a
version refusal `OlderFermix` or `NewerFermix`; every candidate out of reach `CannotReach`, the one
ending `retry()` takes, with a new key; and `cancel()`. `RateLimited` is reserved and never produced:
a fifth failure's address gets the same `1002` before message 2 as a closed window, so it reads as
`Expired` until the contract names it. `PhoneIdentity`'s texts are held to core-protocol's own
`requirePairRequestText`, and `deviceModel` joins `Build.MANUFACTURER` and `Build.MODEL` with a space.
`Verify` prints without its SAS. The tests run the ceremony against the fake daemon, which answers
IKpsk2 with the link's secret, splits the raw tail by `cert_lengths` and checks the leaf carries the
handshake's key; its responder writes the vendored `noise_vectors.json` message 2, handshake hash and
SAS byte for byte, and the SAS the phone shows is that derivation of the daemon's hash.

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

`data` (`io.tezra.fermix.data`) is the phone's durable state, an Android library with no socket,
notification or screen in it, tested on the JVM. An `Instance` is design section 9.1's record, field
for field, and public data alone: the device key lives in the Keystore under its `key_alias`, and a
test holds the written records to having no secret's field or value. Its `id`, `sha256(gateway_pk)`,
is computed, never stored, and every file is keyed by it, never by a host name (onboarding gotcha 8);
the keys are kept as the wire's text and checked when a record is made, with the host, profile, port
and tint, which is one of `TINT_NAMES`, the design module's `Tint` by name, since `data` does not depend
on `design`; a test in `design` holds the enum and the list equal. Protocol v2 supplies fields the record
requires (`pair_approved.push_salt`, the QR's `profile`), so a version-1 pairing cannot make one. The
records are a typed DataStore, `DataStore<Instances>`, written as JSON by a kotlinx.serialization
`Serializer` of the module's own: design section 12.1 says Proto DataStore, and the typed store is
what it needs, while protobuf would be a second codec and toolchain for one file. A file that does not
decode, or holds a record that breaks a rule, is DataStore's `CorruptionException`, never replaced, and
a test holds it to that. `InstanceStore` shows the records as a flow, in the Chats list's order.
`upsert` records a pairing on `pair_approved`: the same daemon paired again replaces its record in
place, with the owner's nickname and tint, and the record it replaced is handed back so the caller
deletes the old key alias (design section 6.1); a pairing always brings a new alias, so one under the
alias the record holds is refused and the live key is never handed back, and no two records may share
an alias. A nickname a pairing brings, from the Paired screen, is held to the rename rule below in the
same write, and a refused one writes nothing. `update` is every later change
in one write, what `hello_ack` reports (host, label, profile, candidates, caps, push platforms) and this
phone's settings (notifications, the last `push_register`), and it refuses a change to what a pairing
set (gateway key, TLS pin, device id, key alias, push salt) or to the owner's nickname and tint.
`merge` is "Pair again" on the row of a daemon that was reinstalled: the new gateway key, of the row's
profile, takes that row's place, nickname and tint, and the old row's databases and media are deleted
(section 9.2); a pairing under that row's alias is refused there too, since the caller deletes it. The
owner names the row; the store never merges by host and profile, which two daemons
on one computer can share, and it does not compare hosts, since a pairing knows only the link's name
until the first `hello_ack`. `rename` takes 1 to 40 characters (code points), trimmed, refused when
another row is titled the same in any case, and null resets the name to the daemon's; `remove` deletes
a record with its files; `reorder` is "Move to top" and the list's manual order. `launchCheck` runs at
launch: an instance whose key alias the injected `aliasExists` does not find, as on an app restored
without its Keystore keys, is dropped with its files (design sections 6.4 and 6.6), and its title is
kept among `repairNotices` in the same write, so the app shows "Re-pair this Fermix" with its name even
after a process death, until `dismissRepairNotices`. The Keystore is asked before that write, never
inside it. Files no record names, left by a removal cut short, are deleted. `ProfileDatabases` opens one Room
database per (instance, profile), keyed by both from day one, at
`<root>/<instance id>/<sha256 of the profile id>/profile.db` beside its `media/` directory; the root
belongs under `noBackupFilesDir`. A `ProfileDatabase` holds the timeline cache, its full-text index, the
notified set, the outbox and the cursors, and its schema is exported to `data/schemas`. A whole row is
stored as its `HistoryMessage` JSON through core-protocol's model, which is what is read back, so a wider
row needs no migration, and its fields again as columns for queries; a `text_done` reply stands in for
its row until the history page brings the row whole, which replaces it, and never replaces a whole row.
`TimelineDao.persist` is the announcer's persist before it shows or notifies, and `persistAll` keeps an
older page in one transaction, whose rows never reach the notified set. `search` is section 13.7's
offline search over an FTS4 index with the unicode61 tokenizer, which folds case and diacritics. The
index's own tokenizer splits each word the owner types into its terms, through an `fts3tokenize` table
the database makes when it opens, so the query reads every character as the index does, by Unicode
6.1's tables and not the JVM's: punctuation on a word is a separator, and a word of no term is left
out. Each word is a quoted phrase, its last term a prefix, so FTS's own syntax is searched for and never
obeyed; newest first. Rows are
inserted or updated, never replaced, since SQLite fires no delete trigger for a REPLACE and the index
would keep the old text. A stored row or request is, byte for byte, what core-protocol's codec puts on
the wire without the envelope, which a test holds against the vendored fixtures. `NotifiedDao` is design
section 10's notified set, read on every path that can alert: rows by `server_seq`, approvals by
`approval_id` and failed turns by `turn_id`; a put is idempotent and says whether it added the entry, in
one transaction with its test of the read frontier, so of two paths racing to announce one id only the
first alerts, and a row already read is never added; `removeReadUpTo` drops the rows a read frontier
covers, compared as numbers, and `removeExpired` the approvals and failed turns, each stamped when it was
put, once no duplicate push of them can arrive: `NOTIFIED_ID_RETENTION_MS`, two push `ttl`s of a day.
`ProfileDatabase.pending` is the outbox as a flow,
in enqueue order, for the queued and failed bubbles of section 13.6. `RoomSessionStore` is
core-session's `SessionStore` exactly, each call one statement or one transaction: the cursors are one
row written when the database is created, so a cursor write that changes no row fails; mutations update
the cached rows they name and skip the rest; a rebuild drops the cache, its index and the server cursor
and keeps the ack and read frontiers and the outbox; the outbox is keyed by `client_msg_id` in the order
it was enqueued, each request as its model's JSON. `MediaCache` keeps one profile's blobs by the
lowercase hex of their SHA-256, checks the digest while it writes and keeps nothing that does not match,
nor any part of a stream that fails, whatever it throws, and making one deletes the partial files of
puts a process death cut short, so a process makes one per directory; it evicts the least recently used
first, a read counting as a use, never the blob a put is keeping, and holds at most
`MAX_MEDIA_CACHE_BYTES` (512 MiB); `size` and `clear` are
section 13.7's "cache size" and "Clear media cache". The schema of each database version is exported
to `data/schemas` and committed, and CI's build job fails when the build changes it, an entity changed
without a new version. The databases run on the bundled SQLite, on the phone and in the tests alike, so
the tests prove the engine and tokenizer the app ships. Room's Android runtime runs on the plain JVM through
`Room.inMemoryDatabaseBuilder` or a file under a test's directory, with no Robolectric: the
`fermix.android.library.room` convention extracts this machine's library from `sqlite-bundled-jvm`,
the same SQLite built for desktops, and points the driver at it, and the tests hand Room a `Context`
that answers database paths and nothing else. The JVM tests hold `RoomSessionStore` to
`SessionStoreContract`, a test of each rule `SessionStore`'s KDoc states, a write made whole or not at
all among them, proven by a trigger that fails the cursor write; and they cover the record's rules and
codec against the vendored pairing link, a corrupt records file, every vendored fixture row read back
equal with its query columns, an older page that fails partway, the search, the notified set, the
outbox's flow, a restart that finds everything as it was, the media cache's digest, eviction and failed
streams, and the launch check with its notices. The app's backup posture, `allowBackup="false"` with data extraction rules and
full-backup rules that exclude every domain, is checked in the release APK itself by the `policy` job.

`feature-onboarding` (`io.tezra.fermix.onboarding`) is design section 13.3, Welcome to Notifications, as
Compose screens over one ViewModel. Each screen is a composable of its state and its callbacks, with no
ViewModel inside: `WelcomeScreen`, `PairScreen` with `RenameSheet`, `ScanScreen` (the frame around a
`preview` slot, which `QrPreview` fills, with the torch a toggle that shows once the camera reports one,
the camera's rationale before the system's prompt and "Camera is off for Fermix" with "Open settings"
after a no; TalkBack reaches "Paste a pairing link" first), `PasteLinkSheet` (one field, Paste and
Continue), `ConnectingScreen`, `VerifyScreen`
(the SAS in two groups landing digit by digit with `SEGMENT_TICK` on the expressive scheme, standing
still under reduce-motion, read by TalkBack digit by digit, the countdown ring), `PairedScreen`, `NameScreen` (section 9.2's question when
a second Fermix would carry the first one's name) and `NotificationsScreen`; and `FailureScreen(case)`,
which draws every failure from `FailureCase`, the one table that pairs each row of section 13.3's
failure table with its icon, strings, primary and secondary actions, and says which failures are
refusals, which play `REJECT`: the `pair_denied` rows, another pairing in progress, the wrong machine
and the two version refusals, as does a link the scan refuses. Every page keeps clear of the system
bars, as the app draws edge to edge. Their words are strings.xml's, the design's verbatim, with the host
as a format argument. `OnboardingViewModel` holds onboarding's part of the back stack (`stack`, above
the app's root) and what the screens show (`ui`): "Get started" runs attest's hardware gate, a scanned
or pasted text is read by `readLink` into a `LinkOutcome` (a link, not a Fermix code, an older or a
newer Fermix, or one field missing or out of range, its candidates' ranges among them), and a link
starts core-session's pairing through `PairingControl` (the handle, or a test's fake),
and `CeremonyDriver` shows each `PairingState`'s screen as the pure `screenFor` maps it: Connecting's
three lines, with "Trying Tailscale…" after 4 s of reaching a tailnet candidate and "Securing the line…"
paced for 600 ms, as no state holds it; Verify with its code, its end and the name `pair_request`
carried; `pair_approved` stored through data's `InstanceStore` with an auto-picked tint before Paired
shows, the replaced record's key handed to the commit to delete, and the paired session the approval
hands over closed once the record is stored, until the Chats list takes sessions on; and every ending
one failure screen, "Can't reach" read through section 5.2's reachability, "Try again" retrying only the
link "Can't reach" holds, a wrong machine never retried. Leaving the ceremony's screens cancels an
attempt not yet approved. The pure rules are `stackOf`, `topOf`, `screenFor`, `keyAfter`, `stepAfter`,
`connectingPhase`, `appBackStack`, `securesWindow` and `darkUnderBars`.
`QrPreview` binds CameraX's preview and a 1280×720 analysis (16:9, the latest frame only, at the
camera's own rate) to the screen's lifecycle, on the back camera or the front one, and reads each frame
with zxing-cpp's Android binding in process, through `qrReader()`: QR codes of Model 2 alone, the model
`fermix pair` draws (zxing-cpp's `QR_CODE` is the whole family, Micro QR and rMQR with it), inverted ones
too, as a dark terminal draws `fermix pair`'s, the native library loaded on the analysis executor, which
the screen owns and shuts down as it leaves (`qrAnalyzer` reads a frame, and `deliver` hands each code
on once until another is read). A torch switch the camera does not carry out is logged. Its rules that
need a camera, the torch shown only for a bound camera with a flash unit, and the use cases unbound, the
executor shut down and no torch reported as the screen leaves, run in no test, as the tests have no
camera (AGENTS.md): they are the device gate's, on a phone.
`PasteSheetModel` holds the paste sheet's field in the ViewModel, so a rotation keeps a half-typed link
while the saved state, which outlives the process, never holds the secret. Paste and "Continue" take the
text's first word that starts `fermix://pair?`, so `fermix pair`'s "Manual pairing URI: …" line copied
whole from a terminal, its end with it, gives its link. A clip with a pairing link in it, whatever the
phone makes of the link, is cleared once Paste reads it, or once "Continue" takes or refuses a link;
a clip with none is the owner's own and stays.
`onboardingEntries(builder, viewModel, camera, clip)` registers the screens as Navigation 3 entries,
with what lies outside the app: the pages, the Tailscale app (seen through the
manifest's `<queries>`), the VPN settings, the app's settings page, the camera and notification prompts,
the camera (`phoneCamera()`, or a test's stub) and the primary clip (`clipboardClip`, or a test's fake).
The manifest asks for `CAMERA` and requires no camera: `camera.any`, `camera` and `camera.autofocus`,
which `CAMERA` would otherwise imply, are all declared not required, as the pasted link is the other way
in. An entry hands an
action to the ViewModel only while its screen is the one on top (`topOf`): a screen popped off the stack
is still drawn, and hit, as it leaves, and the ViewModel holds each call to the screen it belongs to.
The JVM tests cover the route rules, `screenFor` over the ceremony's twenty-one states among them, the
failure table verbatim with suj-mbp as the host, the record's tint and name rules, the ViewModel over a
fake ceremony and real records (the pairing-wait facts cleared on every outcome and on the ViewModel's
end, the replaced key handed to the commit, the session closed), every `LinkOutcome`, the paste sheet
clearing a clip with a pairing link in it whatever the link's outcome, `fermix pair`'s labelled line
among them, and no other clip, a link with blank or that label around it taken, the reader's options
(QR codes of Model 2 alone, inverted too), a code read frame after frame handed on once, and on
Robolectric every action's label in the semantics, the order TalkBack reads the scan in with the camera,
its rationale and its denial, the code read digit by digit and still under reduce-motion, the torch's
state, a scanned link's `CONFIRM`, the refusals' `REJECT`, the paste sheet's read out too, "Open
settings" opening the app's own page in the system's settings, Welcome's actions above a navigation
bar, a rotation that draws Verify again from the kept ViewModel with its countdown running on, and
keeps the paste sheet's half-typed link,
and Welcome's and Paired's mark drawn where it arrived when the screen is drawn again. Its instrumented
tests run the same screens on an emulator (below). Every screen and every failure is a preview at the twelve
windows, each failure a preview of its own named for its case, with references under
`feature-onboarding/src/test/screenshots`, the SAS in them the vendored IKpsk2 vector's.

Where the code departs from section 13.3, or reads it where it is silent or says two things, for the
owner to settle:

- The phone's name is set on Pair, not on Verify as step 5 draws it: core-session takes the name as the
  handshake completes, before Verify shows.
- "Paste a pairing link" is left out of Older Fermix, Newer Fermix and No secure hardware, where a new
  link cannot help, as the visual canon leaves it out of Wrong machine and Attestation refused; the
  table gives every failure both secondaries.
- `PairingState.ProtocolError`, a daemon that answers what the ceremony does not take, has no row in the
  table. Its screen borrows section 13.9's generic "Something went wrong on {host}" as its title, with no
  body, until the owner gives it words.
- Step 3's own lines for an older and a newer code ("Update Fermix on suj-mbp — this code is from an
  older Fermix." and "This code is from a newer Fermix. Update this app.") are not used: the table's
  Older Fermix and Newer Fermix screens stand in their place. The design gives both.
- "Trying Tailscale…" shows after 4 s of reaching only when a tailnet candidate is among those tried;
  step 4 says "after 4 s" with no condition.
- The SAS digits rise 12 dp and fade in, one after another. Section 13.10's "land like a combination
  lock" could also mean each digit rolling through figures to its value.
- Can't reach's primary cell in the table is "Try again · Open Tailscale", two actions where the rule
  of section 13.3 gives a screen one. "Try again" is drawn as the one primary, and "Open Tailscale" as
  the first secondary, before "Paste a pairing link" and "Troubleshooting".
- Step 3 asks for a rationale before the camera's prompt without giving its words. "Allow the camera to
  scan the code" and "The code is read on this phone and never leaves it." are placeholders for the
  owner to settle; its action is step 6's "Continue".
- Under reduce-motion the code stands landed from its first frame, with no ticks: section 13.1 says only
  that springs snap, and digits snapping one after another would still move.
- A version-1 link shows Older Fermix at once, from the link alone, and a link whose candidates fall
  outside the LAN and tailnet ranges is refused at the scan, as "That's not a Fermix pairing code.",
  before any ceremony starts.
- Section 12.4 clears the primary clip "after reading a pasted link", which could also be read as a link
  that parses. A text with `fermix://pair?` in it carries a secret whatever the phone makes of it, so a
  clip holding one is cleared, a link refused for its fields or its candidates and a newer Fermix's among
  them, and `fermix pair`'s whole "Manual pairing URI: …" line when "Continue" takes the link trimmed
  out of it by hand; any other text is the owner's and stays. "Continue" reads the clip only when the
  field holds a pairing link, and that read shows Android's "pasted from your clipboard" note, as Paste's
  own read does.
- Paste and "Continue" take the first word of the text that starts `fermix://pair?`, so `fermix pair`'s
  labelled line copied whole pairs as its link does. Step 2 names the link alone; the label could instead
  be refused as "That's not a Fermix pairing code.".
- A phone with no camera may install the app (the manifest requires none) and is offered the scan all
  the same: the rationale, the prompt, then the reticle and the hint over the camera's stand-in, with
  only "Paste a pairing link" that works, and a log line. The design does not say what such a phone
  sees: Pair could leave "Scan the code" out, or the scan say there is no camera.
- At the expanded window (841×673 dp) at font scale 2.0, two pages outgrow the window: Pair's "Paste a
  pairing link" and Attestation refused's "Troubleshooting" sit below the fold, cut by the window's
  edge, reached by scrolling, with nothing on screen to say the page scrolls (the expanded 2.0
  references of `PairPreview` and `FailureAttestationRefusedPreview`). The primary action is in view on
  both. Dropping the top margins on a short window, or a fade above the actions, would bring them in;
  the canon draws no window that short.

The app wires it: `FermixApplication` makes `AppServices` once (the records, the network watcher, the
device keys, the connector), `MainActivity` shows `appBackStack` in `NavDisplay` under `FermixTheme`,
Welcome until a Fermix is paired and a placeholder for the Chats list after, fits the window to the top
screen (`fitWindow`): `FLAG_SECURE` while an onboarding screen shows (`secureWindow`), and white system
bars with no contrast scrim over the scan's camera, which is dark in both modes as the canon's
`.phone.bleed` draws it, the theme's bars everywhere else; and on leaving Verify for another app it
starts `PairingWaitService`, the short foreground service of section 12.5, "Waiting for approval on
suj-mbp · 1:42", which ends itself when the wait ends or the app comes back (`pairingWaitShown` decides
both). The app's tests run on Robolectric through the application convention, over the app's own
services: the window's flag on Welcome and its clearing on the Chats list, the bars over the scan in
light mode and back after it, a second tap on a failure screen as it leaves dropped, the service started
as the app leaves Verify and not otherwise, and the service ending itself.

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
./gradlew :feature-onboarding:connectedDebugAndroidTest   # the instrumented tests, on the device adb sees (below)
scripts/verify_protocol_contract.sh               # the vendored contract against its pins
scripts/verify_protocol_contract.sh --source ../fermix   # ... and byte for byte against an engine checkout
scripts/verify_protocol_contract.sh --pinned      # ... and against the pinned engine commit on GitHub
scripts/check_release_policy.sh app/build/outputs/apk/release/app-release-unsigned.apk   # the release APK against the policy
```

A local build with no release key writes the release unsigned, as `app-release-unsigned.apk`; with
`release.*` in `keystore.properties` or the `FERMIX_RELEASE_*` variables it is signed, as
`app-release.apk`, which is what CI checks. The script reads either alike.

`scripts/check_release_policy.sh` reads a release APK with the build tools' `aapt2` and checks what
CI/CD design section 3 asks of it: minSdk 35 and targetSdk 36, not debuggable, `usesCleartextTraffic`
false with no network security config to override it, no `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`,
`allowBackup` false with backup rules that exclude every domain under cloud backup, device transfer and
the older platforms' full backup (design section 6.4) in every configuration of the rules, an override
such as `res/xml-v36/` included, the requested permissions exactly those in `policy/permissions.txt`,
no camera required (no feature the manifest declares or `CAMERA` implies names a camera as required, and
`android.hardware.camera.any` is declared not required, as the pasted link is the other way in), and no
test material: no entry named as a file of `contracts/mobile/`, under a `fixtures/` directory, or
with a key store's, a key's or a fixture's extension, no entry holding the bytes of a vendored file
under any name, and no entry holding a key of the vendored vectors (every private and public key, psk,
salt, secret and key field of `noise_vectors.json` and `push_vectors.json`) as hex of either case or as
base64. A backup rule counts only where a phone reads it, as AOSP's `FullBackup.java` does: as a child
of its section, read off `aapt2`'s tree by depth, so a section left empty beside rules outside it
excludes nothing, and anything but a rule inside a section fails the check. It reads the APK with
`aapt2`, `unzip` and `jq`, and prints every check that fails with what it expected and what it found,
and what it searched for, then exits 1; a missing or failing tool, grep included, and a missing or empty
reference stop it at once with status 2. Its `aapt2` is build tools
36.0.0's, the Android Gradle plugin's default, which `gradle/libs.versions.toml` notes beside `agp`.
CI's `policy` job builds the release with a key made for the run and runs it. A permission the app
starts to request lands in `policy/permissions.txt` in the same change.

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

## Instrumented tests

A `fermix.android.library.compose` module's `src/androidTest` runs on an emulator or a phone, with
AndroidX Test's runner, Compose's test rule and Espresso 3.7 (Compose's own 3.5 cannot start on API 36).
`check` builds the test APK (`assembleDebugAndroidTest`), so `./gradlew build` holds the tests to every
gate; running them needs a device. Today `feature-onboarding` has them, and they need no daemon and no
camera: `OnboardingTestActivity` shows the entries in `NavDisplay` over a `TestRig` kept in the
activity's ViewModel store, which holds the fake pairing control (`FakeStarter`, from `src/sharedTest`,
which the JVM tests compile too), the gate's answer, the network facts, a stub preview that reads what a
test hands it and reports a torch, an `ActivityResultRegistry` that answers the camera prompt, and a fake
clip. They cover onboarding from Welcome through Scan to Paired, every failure screen with its title and
labelled actions, the scan's refusal, the camera denied with "Open settings" and the paste clearing only
a clip with a link in it, a rotation and a fold that keep Verify's code and countdown and the paste
sheet's text, TalkBack reaching "Paste a pairing link" first, reduce-motion's still code with the setting
the owner sets, the scan's own reader (`qrReader()`) on real QR, inverted QR, Data Matrix and Micro QR
images (`src/androidTest/assets/codes`), and its analysis (`qrAnalyzer`) on camera frames made of them,
and `clipboardClip` on the phone's own clipboard. The test APK is signed with the SDK's debug key; it is
not the app, and pairs with nothing. A test that needs the window's focus, Espresso's back and the
clipboard's read, waits for it (`awaitWindowFocus`) and fails naming the window that holds it.

CI's four legs run Google APIs images of API 35 and 36, as `medium_phone` and as `pixel_fold`. Make the
same AVDs with cmdline-tools 20.0 or later (12.0 knows no `pixel_fold`); a green run on another image,
Android Studio's 36.1 Play Store one among them, says nothing of CI's: the fold once failed on CI's API
36 image alone.

```bash
sdkmanager "system-images;android-35;google_apis;x86_64" "system-images;android-36;google_apis;x86_64"
for api in 35 36; do
  for profile in medium_phone pixel_fold; do
    echo no | avdmanager create avd -n "ui_${api}_$profile" -d "$profile" -k "system-images;android-$api;google_apis;x86_64"
  done
done
```

Locally, run the tests as a script, with `emulator` and `adb` on the PATH; its trap and its exits end
the shell that runs it, so it is not for pasting into a terminal:

```bash
serial=emulator-5554
emulator -avd ui_35_medium_phone -port 5554 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim &
pid=$!
stop() {                                     # stops the emulator however the run ends, and waits for it
  adb -s "$serial" emu kill
  for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
  echo "the emulator is still running after 60 s: stop it by its pid, $pid"
}
trap stop EXIT
timeout 120 adb -s "$serial" wait-for-device || { echo "$serial did not come up in 120 s"; exit 3; }
for try in $(seq 150); do                    # 150 tries 2 s apart, then give up
  [ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break
  [ "$try" -lt 150 ] || { echo "$serial did not boot in 300 s"; exit 3; }
  sleep 2
done
ANDROID_SERIAL="$serial" scripts/settle_emulator.sh || exit 3
adb -s "$serial" shell svc power stayon true
ANDROID_SERIAL="$serial" ./gradlew :feature-onboarding:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.tezra.fermix.onboarding.FoldingPhone
```

`ANDROID_SERIAL` names the device to use. `scripts/settle_emulator.sh` waits, 30 polls 2 s apart, for
the home screen to have the focus, as a cold boot can leave a system dialog holding it, System UI's
"isn't responding" among them; each poll wakes the phone, dismisses the keyguard and closes system
dialogs, and it fails naming the window that has the focus. The fold test (`@FoldingPhone`) folds the
phone and waits for Verify drawn again; a Pixel Fold locks as it folds, and the test activity shows over
the lock screen (`showWhenLocked` in `src/androidTest/AndroidManifest.xml`), as the lock can come after
the activity is made again. On a device whose `cmd device_state` cannot close it, its assumption skips
it, and AGP's report counts that skip among the failures while the task passes, so on a phone leave it
out with the `notAnnotation` above; on a folding AVD run it with
`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`, which turns that skip into a failure.
Animations stay on: the reduce-motion test sets the animator scale itself and puts it back.

CI's `ui` job runs them on API 35 and 36, as `medium_phone` (the fold test left out) and as
`pixel_fold` (the fold required), four runs side by side on `ubuntu-24.04` with KVM opened by a udev
rule, through `reactivecircus/android-emulator-runner` (pinned by commit). The runner's cmdline-tools
are 12.0, which know no `pixel_fold`, so the job replaces them with 20.0, checked by its sha256, and
looks the profile up before any emulator starts. It builds the test APK before the emulator starts, so a
compile error is never taken for a boot that failed. The action's pre-launch script marks the AVD made,
and the script settles the emulator (`scripts/settle_emulator.sh`) and marks the boot before the tests:
only when the AVD was made and the emulator never booted or never settled do the tests run once more,
and a setup that failed before the AVD existed is not retried. The job's summary
says which happened, or that the tests never ran, and whether KVM was open, and the reports are
uploaded. `gate` requires it.

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
