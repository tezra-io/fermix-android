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
push/                   a push's envelope, keys, trial decryption and plaintext, and its diagnostics lines (io.tezra.fermix.push)
gradle/                 the version catalog, the dependency checksums and the wrapper
policy/                 permissions.txt and exported.txt, the permissions the release APK requests and the components it exports, exactly
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
after core-transport's backoff, the state no longer `Connected` through the wait (a live `1002` among
them); after 2,880 races in one run the session is `Suspended` until
`resume()`. `close()` ends it for good and returns only once its run has stopped and every request made
before it, `send`, `retry`, `stop`, `markRead` and `remove`, which run in their caller's coroutine, has returned;
one made after it is refused, so a closed session touches its store no more. An event such a request says,
`markRead`'s read frontier or an approval's answer, waits while 256 events wait for the app, and lets go
as the session ends, so a close never waits on a collector the app stopped first. The outbox lives in the app's `SessionStore`, which one session at a time owns: a
request is persisted, then sent; `accepted` clears it and `error{client_msg_id}` keeps it, failed,
until `remove()` takes it out; one persisted while the drain reads the store is sent after what the
drain read, and none goes twice on one connection, accepted or not. "Run again" is a new request
whose `retry_of` names the failed one, which the app passes, since a run that failed after
`accepted` left the outbox then; `RequestFailed.inOutbox` says which of the two failed. `stop` is never
queued: `command{name:"stop"}` goes at once over the connection that is up, or not at all, so a Stop
pressed offline never stops a later turn. Only the owner's own `msg` opens a card at `accepted` (section
8.2); a command the session wrote shows a card only from its `turn_started`, and its answer written inline,
a bare `text_done` on a turn nothing of which shows, ends that turn there, since the daemon answers `/stop`
before its queue and sends no `turn_done` after it. Each `Turn` event carries the turn machine's
`daemonSpeaking` after its step, which the indicator reads. New rows
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
last 200 notable things, `acks` the last 200 acks sent, with how long each waited, and
`lastSuccessful` the candidate the last `hello` went over, the one the next race tries first. A store or
announcer that throws ends the session as `Failed`, and cancelling its scope as `Closed`. The app's
other client events, `push_register` and `push_unregister` first (design section 10,
"Registration"), have no path through the session yet; the push module adds one.

Beside the outbox, a session asks and waits for four answers, each once over the connection that is up and
reconciled and never queued: `Session.search` (`history_search`, 20 hits a page before a `before_seq`, its
query at most 256 scalars, quoted in no diagnostic), `Session.pullModels` (`models_pull`, every page of one
answer in order, 64 at most), and `Session.fetchMedia` (`media_fetch`, the blob's chunks written to the
caller's file as they come, its size and SHA-256 checked against `media_begin` and `media_end`, a mismatch
thrown as `MediaMismatchException` and the file deleted on every way it ends unanswered). Each ends as a
typed `OneShot`: `Answered`, `Offline` without a connection, `Busy` past 8 waiting of its kind, `Refused`
with the daemon's code, `TimedOut` after 30 s without its answer or its next part, and `Interrupted` when
the connection ends first; a caller that is cancelled gives it up. Answers are matched as the daemon gives
them: a `search_results` goes to the oldest search waiting for the `query` it echoes (design section 7), and
one no search waits for is dropped, said in a diagnostic that never quotes it; a `models` page goes to the
oldest pull until the page with no `next`; a blob's frames go to the fetch of their ref, from `media_begin`
to `media_end` or `error{ref}`, whatever other blob's frames come between them, since the engine pushes a
message's media between a fetch's chunks. An `error` naming neither a request nor a ref is the app's to hear
(`SessionEvent.Refused`): no code tells a refused search or pull from the connection's other refusals
(`request_backlog_full`, `unsupported_event`), so none is taken for one, and a search or a pull the daemon
refused that way ends as `TimedOut`. A caller that gave up keeps its place for the 30 s an answer takes, so
an answer on its way is not taken for a later search's or pull's; past them it is let go, as one the daemon
refused gets no answer at all, so a "Try again" of the same query gets its own page and refused ones never
leave the connection `Busy`. A fetch holds at most 65 replies its reader has not taken; one that falls
further behind throws `MediaMismatchException`. The fourth, `Session.answerApproval`, is an outbox `command`
like any other, at least once, under an id that starts with `approval-answer:`: the session keeps each
card's approve and deny routes and its token, the app hears the card (`SessionEvent.Approval`,
`ApprovalResolved`, `ApprovalClosedWhileAway`, `ApprovalAnswered`) without them, and a card it does not
show, one answered already or one past its `ttl_s` is not answered. An answer that fails, before or after a
reconnect, ends no turn and gives the card back. The daemon writes the answer as the owner's row, its route
and token as the words: the session keeps that row without its words, live or paged, and so does the store's
mutation of it, so the token is never kept, announced or in the phone's index; and `Session.search` drops a
daemon hit on an answer, an owner's excerpt that is one of the daemon's routes or a route a card named and
then one token-shaped word, as the answer's row is no more than that, while the owner's own "confirm the
booking…" is found. The outbox item carries the token until the daemon accepts it, and the chat never draws
it. A reaction and a link preview are kept on the row they name in the store (`RowEdits`), then told as
`SessionEvent.Reaction` and `LinkPreview`; `model_changed` is `SessionEvent.ModelChanged`, and a `models`
answer none asked for, a `/model` command's, is `SessionEvent.Models`. The tests run each against the fake
daemon and the vendored fixtures: the order, the bounds, a refusal and the same search or pull asked again,
the timeout, a cancelled caller and one cancelled as its file opens, a mismatched blob, nothing sent or kept
for later while offline, and a card's token never in what the app hears, keeps or finds.

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

Attachments go before their `msg` (design section 8.5, PROTOCOL.md "Attachments"). `send(request,
attachments)` puts the item in the outbox with each attachment's staged file, digest, type and size
(`OutboxAttachment`), and the session's `Uploads` takes the outbox's items with an attachment still to go, one
item at a time and one attachment at a time, in order: `attach_begin`, then 60 KiB `attach_chunk`s paced
against the socket's queue (at most sixteen chunks unwritten, and a queue that does not drain in 30 s stalls
the upload), then `attach_end`. An `attach_status` of `present` for a digest the daemon already holds skips the
bytes. Each attachment the daemon holds is marked in the store, and the `msg` goes, through the outbox like any
other, only once every one is in: never before the last `attach_end` is answered. The outbox's `msg`s reach
the daemon in its order: one that cannot go yet, as it uploads, waits for a turn or waits behind another,
holds every later `msg` back, while a `command` passes. The daemon drops a partial upload with its connection,
so a later connection starts each attachment not yet in again from `attach_begin`, at most
`MAX_UPLOAD_RESTARTS` (3) times per item, and the connection that cuts the last restart fails the item
`upload_interrupted`; a staged file that no longer holds the bytes its attachment announced fails it
`upload_source_changed`, and the daemon's upload refusals fail it with their code. Those name no upload, so
the one on its way takes them: a refusal while its chunks go stops them, with no `attach_end`, and the late
refusals of a dropped upload (`unknown_upload` for each chunk it still had, never an answer to an
`attach_begin`) are let go before the next attachment's answer. An upload stalls when an `attach_begin` or
an `attach_end` has no answer within 30 s, when the socket's queue does not drain, or when the daemon answers
`request_failed`, which names nothing: the connection then ends (`Ending.UploadStalled`, reconnecting at once,
as the uploads are one of the connection's endings), the item shows "Upload interrupted" with no `msg` sent,
and the next connection starts the attachment again, a restart like any other. `uploads` is each
attachment's progress by its `attach_id` (`UploadProgress`, whose `UploadStage` the bubble's ring and its one
line read), and `uploading` says whether an upload is in flight, from an item's first `attach_begin` until its
`msg` goes or the connection is cut, which the app's supervisor and its upload service follow. The tests run
the uploads against the fake daemon: the vendored frames in order, the item stored before any frame, the
`msg` never before its last `attach_end` is answered, chunks of at most 60 KiB adding up to the source, a
`present` digest with no chunk, a reconnect's restart from `attach_begin` that skips what is in, the item
failed as its third restart is cut, a stored item with its restarts spent failing and one with a start left
taking it, a refusal, a refusal mid-chunks, a dropped upload's late refusals, a daemon silent after
`attach_begin` or after `attach_end` and a `request_failed` each ending the connection in 30 s to 60 s with
the item interrupted and started again on the next, a text `msg` written behind a stalled upload only after
it, a changed source, an item removed mid-upload, the paced socket, the in-flight flag, the `msg`s' order
behind an upload while a second offer comes, and the limits on attachments.

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
references on Linux only. The code card's tint is computed as the card composes, never on a thread of
its own, so it is in the first frame and its references cannot race a slower runner (`CodeCardTest`).
CI's `screens` job fails on an image that differs from its reference beyond
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
records are a typed DataStore, `DataStore<Instances>`, written as JSON through kotlinx.serialization by an
`OkioSerializer` of the module's own: design section 12.1 says Proto DataStore, and the typed store is
what it needs, while protobuf would be a second codec and toolchain for one file. A file that does not
decode, or holds a record that breaks a rule, is DataStore's `CorruptionException`, never replaced, and
a test holds it to that. `InstanceStore` shows the records as a flow, in the Chats list's order, and every
read of them, once or following, is taken under the store's write lock (`lockedReads`, `updateData` with a
transform that writes nothing), so a reader started during a write shows that write once it ends: DataStore
1.2.1's own `data`, started then, reads the file without the lock and keeps what it read until the next write,
and one whose read lands as the write moves its file in finds none and answers with no records at all
(`ReadsDuringWritesTest` holds the first, a run on Robolectric counted the second). Each transform, a
read's or a write's, runs in place on the store's thread (`locked`), not in its caller's context, where
DataStore runs it while it holds the lock, so a caller on a busy main thread holds no read behind it. The
file is written through DataStore's `OkioStorage` (`atomicDataStore`), whose one rename puts the written file
in place, where DataStore's own file storage deletes the old file first on Android 8 and later, so that a
process killed in that instant would leave no records; the serializer emits what it wrote before it returns,
as OkioStorage syncs the written file then, before the rename, and a byte left in the sink's buffer would reach
the file only after that sync. The settings (`AppSettingsStore`) are kept, read and written the same way. Each
store makes its DataStore from its file and holds it alone (the constructor handed one is the module's, for its
tests), and DataStore is the module's dependency, not its API, so no other module can read around the lock.
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
without its Keystore keys, is dropped with its files (design sections 6.4 and 6.6), and it is kept
among `repairNotices` by its id, with its title, in the same write, so the app shows "Re-pair this
Fermix" with its name even after a process death, until a pairing brings that daemon back (`upsert` and
`merge` take its notice in their own write) or the owner removes the notice (`dismissRepairNotice`); a
notice is keyed by the id, never by the title, which two daemons on one computer share, and never names a
record that is there. The Keystore is asked before that write, never inside it. Files no record names, left by a removal cut short, are deleted. `ProfileDatabases` opens one Room
database per (instance, profile), keyed by both from day one, at
`<root>/<instance id>/<sha256 of the profile id>/profile.db` beside its `media/` directory; the root
belongs under `noBackupFilesDir`. `ProfileDatabases` orders its readers against a removal; a session,
which holds its database outside them, is ordered by the app's supervisor (below). Room 2.8.5 ends
no flow as its database closes: one reading hangs on, one whose query comes after fails with
`IllegalStateException`, and a suspend call after it throws a `CancellationException`, which cancels its
caller's coroutine with nothing reported, as a test holds. So a database or a media cache is read through
`observe`, a flow, or `withDatabase` and `withMediaCache`, one use each, a use being whatever block its
caller passes, and every reader is counted from its start to its end, a cancelled flow until its last query
has ended; `delete`, which `remove`, `merge` and the launch check run once the record is gone, first marks the
instance gone, which ends its flows and refuses every reader after it, `open` included, with the typed
`InstanceGone`, then waits for the count to reach zero, and only then closes the databases and deletes the
files. It waits at most `RELEASE_WAIT_MILLIS`, 10 s, which is all that bounds a use, and past it fails loud,
the files left for the next launch's check. Gone means deleted in this process: no record is looked up, so
after a restart an id no record names opens as any other, and the launch check deletes the files of such an
id. A removed daemon paired again is admitted back (`admit`): `upsert` and `merge` admit it
before they write its record, and so does a pairing's session, which reads its store before that write. A
`ProfileDatabase` holds the timeline cache, its full-text index, the
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
lowercase hex of their SHA-256 (`isMediaName` tells a name it takes, as its reads and puts refuse any
other), checks the digest while it writes and keeps nothing that does not match,
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
codec against the vendored pairing link, a corrupt records file, a reader of the records or the settings
started while a write is held (on one thread, by a serializer that waits) showing what it wrote, once and
following, where DataStore's own `data` started then does not, a reader or a writer whose own thread is held
keeping no reader waiting, each write of the records and the settings whole in its file when OkioStorage syncs
it (`SyncedWritesTest`), every vendored fixture row read back equal with its query columns, an older page
that fails partway, the search, the notified set, the
outbox's flow, a restart that finds everything as it was, the media cache's digest, eviction and failed
streams, and the launch check with its notices; and the readers against a removal, on a dispatcher of their
own that the test advances by hand: a reader that holds a database ends as its instance is removed or
dropped and the files go only then, a reading the removal cancels holds it until its last query has ended,
one that has not read yet ends with no value and makes no file, a use holds the removal until it returns
its value, the files of an instance removed in this process are refused until a pairing admits them again,
a reader that never lets go fails the removal after the wait, and Room's own close, of a flow and of a
suspend call. The app's backup posture, `allowBackup="false"` with data extraction rules and
full-backup rules that exclude every domain, is checked in the release APK itself by the `policy` job.

`push` (`io.tezra.fermix.push`) is the phone's side of a push before anything is shown (design section
10), an Android library with no Firebase in it and no `android.*` but what its records bring. `PushEnvelope`
reads FCM's `data` map, `{"v":"2","n","c"}`, within its bounds: the whole map at most 4,096 bytes, `v`
"2", a 12-byte nonce and the 2,064 bytes of a sealed 2,048-byte bucket, in standard base64, and refuses
anything else by the field's name. `PushKeys.derive` is `HKDF-SHA256(salt: push_salt, ikm: X25519(device,
gateway), info "fermix-push-v1", L 32)`, on core-noise's RFC 5869 `hkdfSha256`, and `PushCipher.open`
ChaCha20-Poly1305 with no associated data, a tag that does not verify being no error but another key's
push. `TrialDecrypt` tries each instance's push key in record order, one Keystore agreement each through
attest's `DeviceKeyFacade`, until a tag verifies; the instance it names, and with it the notification's
channel, shortcut and tap, is the record whose key verified, never a field of the plaintext, and a key the
Keystore lost is logged by the instance's id and passed over. It is bounded by the records it is handed and
by its caller's budget: once its coroutine is cancelled it starts no further agreement. `readPushPlaintext` takes the bucket apart (the
JSON, one 0x80 byte, then zeros) and types its `kind`: `message` (`profile_id`, `server_seq` from 1 to what
the store's signed 64-bit column keeps, `preview_text` or null), `approval` (`approval_id`, `expires_at`), `turn_failed` (`turn_id`, `code`), and
any other kind or none as `Unknown`; a field is shown as text at most. `registrationStep` decides what a
connection sends, `push_register` while a daemon that pushes through FCM may show its notifications and
none was sent since the last token or 7 days ago, `push_unregister` once they cannot show. `PushLog` keeps
the last 200 push lines, `decision[:instance] [detail]`, with no token, key, salt or word of a plaintext, and
the lines of a push no key opened in a ring of their own, so that anyone who floods the token with pushes
never pushes out what became of a paired Fermix's.
The JVM tests replay `contracts/mobile/push_vectors.json`, which is protocol v1's APNs vector only: its
agreement, push key and sealed plaintext gate the derivation and the cipher. The FCM cases, one per kind,
are built in the tests from that vector's keys, salt and nonce (`ProvisionalFcmCasesTest`) until engine
stage D1's export brings its own. The instrumented test generates an X25519 agree key in the device's
AndroidKeyStore, as a pairing does, and opens a push sealed for it at test time, behind a record whose key
the Keystore does not hold, and again with the screen locked behind a PIN set for the test and cleared after
it, the key made while unlocked; what an emulator cannot show is the owner's device gate (onboarding section
6): a phone's TEE or StrongBox doing the agreement, locked or not, its time, FCM's delivery to a phone asleep
and locked, and attestation to Google's root.

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
the app's root) and what the screens show (`ui`): "Get started", and the Chats list's "Add Fermix" and a
trust screen's "Pair again" through the same `getStarted(mergeInto)`, runs attest's hardware gate, a scanned
or pasted text is read by `readLink` into a `LinkOutcome` (a link, not a Fermix code, an older or a
newer Fermix, or one field missing or out of range, its candidates' ranges among them), and a link
starts core-session's pairing through `PairingControl` (the handle, or a test's fake), once section 9.2's
"Already paired; pair again to replace this phone's key?" has its yes when a row holds the link's daemon
(the row "Pair again" named aside; `PairAgainQuestion`), a no zeroing the link's secret and going back
to Pair, and `CeremonyDriver` shows each `PairingState`'s screen as the pure `screenFor` maps it: Connecting's
three lines, with "Trying Tailscale…" after 4 s of reaching a tailnet candidate and "Securing the line…"
paced for 600 ms, as no state holds it; Verify with its code, its end and the name `pair_request`
carried; `pair_approved` stored through data's `InstanceStore` with an auto-picked tint, the phone's
name and the time before Paired shows, merged into the row "Pair again" named when it is the same
profile's and no other row holds the new daemon (`mergeTarget`), the replaced record's key handed to the
commit to delete, and the paired session the approval hands over given, as the record is stored, to the
app's `SessionHandover`, which keeps it as the instance's one session; and every ending
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
a clip with none is the owner's own and stays. Paste takes the clip's first item's text, or else its URI's own
words, and never opens that URI, which the app would read with its own rights (`clipboardClip`).
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
end, the replaced key handed to the commit, the session handed over with its record stored only then,
"Pair again" merging into its row or adding one, a daemon paired already asked about first), every `LinkOutcome`, the paste sheet
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

`feature-instance` (`io.tezra.fermix.instance`) is design section 13.7's Instance screen and what the
Chats list shares with it, a Compose library on core-session and data. `Link` is how a Fermix's link
reads, from its session's state and diagnostics (`linkOf`): up over a scope with its latency, not yet
caught up ("Updating…"), connecting, waiting for the network, can't reach, a protocol error, each way a
session ends, or no session. A session backs off from a `1002` as from any failed connection and never
ends on it, so the newest of the diagnostics that say how a connection ended decides it: a protocol
error while it was the last, cleared by a connection or a later ending, and never shown as revoked.
`Link.dot` is section 13.5's dot, never optimistic: ok only for a completed handshake, warn while
connecting and for a changed identity, err for a revoked phone, the tertiary ink otherwise.
`InstanceAvatar` draws the mark on a row's tint with that dot at the canon's three sizes, and
`FermixMark` the large two-dot mark the empty list and the lock show. `InstanceScreen(ui, actions)` is the
canon's page: the header (the name to tap and rename, "Fermix on {host}", "Reset to gateway name" under a
nickname); Connection (the state, the live path, each candidate with its scope, the protocol, and "Test
connection" with its result in place, one race over the record's candidates whose socket closes at once;
a candidate's dot is lit only by what answered for it: the live connection went over it, or the last
test reached it, a candidate the winner cancelled lit by neither; section 5.2's network facts never light
a dot, as only a handshake confirms a tailnet, and only put out a test's word that no longer holds, every
candidate's with no network and a tailnet one's while Tailscale is off or kept from the app:
`reachableCandidates`); This phone (the name it paired as, since when, the gateway key's fingerprint, and
the hardware and build); Notifications (the two switches, or "Notifications aren't set up on {host} yet"
when the daemon has no FCM); Storage (the media cache's size and "Clear media cache"); Diagnostics (the
session's log, `+h:mm:ss  kind  detail`, panning sideways inside the card's inset); "Unpair from {host}…" with its dialog; and the
footer. The name and "Reset to gateway name" are 48 dp targets. The rename dialog says which of data's
rules refuses a name, and it and the unpair dialog survive a rotation and a fold. `InstanceViewModel`
reads one instance's record, session (its state, diagnostics and live candidate), chat settings, cache
and the network facts into `instanceUiOf`, pure, and does what the controls ask; each visit (`entered`)
drops the last visit's test and measures the cache again. Its JVM tests cover the link over every
session state and diagnostic, a session put aside reading "Connecting…" unless it ran out of races, the
dot, the fingerprint, "Paired since" in a given zone, the candidates' dots and the screen's state; the screen
is a preview at the twelve windows, at its head and at its foot, with references under
`feature-instance/src/test/screenshots`.

`feature-chats` (`io.tezra.fermix.chats`) is design section 13.4's Chats list, section 9.4's trust
screens, section 13.7's app lock screens and the phone's conversations. `ChatsScreen(ui, actions)` is the
bar ("Fermix", "+", and the overflow's "App lock") over a row per (instance, profile), or, given no row,
the mark and "Add Fermix", which the app does not reach today (below). A row (`rowOf`, pure) is the avatar with its dot, the title (the
nickname or the label, with the host-owned agent's name when it is not "Fermix", and the DEV tag), and a
second line in the canon's order: a link that speaks (a trust state, a protocol error, a connection taken
over, a daemon too old or too new), "thinking…", "Draft: …", then the newest row's words, the agent's markdown as its plain
words (feature-chat's `rowWords`); with the time
of the newest message (the hour today, the weekday for the six days before, the date before that, in the
phone's own form) and the unread count, the notified set's size. A row offline or connecting keeps its
last message. A long-press, felt, opens Move to top · Rename · Details · Unpair…, each with the canon's icon, the
row held in the agent's tone while it is open; Rename is the Instance
screen's dialog and Unpair… its confirmation. A "Re-pair this Fermix" row stands for each Fermix the
launch check dropped, by its instance id, and goes once that daemon is paired again, in the pairing's own
write, or on its long-press's "Remove". `TrustScreen` is "This phone was unpaired from {host}" or "{host}'s identity changed
(reinstalled?)" with "Pair again" and "Remove", in place of the chat. `LockScreen` is "Fermix is locked"
with "Unlock", and `AppLockScreen` the "Lock with biometrics" switch, off and explained on a phone with no
screen lock. `ChatsViewModel` reads the
records, the sessions, the turns and each instance's main profile, and moves, renames, unpairs and
removes. `ConversationSync` keeps one long-lived conversation shortcut, in the share category
(`SHARE_CATEGORY`) so the share sheet offers it as a Direct Share target, and one notification channel per
(instance, profile), id `{instance}:{profile}`, named as its row reads (`conversationName`: the title,
the agent's name when it is not "Fermix", and the DEV tag) and tinted as its row, through
`ConversationSurface`: `PlatformConversations` on the phone, a fake in the JVM tests. Each row's
conversation is made with it, published again when it is renamed or its agent is, and removed with it, and
the first sync after a start removes what a removal cut short. The JVM tests cover the row rules, a row's
time in the locale it is handed, a last message's plain words, the sync, and on Robolectric a conversation's name, a
`1002` row reading "Protocol error" and nothing of being unpaired, a revoked row naming who unpaired it,
the long-press menu, its place over the held row and the tap, the empty state, and "Send to which Fermix?"
(`ShareSheet`), its rows as the list draws their titles. Every
screen is a preview at the twelve windows, with references under `feature-chats/src/test/screenshots`.
Its instrumented tests (below) long-press a row on a device and keep a rename dialog's half-typed name
through a rotation and a fold, on the list and on the Instance screen.

Where the code departs from sections 9.4, 13.4 and 13.7, or reads them where they are silent, for the
owner to settle:

- A row whose session last ended on `1002` reads "Protocol error": section 9.4 names the state and the
  deck gives it no sentence. "Secure hardware ✓ · debug build" is the release line's twin for a debug
  build, "More options" and "Back" label the bar's icons, "Set a screen lock on this phone to use it."
  explains the switch that cannot turn on, and "{title} · {agent}" is how a title carries the agent's
  name; the design gives none of them.
- "Test connection"'s result reuses the deck's words: "Connecting…", the path, the banner's "Can't reach {host} — is it on and awake?" and the
  identity-changed line.
- The Diagnostics log gives each line's time after the session opened, and its kind in lower case.
- The empty list is the mark and "Add Fermix", with no sentence; the canon gives none. The app never shows
  it: the root is Welcome until a Fermix is paired and the Chats list while one is, or while one the launch
  check dropped waits, so the last unpair returns to Welcome. Design D10 says the Chats list is always the
  root; whether it should stay the root after the last unpair, with this empty state, is the owner's call.
- A session that failed on the phone's own fault, its store or its announcer, reads "Something went wrong
  on this phone". Section 13.9's "Something went wrong on {host}" is a daemon's `turn_error`, and would put
  the fault on the computer.
- A conversation's name carries the DEV tag as "{name} (DEV)", as a row draws the tag beside its title.
- A "Re-pair this Fermix" row is drawn in the Slate tint with no dot, and its long-press offers only
  "Remove".
- The Notifications switch sets the record's flag and hands it to `NotificationsPolicy`, which does
  nothing yet; its channel and `push_register` come with the notifications change.
- A refused nickname says why in one line ("A name needs at least one character.", "A name has at most
  40 characters.", "Another Fermix on this phone has this name."); the design gives no words.
- The lock uses the platform's `BiometricPrompt`, a strong biometric or the screen lock, not
  `androidx.biometric`, which would add a dependency for what API 35 gives.

`feature-chat` (`io.tezra.fermix.chat`) is design section 13.5's Chat screen, with section 13.6's composer
and section 13.7's message actions. `ChatViewModel` builds its `ChatScreenState` in pure, tested functions
(`chatScreenState`, `chatItems`) from the profile's cache, newest first (60 rows at a time and 3,000 at
most, then `loadOlder` from the oldest), the outbox, and the app's fold of the session's events
(`ChatLive.after`): the thinking card, one list item with the bubble that takes its place, whose bounds
morph into it in 300 ms on the emphasized easing and which stands in for it while a bare `text_done` waits
for its row, the live bubbles, `accepted`,
each turn's ending (an error card by `turn_error` code, or "Stopped"), and the notices and model lines.
The bar's subtitle keeps section 13.5's order (`chatLine`); the banner shows once its condition has held
2 s, Offline without a network and "Can't reach {host} — is it on and awake?" once every candidate has
failed for 30 s, whose tap (48 dp tall) opens the Instance screen; the screen draws nothing until the cache
has returned its first page, so its first frame holds the rows and the answers that came before it; the unread
divider is placed once, from that first page, empty or not, before the agent's first row past the read
frontier the chat opened on (`unreadAnchor`), and a row that lands later moves nothing; the scroll pill, a 48 dp target at the column's end, counts the agent's rows
past what the owner has seen; each day has its pill, and while the owner scrolls a date pill names the day
of the topmost item and fades 500 ms after; a pull at the top shows three skeleton bubbles until the older
page lands, the rows asked of the cache growing only once the daemon took the pull; bubbles within 2
minutes group with 6 dp inner corners, a message with no time grouped as if it came now. A bubble that
lands rises 12 dp, a tool chip scales in from 0.92, a streaming bubble's lines grow by
`animateContentSize`, and send ↔ stop turn into each other; all of it at once under reduce-motion. The
working indicator is core-session's `indicatorLine` on a seed per turn (`cardLine`), "Thinking" while
core-session says the daemon speaks on the card (`SessionEvent.Turn.daemonSpeaking`),
cross-faded in 200 ms, beside the orbiting two-dot mark, which moved from onboarding to `design`; under it
the latest two headings and two tool chips with "+n more", worded by the verb map (`toolVerb`). TalkBack
hears "Fermix is thinking" once, from one polite live region, and never the phrase. An answer renders with
mikepenz's multiplatform-markdown-renderer 0.45.0 (`-m3` and `-code`): as it streams, into
`rememberStreamingMarkdownState`, which a `replace` snapshot starts again, with the beam cursor after the
last paragraph's last glyph, or after the "code…" chip of a fence still open; sealed, parsed as it composes.
Each top-level fence and table is a card grouped under the bubble (`segmentsOf`; as an answer streams, the
parts from its last settled card on are read again, `segmentsAfter`), and only the answer's last part streams,
so the prose above a fence that closed or a table that formed is sealed at once: the code card, `#16171B` in both themes, with its language chip, Copy and
Share, no soft wrap, folded past 14 lines, and tinted by highlights 1.1.0 for Kotlin, Swift,
JavaScript/TypeScript, Python and shell from its first frame, in a fence of up to 16,384 characters;
the table card, flat, figures (times, counts, amounts, a number
with a short unit) in mono to the right, past four columns in columns as wide as their words, which share the
card's room when they need less and pan when they need more, a cell past three lines opening whole. A job's
tag stands above an answer's first part and the cursor after its last, whatever they are, and an answer made
only of cards shows its time under the last one. Raw HTML never renders: a tag, an HTML block and an
image show as the text they are. A link in a message opens a web address or nothing (`MessageLinks`, the
chat's `UriHandler`, `opens`): `http` and `https`, in any case, of at most 2,048 bytes, a link preview's
address's bound, open in the Custom Tab in the instance's tint, as a link preview does; any other scheme,
`tel:`, `content:`, `file:`, `intent:` and `fermix:` among them, and a longer web address, are never
handed to the system, the tap logged by the scheme alone. An inline, angle or reference link to one, an
email address and a bare `www.`, which names no scheme, draw as their words with no link on them, nor
on an autolink among them, marked up as a link's words are (`LiteralMarkup`); a destination in angle
brackets is an autolink to the parser, so its words carry no link either. A tap hands on the address its
link carries: in the renderer's table of addresses (`DefinedLinks`), which once turned a bare address
into the destination of a later link with those words, a destination that is a label in brackets is no
definition, so no address a link carries is a label, and its reference draws as written. The renderer also
keys a link's first words, when they are a label in brackets, to the link's address as it draws them, so
a reference drawn after `[[r] more](https://…)` carries that link's address, one that opens, rather than
its definition's. A link's touch target is its words alone (`InlineLinks`): Compose grows a smaller target
to 48 dp, which reached the lines above and below it, so a tap there opened it and a long-press opened
it instead of the message's menu. The owner's bubble has the clock until `accepted`, then one tick, its
time floated on its last line when it fits; a queued one is at 55 % under "queued · sends after this
reply", a pending one says "Queued", a refused one keeps the clock with "Not sent. Tap to retry sending."
under it in the error colour, an error card with "Retry sending", and a tap menu of "Try again" and
"Remove from outbox"; a queued or pending one's tap offers Edit and Remove while its frame was never written
(core-session's `OutboxItem.written`). The composer is the canon's two-row pill: the field, "Message
{name}…", up to six lines, and send ↔ stop at the end of the second row (Stop is core-session's
`Session.stop`, `command{name:"stop"}` sent at once over the link that is up and never queued, so it never
stops a later turn, and a `/stop` typed or picked on the palette goes the same way; send plays `CONFIRM` once
the session took the request); a "/" at the start, a
long-press on send or Ctrl+K opens the slash palette of the daemon's commands, a sheet the dock opens into
over a scrim on the whole window, its grab bar on top and the field at its foot; Enter sends and
Shift+Enter puts a newline. An answer that arrives whole plays `CLOCK_TICK` on its final bubble and
TalkBack reads its plain words once, from one polite live region, only while the chat is on screen and
never again on a rotation (`Arrivals`). The draft is kept 400 ms after the typing stops and as the screen
leaves, and comes back as the chat opens. A long-press lifts a message over the dimmed timeline with Copy ·
Select text · Copy code · Share · Info · Retry, each where it applies; Info shows the times, `server_seq`,
`client_msg_id`, the turn's duration, "Thought for 12 s · 3 tools", the model, its tools and the path;
multi-select copies or shares a transcript. While the screen is on screen, resumed with its window focused
(`onScreen`), it reports the newest row its list holds (`ChatPresence`), and at the bottom it marks the
newest row read once the session took it, never backwards, asking again as a session comes. What the
owner asked and could not have (a Stop with no link, a Remove of an item written meanwhile, a resent
request whose old item stays) goes to the app's log. `plainWords` and `rowWords` reduce a row's markdown to one line of words, which the
Chats list's last message shows. The JVM tests cover the timeline, the fold, the segments, the lexers, the
verbs, the commands, the menus, Info, the plain words, and the ViewModel over a fake session and cache
(the draft's debounce, its keeping on leaving and its return, a draft kept when the chat leaves before it
came back, the read frontier with no session yet, the divider placed once and none for a chat that opened
empty, no state before the cache's first page and the answers it held in the first one, the banner's delay,
Edit, Remove logged, the older page's pull and skeleton, "Retry sending" under a new id, a typed `/stop` that
never enters the outbox, and no run again unless asked),
the pure pieces (`onScreen`, `freshKeys`, `dateAt`, `stampFloats`, `isFigure`, `freshArrivals`,
`segmentsAfter` against a whole read at every split, `cursorHome`, a wide table's columns), and on
Robolectric raw HTML in a sealed answer and in its row, Run again only from the owner's tap through
`ChatRoute`, an arrival's one `CLOCK_TICK` and live region, the screen's reports to its presence through
`ChatRoute` over a real ViewModel (the newest row its state holds, and none below resumed or without the
window's focus), a fence that closes and a table that forms mid-stream leaving no "code…" chip or raw header
line, an answer made only of a card with its time and a job's tag, and the indicator line's pixels, peaking
in the ink over its middle when still and in the sweep's first frame. Every state is a preview at the twelve windows, with references under
`feature-chat/src/test/screenshots`. Its instrumented tests (below) keep the draft, the row scrolled to and
the indicator's phrase through a rotation and a fold, and check the long-press menu's order, the
announcements (the card's, and an arrived answer's plain words once), the hardware keys and Copy's
clipboard.

The chat's cards and controls answer the daemon (sections 8.4, 8.6, 13.5 and 13.7). An approval card stands
after the newest row as it came: its kind's icon and word, its text, its detail in mono, Deny and Approve,
and "Expires in {s} s · approving resumes the paused turn" over a bar, in the warning colour from 10 s. Its
seconds are read from the monotonic clock, never counted down, so a rotation or a fold keeps them, and a
card whose time ran out before it was drawn is its receipt from the first frame (gotcha 18). An answer goes
through core-session's `answerApproval`, which alone holds the card's routes and token; the card takes no
second answer while one is on its way, and takes one again when the daemon refuses it. It becomes its
receipt line in 350 ms, at once under reduce-motion: approved, denied, expired, or closed while this phone
was away. TalkBack reads the card as one group and one stop, Approve and Deny a touch's alone and the
group's custom actions, and hears the countdown from one polite live region as it crosses 30 s and 10 s,
with the seconds it then has; a card first drawn under 30 s says its own seconds once. The countdown's line
keeps the height of the most seconds it can show, so the card never changes height as it counts down and the
bottom-anchored list never moves its buttons, and each button's word stays on one line at 200 % type. An
answer's own row and outbox item never show, the Chats row's last message passes the row by, and neither
search finds it. A card that comes while its chat is off screen goes to the app's `ApprovalAlerts`, which
puts it in the notified set and hands it to an `ApprovalNotifier` once, never its replays; the notification
itself is A4's, and until then the notifier posts nothing and the set is left as it was. A reaction is a 24
dp pill over the owner's bubble's bottom-left, read from the row's metadata, overlapping the bubble's bottom
10 dp at every type size, so one that grows with the type grows down, never over the words, popping in on
the expressive spring (not under reduce-motion); a turn whose answer is only a reaction shows no card and no
empty bubble. Up to two link previews, `http` and `https` only, stand under their row: the site, the title,
two lines of description, and a 16:9 thumbnail that comes only through `Session.fetchMedia` into the media
cache (`ChatThumbnails`), never from its URL, its bytes read while the cache is in use, never its file held
past it; a tap opens the page in a Custom Tab in the instance's tint (androidx.browser), which needs no
`<queries>` and no permission, so `policy/permissions.txt` is unchanged. A preview that lands above or below
the row the owner reads moves nothing. The model chip in the composer's second row is seeded by
`caps.model_state`, then by each `model_changed`: at 70 % on the config's default, tonal with the accent's
dot on the chat's own model, and disabled with no connection, "Connect to change the model" above the
composer. The chip, `/model` on the palette and `/model` typed alone open the "Model" sheet, and none of
them does while the chip is disabled. The sheet pulls the models and lists the default, each provider's
models but the one the daemon marks default, with "no live typing" for one that does not stream, and a
provider the daemon could not list; a pick sends `command{model}` and says "Switches after this reply" while
a turn runs, and the `model_unavailable` card's action is "Reset to default". A pick and a reset go under an
id that starts with `model-pick:`, so neither their outbox item, but for one refused, nor the owner's row
the daemon writes of them is drawn: the daemon's "Switched to" line tells of them, as the canon draws.
Search opens from the bar's icon or Ctrl+F: 300 ms after the typing stops, the daemon's index while the link
is up and the daemon has `caps.search`, 20 hits a page and the next as the list nears its end; otherwise the
phone's own index, under "Cached messages only — connect to search everything", with nothing queued for
later. A daemon's page that does not come while the link is up is logged by how it ended and says "Couldn't
search {host}" with "Try again". The chips (All, Media, Files, Links) filter cached rows. A hit opens the
chat at its row, loading the pages down to it first when the cache lacks it, each the one before the oldest
row held, so no gap is left, or waiting up to 10 s for the catch-up to bring a row newer than the cache;
with a 1.5 s ring round the bubble's own corners and the query's words washed in the row, in the owner's
bubble in its own ink, ▲ ▼ step through the hits there from a bar in the 640 dp column. The query, cut to
the 256 scalars a search takes, goes to the daemon or the cache and nowhere else, never into a log. The JVM
tests cover the cards' fold and countdown (an expired card answerable never, the announcement's crossings),
the thumbnail through the session alone (a scan of the module's sources and build script, and of its
compiled classes, for an HTTP client, a socket, a URL fetch or a web view), the chip's seeding and the
sheet's rows, a pick's quiet id, the search's debounce, pages, failures, bound, offline index and steps, and
the jumps that fill every page between; on Robolectric the card's custom actions and live region, its one
TalkBack stop, its height through the countdown at widths from 240 to 412 dp and its one-line buttons at 200
% type, an expired card with nothing to press, a preview's tap, its Custom Tab's address and tint, a
message's link by its scheme, upper-case, blank-led, bracketed and over-long ones among them, opened in
the tinted tab or logged by its scheme, drawn as a link or as its words marked up as a web link's are,
a reference's and an autolink among closed words among them, a definition naming a label in brackets
drawing its reference as written, and its tap handing on the address it carries, sealed or streaming, as
a reference after a link whose words begin with its label carries that link's (`MessageLinksTest`), a
reaction's description and its chip growing down at 200 % type, a late preview that moves no row, Ctrl+F and
the search field's caret, and the chip, the sheet, a pick, the palette's and the typed `/model` through
`ChatRoute`. The instrumented tests keep the countdown and the buttons through a rotation and a fold, read
the card's custom actions, its one stop and its two announcements from the window's accessibility tree, pick
a model from the chip's sheet, and open search with Ctrl+F through the system's input.

Attachments and voice notes (sections 8.5, 13.5 to 13.7 and 13.9). The composer's + opens the attach sheet,
a modal sheet at 60 % of the window: the chips Camera · Files · Paste, never tabs; the system's embedded Photo
Picker (androidx.photopicker, SDK extension 15 or later), its picks numbered in order, at most ten, each grant
a pick and each revocation an un-pick, or, where it cannot draw, a tile that opens the system Photo Picker
(`PickMultipleVisualMedia`), allowed what the tray's other items leave of the ten (a pick past the ten is
left out and logged); the first item past `caps.max_media_bytes` inline ("{name} is {size} — the limit
is {max}."); the caption, which is the composer's words; "Send as files"; and "Send {n}". Files is the
documents UI (`OpenMultipleDocuments`), Camera the chat's own CameraX capture once the app may use the camera
("Camera is off for Fermix" when refused), Paste the clipboard's first item (`ChatClip`), and the keyboard's
images come through the field's content receiver (IME `commitContent`). None of them needs a media
permission. Each enters through one check, `mayRead`: an item from Photos, Files, Paste or the keyboard is
another app's `content:` URI whose authority, without its `user@` prefix, names no provider of this app's own
package (`ownsProvider`: the providers the app's package declares, as the package manager lists them), which the
app would read with its own rights, and the
camera's is a `file:` URI the chat made under its cache; any other scheme or authority is refused, logged by
its scheme and authority alone, never its path, and never lands in the tray, and the clipboard hands Paste
nothing else (`PhoneClip`). The tray's reads, a send's and its thumbnails, take those two and no other. The
check knows a provider, not a grant: a row the app saved into the media store itself (Save), which other apps
cannot read, is read by the app as its owner when another app puts its URI on the clipboard or a keyboard
commits it, and shows in the tray before Send. A paste's, the keyboard's and a share's items are copied into the
chat's own files as they land, the keyboard's commit held until then, since their read grant ends long before Send;
one whose grant is already gone is logged and left out. Such a copy is bounded: an item past the room the tray
has, or whose provider says it is past `caps.max_media_bytes`, is never copied, and the copy stops a byte past
the limit (`copyAtMost`), the item left out and named on the tray's line. A provider's failure carried across
the binder, as it describes or hands over an item, leaves that item out, logged by the exception's class. The
tray's copies are kept in the chat's saved state (`KeptTray`), so after a process death the chat comes back with
the copies whose files are still there, and only those: a picker's grant ended with the process. The sheet's
grid keeps at least 160 dp, and what the window leaves beside it,
with the sheet's other items (a capture, files, a paste) in a tray at its foot; the Photos tile shows its
own picks. The picks wait in the tray over the field, 56 dp thumbnails each with its ✕, an 18 dp badge in
a 48 dp target, a file's extension sized in dp; the files the chat made for the tray, a camera's capture,
a paste's and the keyboard's copies and Edit's copies, go when they leave it or the chat closes; a send
that stops short, a grant gone or the disk full, deletes what it made and lets go what it staged. `PhoneMedia` makes
each item ready: an image is decoded by ImageDecoder at most 2,048 px on its long edge, HEIF among what it
reads, and goes as a JPEG with no EXIF and no GPS; "Send as files", a video or audio go as their own bytes,
as documents; each is hashed, and a digest the daemon holds already says "Already on {host} — sent
instantly". The owner's item shows at once from its staged file, the ring over its images while it goes up,
a 3 dp bar under a document's size and under a voice note's waveform, and "Upload interrupted — resumes when
connected", its glyph inline with the words, in place of its time while an upload that was cut waits
for a connection; one composed offline reads "Queued". "Retry sending" names each attachment under a new
`attach_id`, whose `present` skips the bytes the daemon holds, so an id it let go of
(`attachment_unavailable`) is never named again. A send its session did not take lets its staged copies go. Incoming images
come only through `Session.fetchMedia` into the media cache, drawn in their first chunk's average colour
until they are in: one at its own aspect clamped to 3:4…16:9, two side by side, three as one large and two,
four or more as a 2 × 2 whose last cell says "+N", the message's words as the caption inside the card; a blob
the daemon let go says "No longer on {host}". A document is a 64 dp row, its extension's tile, its name cut
in the middle and its size · origin; a tap downloads it and opens it through the chooser as a read-only
content URI of the module's FileProvider (`{applicationId}.chat.files`, the cache's `shared/` alone), raw
HTML too, which the chat never draws. No string from the wire names the copy's path (`sharedFile`): it sits
in a directory named by the first 16 hex characters of the SHA-256 of the blob's cache name, whatever its
ref, under its name with no `/`, `\`, control character or lone surrogate in it, never `.` or `..`, cut to
its last 255 UTF-8 bytes (`file` when nothing is left), and its canonical path is checked to lie under
`shared/` before anything is written, a copy that would not being refused and logged; the oldest of eight
copies goes as another is made, a link among them deleted, never followed. A blob's type reaches the chooser,
the share sheet and the media store as a `type/subtype` of at most 255 characters, its parameters dropped and
lower-cased, anything else as `application/octet-stream` (`mediaTypeOf`), the daemon's and another app's alike,
as the wire bounds it nowhere. A long-press offers Share and Save, which writes through MediaStore, with no
storage permission, an image whose type is an image's into Pictures/Fermix and anything else into
Download/Fermix, as a row's kind and type are the daemon's and may disagree; an entry the media store refuses,
a name it numbers no further ("report (32).pdf") among them, is logged and the app goes on. A tapped image
opens the viewer, black in both themes, the image moving from its bubble as a shared element: every image the list holds, oldest first,
swiped between, pinched to 5× and panned while zoomed, a swipe down at its own size putting it down, with
Share, Save and Show in chat; it keeps its image as the window turns. The mic, at the end of an empty
composer, records while held: the first hold shows the microphone's rationale, then the system's prompt
(`RECORD_AUDIO`); refused, "Microphone is off for Fermix" with "Open settings". Held, slid left past 120 dp
it cancels, slid up past 96 dp it locks hands-free (pause · stop · send), each threshold felt
(`GESTURE_THRESHOLD_ACTIVATE`), and let go it sends, with `CONFIRM`, one `msg` with the note as its `audio`
attachment, OGG/Opus through `MediaRecorder` (`PhoneVoice`). A call, another app taking audio focus, the
chat leaving the foreground, or the system taking the touch from the finger stops it into a draft,
"Recording stopped — send or discard", and a release after that sends nothing: only a tap sends a draft; a
rotation or a fold does not stop it, and a hold the window change took from the finger locks the take
hands-free. A hold while a call rings or is under way starts nothing and says nothing: the system
refuses the take audio focus, which is logged ("The microphone could not record"), and the composer
stays as it was, as the design has no words for a take that did not start (the owner's to give). A take
records into its profile's `voice-draft.ogg` (`ProfileDatabases.voiceDraft`), so a draft outlives the
chat and the process and comes back when the chat opens, one unreadable deleted and logged; it goes
once its session takes the note, and a send its session refused keeps it a draft. A sent note is its
bubble, at most 78 % of the column: play, the 40-bar waveform, which gives up width first, played solid, its
length, the speed chip (1× · 1.5× · 2×) on one line and its transcript, "Transcribing…" until
`transcript` comes; a note this process did not record draws the bars read from its file with its length
(`noteLevels`: MediaExtractor and MediaCodec, the loudest sample of every 100 ms, as the recording row
samples it); one note plays at a time through the one player (`ChatPlayback` over `VoicePlayer`). What the chat asks of the phone beyond its
screen, another app's activity, a permission, the camera's screen and the picker's surface, is `ChatOutside`,
which the instrumented tests replace. The JVM tests cover the sheet's and the tray's state, the size line,
the caption written once into the field, the un-pick, each source's items, a paste's and the keyboard's
copied as they land and a grant gone at Send, the keyboard's image committed through the field's input
connection (`ComposerMediaUiTest`), the tray's 48 dp ✕, the long-edge cap (`ImageCapTest`), the pipeline's
cache and digest, one blob asked for twice at once fetched once, the files a closed chat, a failed Edit, a
stage that stops part-way or a full disk leaves none of, and the staged files a send its session did not take
lets go, the voice note's start, lock, pause, stop, release, send, discard and focus loss, a release after an
interruption keeping the draft, the draft kept through the chat's close, a recording the chat closes on and
a refused send, and one unreadable let go, a recording stopped into a draft as the chat leaves the
foreground and going on through a rotation, its length and bars, the player's one note and the notes it
cannot open or read, the outbox's ring and lines, "Retry sending"'s new ids, the layouts and the viewer's
pages, a shared copy's directory and name and where they may lie, a copy refused before any is evicted, and the
oldest copies evicted without following a link (`SharedFileTest`), a landing copy stopped a byte past its limit
however long its stream, and whole with no limit (`BoundedCopyTest`), a share's items landing past the ten,
past the limit as their provider says it or as their stream shows it, an image past it landing whole and going as
the smaller JPEG while one past its own bound never lands, a share before the chat's record waiting for its limit
and dropped once the wait ends, a refusal logged by its class alone, words that do not fit leaving the draft as it
was, and through a provider that fails (`ChatShareTest`), where Save puts a blob, a type bounded
(`mediaTypeOf`, a 1 MiB one among them), the one check's table of scheme, source,
authority and path (`ReadableUriTest`), and the module's scan for a network client; `data`'s tests keep the
voice draft in its profile's directory, gone with its instance, a send's staged files let go unless an item
names them, and a file staged under no id that names a directory; the screenshots draw the sheet with two picked, a file and a paste in its tray, and a
caption, the size line, the sheet's Photos tile with its pick, the tray, the composer recording, locked, its
draft and the microphone off, one, two, three and five images, the ring, the duplicate line, a gone image
in a lone and a small cell, documents and a voice note uploading, voice notes playing and transcribing, the
interrupted and the failed item and the viewer. The instrumented tests prove a photo leaves the phone with
no EXIF and no GPS, at most 2,048 px on its long edge, and as its own bytes sent as a file; the Photo
Picker's, the documents UI's, the clipboard's and the camera's items reaching the tray, the clipboard's as a
copy that still sends once the clip changed and its grant went; the mic's cancel past 120 dp, its send short of it and its lock; a take stopped by a lost audio focus while held, and a hold
the system took, staying a draft; the microphone's rationale before its prompt, and "Open settings" once
refused; the phone's own recorder writing OGG/Opus whose length and levels its player reads, and losing
audio focus to another app (`PhoneVoiceDeviceTest`); a document's chooser and its provider's bytes, raw HTML
among them; a locked recording, with its bars and its timer, and an upload going on through a rotation and
a fold, and a recording held through a rotation locked hands-free; and the viewer open on its image through
a rotation.

Where the code departs from sections 8.3 and 13.5 to 13.7, or reads them where they are silent, for the
owner to settle:

- "Retry sending", and the failed bubble's "Try again", send the refused request again under a new
  `client_msg_id`, naming nothing in `retry_of`, and take the refused item out of the outbox; "Run again" on
  a turn that ran and failed is a new request naming the old one in `retry_of` (core-session's
  `Session.retry`). Sections 13.5 (review R4) and 13.6 keep a never-`accepted` request's id, for the daemon
  to deduplicate; PROTOCOL.md answers a refused request sent again under its id as a duplicate that never
  runs, and one neither accepted nor refused is still the outbox's, which sends it again by itself under its
  own. A failed turn whose request the chat does not hold (a cron's, or one whose message is older than the
  rows the list holds) shows its line with no "Run again".
- A refused item has both section 13.5's error card ("Retry sending") and section 13.6's tap menu ("Try
  again" · "Remove from outbox"); the canon asks for one word.
- highlights 1.1.0 has no lexer for Elixir, JSON, YAML, SQL or diff, so those fences are mono, untinted.
- A fence of more than 16,384 characters (`TINT_MAX_CHARS`) is untinted too, drawn as an untinted language's.
  The card tints as it composes, so that the tint is in its first frame, and highlights' work grows faster
  than the fence, where a reply may hold 1 MiB: on a laptop's desktop-class core, warm and at best, about 10 ms
  at that bound, 2 ms at 4 Ki characters, 120 ms at 64 Ki and 2 s at 256 Ki, and about 110 ms for the first
  call in a process. A phone's figures are not measured yet, and may argue for a smaller bound. A folded card
  tints only the lines it shows. Section 13.5 names no size.
- Each top-level fence and table is cut out of the answer before the renderer (`segmentsOf`), to group it
  as a card under its bubble as the canon draws; the renderer parses each prose part again, so an answer is
  parsed twice, and the renderer's own fence and table components draw only nested ones.
- The cursor stands after a paragraph's last glyph (inline content in a paragraph component of the
  renderer's) and after an open fence's chip; after any other last block, a heading or a table being
  written, it stands below it. The owner's time floats on the last line, as the canon's `.ts`; the agent's
  is a line of its own under the prose, whose last line is the renderer's to lay out, or under the last card
  of an answer made only of cards. A fence nested in a
  list keeps the renderer's 8 dp padding. Table cells are plain words. An image is its source text, as raw
  HTML is.
- A job's delivery reads its job from `metadata.job`, which no wire document defines yet: provisional, a
  wire question for the owner.
- A command's answer written inline ends its turn at its `text_done` (core-session above): the engine's
  `/stop` replies before its queue, and section 7 emits `turn_done` only from `build_turn_result
  {:completed}`, which such a reply never reaches, so the turn would otherwise hold every later message as
  queued. Whether protocol v2 ends such a turn on the wire is a question for the owner; a `turn_done` that
  does come after it is late, and ignored.
- The running tool chip's glyph is 16 dp in a 22 dp ring, as section 13.5 says; the canon draws 11 dp in 20.
- The scroll pill floats over the list, as the canon's `.sp` does, and covers what scrolls under it; no
  padding is added while it shows, which would move the list as the owner scrolls off the bottom.
- The unreachable banner is 48 dp tall, section 13.8's least target, where the canon's line is 28 dp; the
  offline one, which does not tap, stays thin.
- The date pill shows while the owner drags or flings the list, not while the list follows what lands, and
  not while a day header is the topmost item, which names its day itself. A panned table's columns are as
  wide as their words, 64 to 220 dp and never narrower than their longest word (up to 320 dp).
- A card the phone showed and `hello_ack`'s `pending_approvals` no longer holds ends as "Closed while this
  phone was away", design section 8.2's line, where the task named only the approved, denied and expired
  receipts. The reaction's pop-in is expressive, as section 13.5 draws it, although the motion section keeps
  the expressive scheme to three places. The chip's glyph is the provider's first letter, and a provider's
  group in the sheet is its id, capitalised: the wire carries no provider name. A preview whose address is
  not `http` or `https` is never shown or opened; with nothing on the phone to open a web page the tap is
  logged and does nothing. A thumbnail is looked up in the media cache by its `image_ref`, taken as the
  blob's SHA-256, which is the daemon's content address; one the wire allows that is no lowercase SHA-256
  has no thumbnail, and the log says so. A hit past the 3,000 rows the list holds is logged
  and not jumped to. A late preview on the row the owner reads, or between it and the list's anchor, grows
  upward.
- Search's failure line, "Couldn't search {host}" with "Try again", is not the design's, which is silent on
  a daemon search that fails while the link is up: it is the model sheet's "Couldn't list models on {host}"
  read for search, with section 13.6's "Try again".
- The Media, Files and Links chips filter the phone's cached rows, even while the daemon searches; the
  pinned line ("… connect to search everything") does not fit a connected chat, and the design is silent on
  chips while connected, so their results carry no line saying they are the cache's.
- "Expired — no answer in {ttl} s" names the `ttl_s` of the card's first sighting in this process. For a card
  first seen as a replay, after a reconnect or a process death, that is the time it had left then, not the
  card's whole time.
- The chip's glyph is the first letter of the provider's id: codex's GPT-6 Astra is "C" in its ring, which
  reads as "©", where the canon draws "O". The wire carries no provider mark to follow.
- The countdown's 1 dp live region stays a TalkBack stop holding the words it last said, until it says the
  next: Compose has no way to keep a live region announcing while it is out of the traversal order.
- For the engine: an approval's answer is written as the owner's row, its route and token as the words, and
  indexed for search (`mobile_timeline`, FTS). The phone keeps the row without its words, and drops a hit on
  a row it holds as an answer and an owner's hit whose excerpt is a route it knows (the daemon's four and
  those its cards named) and one token-shaped word; an owner's own two words of that shape ("deny
  everything") are dropped too, so the answers belong out of the engine's index. A `history_search` or
  `models_pull` the daemon refuses gets an `error` that names no request, which the phone cannot tell from
  the connection's other refusals: it waits out its 30 s, and "Try again" works only after them. Naming the
  request in such an `error` would end it at once. PROTOCOL.md says two blobs' frames never mix, while the
  engine's fanout pushes a message's media between a fetch's chunks; the phone takes either.
- Multi-select starts from a tap on a lifted message; the empty chat greets the instance's title ("Say
  hello to {title}."); after a reconnect the card starts again at "Thinking", from the reconciliation's
  active turns; a daemon of protocol v1, which sends no `turn_done`, holds a queued message until the
  reconciliation.
- The embedded Photo Picker is the system's surface: no screenshot or instrumented test draws it. The
  screenshots draw a stand-in grid of its shape, and the instrumented tests reach the tray through the
  system Photo Picker's tile, which the sheet shows where the embedded picker cannot draw. On the API 36.1
  emulator (SDK extension 20, so the sheet takes the embedded path) a throwaway probe opened the embedded
  session and the picker indexed the three images pushed to the device, but its surface drew blank under the
  emulator's software GPU and taps on it picked nothing: the embedded grid is still to be seen on a phone.
- An image goes at most 2,048 px on its long edge, at JPEG quality 85; section 8.5 names no edge. Its
  placeholder colour is the average of what the blob's first chunk decodes to, not a dominant colour
  computed from a palette.
- Camera puts the sheet down before its screen opens, since the sheet is a window above the chat's own. The
  field holds a `TextFieldState`, which the keyboard's images need; a restored draft or Edit's words open on
  the caret's last line once the field has laid them out.
- A voice note's bubble draws an even line until its bars are known: recorded here, or read from its file
  with its length. The viewer swipes between every image the list holds, not only the message's.
- General audio picked from Files goes up as `kind: document`, where section 8.1's "Videos and general audio"
  row names `audio`: an `audio` attachment is what the daemon transcribes, and the owner's `audio` is drawn
  as their voice note, so a podcast would come back as a voice note with a transcript. A video goes as `video`.
  The owner to settle.
- Edit returns an outbox item's attachments to the tray, which is not kept with the draft: a chat closed
  before the owner sends again keeps Edit's words and lets its files go. Keeping the tray with the draft, or
  withdrawing the item only at the next send, is the owner's to choose.
- A picked item past the ten a send takes is left out of the tray and logged; the design gives no words to
  say so.
- "Already on {host} — sent instantly" is the canon's and section 13.9's words, where section 13.5 words the
  same state "Duplicate — sent instantly". An upload's line takes the time's place under the image, as the
  canon's one `.state` line does, and the interrupted line drops "Queued" under it, which would say the wait
  twice.
- Section 13.11's rule 1, a compact window in portrait only with the viewer alone turning, is not in the app:
  `android:screenOrientation="portrait"` fails lint's `LockedOrientationActivity` and `DiscouragedApi` (Android 16
  ignores a fixed orientation on large screens), which the gate holds with no suppression, and a lock set from
  code would read to lint as the same. Every screen follows the rotation, the viewer among them, and rule 3 holds
  for each.
- A row's `media_refs` entry names its blob by `ref`, which is the blob's digest, and carries no `sha256` in the
  vendored rows: the media cache is read by the digest, or else by the ref, so an image is fetched once.

The app wires it all. `FermixApplication` makes `AppServices` once (the records and their databases,
the settings, the network watcher, the device keys, the connector) and tells `SessionSupervisor` when the
process comes into and goes out of sight. `AppServices.start` runs the launch check off the main thread
before anything shows or any session opens, then starts the supervisor, the conversation sync, and the
settings feeding the lock. The Chats list's ViewModel is made at the first composition all the same, and
its rows' readers may run while the check drops a Fermix, which orders them as any removal does. `SessionSupervisor` is the one keeper of sessions (section 12.5): one
`Session` per paired instance, keyed by its id, opened from the records while the app is in sight
(`AppSessions`, on the I/O dispatcher, a missing Keystore key logged and left without a session), each with
one collector that keeps `hello_ack`'s facts on the record, the agent's name on the chat, the daemon's
later routes and the candidate the last `hello` went over (`Instance.lastCandidate`, which the next
process races first), an older page's rows in the cache, and the read frontier against the notified set
(`SessionEvents`); that folds the events into what each chat shows besides its rows (`ChatFolds`, whose
turns end with their session); and that counts the turns running per instance for the rows'
"thinking…", which lasts while any of them runs. Each session's announcer
(`RowAnnouncer`) keeps every row in the profile's timeline, then answers: the owner's own message is
known already; a row the chat on screen lists is shown there (`OnScreenChats`: a Chat screen reports the
newest row its list holds while it is resumed and focused, and the answer waits for the list to take the
row, at most 3 s, so the row is persisted, shown, then acked); any other is put into the notified set and
posted (`RowNotifier`), or known already when the set held it; a row that cannot be posted, its
instance's notifications off or unable to show, is not announced and never acked, its push still to come
(tla/specs/mobile_push, PUSH-2). Out of sight for 5 s every session is suspended, by one timer that a return within
them cancels; back in sight each resumes, and one that ended reconnects, unless it was revoked or its
identity changed, which wait for the owner. A pairing's approved session is taken over as its record is
stored (`adopt`), so no second socket opens; unpairing sends `unpair`, waits up to 5 s for the daemon to
close, and removes the instance, its files and its key. The Chats rows, the Instance screen, the
conversations' agent names and the session events read each profile through data's `ProfileDatabases`
(`observe`, `withDatabase`, `withMediaCache`), so a removal ends them before the files go, and what comes
after it, a late event or an action of a screen leaving, finds the instance gone and does nothing. A
session, its store and its announcer, holds its database for its life, outside that count: the supervisor
closes each session it keeps before the removal runs, and `Session.close` returns only once the run has
stopped and every request made before it, `send` and the others the app makes in its own coroutine, has
returned, so nothing of the session touches the files after; a removed instance gets no session. A
pairing's session is the supervisor's only from its handover (`adopt`, which waits while a removal runs):
before it, nothing orders that session against a removal of the same daemon, a window reached only by
pairing that daemon again while it is being removed. "Pair again" merges only into a row in a trust state, revoked or
its identity changed, whose session's run ended with that state and which takes no request, so the merge
deletes that row's files with nothing touching them, and the next reconcile drops its session. `MainActivity` shows `appBackStack` in
`NavDisplay` under `FermixTheme`: the Chats list while a Fermix is paired or one the launch check dropped
waits to be paired again, Welcome otherwise, the app's
screens above it (`AppNavigator`: a chat, the Instance screen, a trust state in place of its chat, the
App lock setting), and onboarding's on top. A screen whose instance is gone leaves; the chat on top is
kept and restored when the app starts again, unless an intent names another. The intents it acts on
are a chat's deep link, `fermix://chat/{instanceId}/{profileId}`, which notifications and conversation
shortcuts send, and the static shortcut's "Add Fermix"; another app's share reaches it from the share entry
(below), in the process, never on its intent. No filter declares the link, but the activity is exported for the
launcher, so any app can send it one in an explicit intent; all it can do is open the chat of a Fermix already
paired. An intent the system replays as the app's task is brought back from Recents
(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) is acted on never again. The activity is `singleTop`, so a launcher tap
brings the app back with what it had open over it (the Files picker, a Custom Tab, a permission's prompt). It
fits the window to the top screen (`fitWindow`): `FLAG_SECURE` while an onboarding screen shows, or the Instance
screen with the daemon's key fingerprint (section 12.4), or the app is locked (`secureWindow`), and white system
bars with no contrast scrim over the scan's camera, which is dark in
both modes as the canon's `.phone.bleed` draws it, the theme's bars everywhere else. With the app lock
on (`LockGate`), the app locks when it comes into sight after the process starts or after 5 s away (a
rotation or a fold is no return), and nothing shows before the gate has read the setting. While locked
the lock screen stands in place of the app's screens, none of which is composed, the system's prompt
comes at once and "Unlock" asks again; back leaves the app; the recents preview is hidden while the lock
is on, and until its setting is read. A phone with no strong biometric and no screen lock is never locked, as nothing could open it. On leaving Verify for another app it starts `PairingWaitService`,
the short foreground service of section 12.5, "Waiting for approval on suj-mbp · 1:42", which ends itself
when the wait ends or the app comes back (`pairingWaitShown` decides both). On leaving the app while a
session has an upload in flight (the supervisor's `uploading`, from each session's) it starts `UploadService`,
section 12.5's `shortService` for an upload, "Sending to {host}", which ends itself when no upload is in
flight, the app comes back, or the platform's timeout comes (`uploadShown`); out of sight past the grace, the
supervisor keeps such a session up until its upload ends or `UPLOAD_HOLD_MILLIS` (165 s, inside the service's
three minutes) pass, then puts it aside with the rest, its item left in the outbox for the next connection.

The app's notifications have one owner, `Notifications` (design section 10, "Lifecycle on the phone"),
behind the session's seams, `RowNotifier` and `ApprovalNotifier`, and a push's. A conversation, one per
(instance, profile), has one `MessagingStyle` notification, rebuilt from its notified set each time and
posted on the conversation's channel with its shortcut (`ConversationSync`'s ids) and a tap that opens its
chat, an explicit, immutable intent of the app's own activity. A post that adds a row alerts; the read
frontier, which core-session says on every `hello_ack` and `read_state` and whenever this phone's own read
moves it, takes the rows it covers from the set, cancels the notification once the set is empty, and
rebuilds one still showing without alerting (`setOnlyAlertOnce`), a dismissed one staying dismissed; a
rebuild leaves out the rows at or below the stored frontier too, as the phone stores its own read before the
session says it. A row's words come from the
cache, or from the preview its push carried while the process lives, or are "New message"; with previews
off it is the instance's name over "New message", and while the app lock is on it is "New message" alone,
no word of a message written into it whatever the previews say (`messagesNotification`, the pure builder the
tests read); the generic notification is "New message" alone then too. Turning the app lock on or off, or a
chat's previews switch, rebuilds each conversation's notification still showing without alerting
(`restyled`), so none keeps words they now keep out. An approval's notification is keyed by its id and times out at its expiry
(`setTimeoutAfter`), and one whose push comes after it says "An approval on {instance} expired"; a failed
turn's is keyed by its turn. An id is one character or more, as the wire and the notified set take it, so
one of spaces is an id like any other. The session's approval cards post through the same owner, and the
notified set lets a push and its socket row alert once between them, whichever comes first; a card the
daemon resolved (`approval_resolved`), or one closed while the phone was away, takes its notification with
it, cleared by its id. `FermixMessagingService`
is FCM's side, not exported. A message's `notification` block is never shown, on two paths: the manifest
turns Firebase's notification delegation off (`firebase_messaging_notification_delegation_enabled`), so
Play services never shows the block as the app without calling it, and the service takes the block out of
FCM's intent before Firebase's own code could show it out of sight, past the app lock (`handleIntent`). A
daemon sends none, and one that comes is from whoever learned the token. Firebase reads the flag once per
install, keeping Play services as the delegate of an install that ran without it, so the flag must never
be dropped, even for one build. `onMessageReceived` hands the `data` map to `PushInbox` and returns
once it is posted, the trial bounded by `PUSH_DECRYPT_BUDGET_MILLIS` (5 s of FCM's ten) and run off the main thread,
past which the generic notification goes up; a push no key opens, an unreadable plaintext, a kind the
app does not know or a profile other than main posts the generic notification, never nothing, and a row
read already or a chat on screen posts nothing. A push no key opens posts nothing, its line
`suppressed:off unopened`, while no Fermix wants pushes (none pushes through FCM with its notifications
showing): FCM's priority is then nobody's to keep, and anyone who learns the token can send one. Each push leaves lines in the push diagnostics ring and the log (`FermixPush`), with
no content: `received`, `refused`, `decrypted:{instance}`, `posted`, `suppressed:{set|read|on_screen|off}`,
`expired`, `generic`, `deleted`, and `timed`, the decrypt-to-notify time in milliseconds.
`onDeletedMessages` marks every record's full pull (`Instance.historyPullDue`), so its next session with an
empty cache pulls every row from the first, and the flag clears once a connection of a session opened with
it has reconciled (`SessionEvent.Reconciled.pulledInFull`); a session already open when FCM dropped them
leaves it for the next. The trial tries the records whose daemon pushes through FCM, and whether a push may
notify is read from the record whose key opened it.
`PushRegistrations` is the Instance screen's and onboarding's `NotificationsPolicy`: once a connection has
reconciled, when the app comes into sight, when the switch is turned or onboarding's step 7 answered, and
when FCM hands a new token, each instance's `registrationStep` runs over its live session, writing the
time of a `push_register` to the record and clearing it on `push_unregister`; the switch's step reads the
record as written back, so a quick double toggle takes the last answer. The switch does not unblock a
channel the owner blocked in the system's settings, which only the owner can: while it is blocked, or the
permission denied, the step unregisters and the switch still reads on. No screen shows the push diagnostics
ring yet; its lines go to the log as they are made. The token is never written or
logged: `FcmToken` holds it in memory, asked for with `FirebaseMessaging.register` and handed back to the
service's `onRegistered` (firebase-messaging 25 deprecates `getToken` and `onNewToken`), and a token that
changes, or comes unasked, clears the time of every record whose daemon is to push (`pushWanted`), so that
each is registered again, before `onRegistered` returns, as FCM keeps the process only while it runs; a
record owed a `push_unregister` keeps its time, so the unregister still goes. Firebase makes no token at
launch (`firebase_messaging_auto_init_enabled` false), so a phone whose notifications are all off never
reaches FCM.

Another app's share comes in through the share entry (design section 13.6, "Share into Fermix"), the activity
`ShareTarget`, exported with `SEND` and `SEND_MULTIPLE` filters for `image/*`, `video/*`, `text/plain` and
`*/*`; the app's shortcuts name it as the conversation shortcuts' `<share-target>`, so Direct Share offers each
paired chat, and no `ChooserTargetService` is involved. The entry has no window (`Theme.NoDisplay`), no
affinity and no place in Recents: it starts in the sharing app's task, reads the share, hands it to the app in
the process (`AppServices.shares`, which the activity's `ShareModel` takes once), brings the app's one activity
forward in the app's own task with an intent shaped as the launcher's (`shareForward`, `MAIN` and `LAUNCHER`,
no data and no extra), and finishes. When it holds read grants it sends that forward twice (`shareForwards`):
first with none, then with the grants (below), which the activity takes as a new intent. The intent that starts a
task stays the task's own, which Recents starts again once the activity is gone, and a start naming a grant the
app no longer holds fails and takes the task out of Recents; so a task a share starts keeps the launcher's own
intent, Recents starts it again as the launcher would and replays nothing of a share, and an entry the system
replays from Recents all the same takes nothing. The forward clears the activities over the app's own in its task (the Files picker, a
permission's prompt), as a link does. The app's screens are no activities: a chat over the list gives way to the
share's chat, but a share that lands while onboarding shows, "Add Fermix" with a Fermix paired, lands under it, its
chat showing once onboarding ends; whether a share should end a pairing under way is the owner's to decide. A
Direct Share that starts the activity, its process gone, lands as the activity restores the chat kept from before,
and the share's chat stays on top (`AppNavigator.showPaired`). The entry takes
nothing but a share, and the activity no share: a share sent to the activity itself, or a chat's link or "Add
Fermix" sent to the entry, is nothing. `shareOf` reads the intent's `EXTRA_STREAM` (a
URI, or a list for `SEND_MULTIPLE`), its `EXTRA_TEXT` as plain words and its `EXTRA_SHORTCUT_ID`, each as the
type it must be, and nothing else of it; no extra is passed on to another activity or used as an intent.
`sharedOf` weighs the first ten URIs (`MAX_ATTACHMENTS`) by the one check, `mayRead`, as another app's
`content:` URI only: a `file:` URI, one of the app's own providers, with or without a `user@` prefix, and any
other scheme are refused, logged by scheme and authority alone; the rest past the ten are never looked at, and
their count is logged. The words are cut to 4,096 characters as they are read and to what one `msg` carries as
they land (`withSharedWords`), on a character's edge, and stay words: a link, a `/command` or an intent among
them is never followed, and nothing runs without Send. A share of which nothing lands, or that carries nothing the
app reads, is dropped and logged. Where it goes
is `shareRouteOf`: a Direct Share's shortcut id is looked up among the paired records' conversations and opens
that chat, the list beneath it; an id that names none asks "Send to which Fermix?" (`ShareSheet`), and never
opens a chat made up from it; a generic share goes straight into the one paired Fermix's chat, or asks among
several. Only a Fermix the phone still trusts is a share's: one revoked or whose identity changed
(`Link.needsTrust`) is neither a row of the sheet (`shareRowsOf`) nor the one a share goes straight into
(`shareTargetsOf`); its conversation shortcut is still a Direct Share target, as the conversations carry no link
state, and a share to it asks among the others. With none the phone trusts, the share is dropped and logged ("no
Fermix the phone still trusts is paired"), and nothing on screen says so, as the design has no words for that
case: Welcome shows with none paired, and the Chats list as it was while a Fermix in a trust state or a "Re-pair
this Fermix" row is all it holds. The
share waits in the activity's `ShareModel`, through a rotation, for the lock and the pick (`shareAfter`): with
the app lock on, the lock comes first (`LockGate.sight`), the sheet is never drawn over the lock screen, and a
share is lost once the app leaves with the lock not passed. Each URI's read grant came to the entry, and would
end as it finishes, so the second forward names each URI the entry holds a grant to in its `ClipData`, with
`FLAG_GRANT_READ_URI_PERMISSION`; the activity's record holds the grant from then on until it is destroyed (a
grant the platform will not hand on is logged, and its item then fails to land and is logged). Each item is
read and copied into the chat's own file as it lands in the chat's tray, after the lock and the pick; nothing is
kept of a share before it lands, and a process death before then loses it. Items join what the tray holds, up
to ten: an item past the ten the tray has room for is never copied, nor one whose provider says it is past
`caps.max_media_bytes`, and the copy itself stops a byte past that limit (`copyAtMost`), so an item whose
provider understated its size is deleted as it passes, never held in full; the tray's own line names the first
item too big to go. An image's copy is held instead to the larger of that limit and 128 MiB
(`IMAGE_LANDING_MAX_BYTES`, the app's own bound, as the design names none), from a share as from a paste or the
keyboard: it goes as a JPEG made from the copy at Send and held to the limit as it is made, as a picked image
does, and with "Send as files" its own bytes are held to the limit in the tray, as a picked image's are. The limit
is the chat's record's, and a share that lands in a chat whose model has not read its record yet waits for it, ten
seconds at most (`LIMIT_WAIT_MILLIS`), past which nothing of it is copied, logged. A provider that fails as its
item is described or copied, with an exception carried across the binder, drops that item, logged by the
exception's class alone, and the app goes on; a refusal or a grant gone is logged so too, as the platform's own
words for it name the URI whole, its path among it. Words go at the end of the draft, on a line of their own,
once the stored draft is back, and words none of which fit what one `msg` carries after the draft leave the draft
as it was, logged;
the draft is the composer's, so a draft of shared words that begins with one of the daemon's commands is, as
one typed would be, that command once Send is tapped. Nothing is sent until the owner taps Send, and a send in
flight, a rotation and a process death after the copy keep the tray.

The launcher icon is adaptive (`mipmap-anydpi/ic_launcher.xml`, and `ic_launcher_round.xml` as the manifest's
`roundIcon`): the canvas white background, the two-dot mark as the foreground, and the mark in one colour as the
monochrome layer that Android 13's themed icons draw. minSdk 35 reads only the adaptive icon, so no legacy
bitmap is shipped. The notifications' small icon, `ic_notification`, is that monochrome mark at 24 dp, and the
app draws in the design's own colours, never the wallpaper's (`LauncherIconTest` reads all three).

The app's tests run on
Robolectric through the application convention, over the app's own services and the bundled SQLite, each
test's services ended as it ends (`FermixApplication.onTerminate`, which Robolectric calls after each test,
which a phone never does): Robolectric makes an application per test in one process and keeps the
notification manager's state for the whole process, so services left running wrote into the next test's. The
records' and the settings' stores run in a scope of their own, so they refuse every write once the services
are closed, even while the app lock's setting still waits for a main thread that Robolectric no longer runs;
services end started or not, an end that comes again does nothing, and their coroutines and stores end even
when the system will not release the network watch (`AppServicesTest`, which ends the app's own application
twice); the profile databases stay open until the process ends.
They cover the supervisor's one session per instance, its grace and its timer, a return within the grace never
suspending a session, nothing opened out of sight, a removal with the session closed before it runs and
run only once the store call the session's run was making has returned, "Unpair" sending `unpair` and waiting for
the daemon's close where "Remove" asks nothing, from the list and the Instance screen alike, an opener
that fails, its guards, its scope's end closing every session and overlapping turns; the opener's key
alias and its refusals without a key, a route or the files a removal deleted, and the connection test's race, a candidate named only
for its own failure; the announcer's answers, about its own chat alone, each row kept; the session events
kept, and one after a removal writing nothing, the Instance screen of a removed Fermix doing nothing, a row's unread count, each dropped Fermix's "Re-pair this Fermix" row kept by its id whatever its
title, and the Notifications switch's policy; the lock's gate, a rotation and a phone that cannot lock
among them; the recents preview hidden until the lock's setting is read (`FollowLockSettingTest`); the trust
screens' keys; the navigator's restore, a share's chat it leaves on top, its deep links and its pruning; the window's
flag on Welcome and on the Instance screen, its clearing on the Chats list and its return when the lock
holds, with the list gone and the prompt up; a dropped Fermix's "Re-pair this Fermix" row as the root; a
paired Fermix as a row with its long-press menu; the intents the activity takes, and a chat's link
reaching the running app; a share's intent read as its types say, a replay from Recents taken for nothing, and
the forward's launcher shape and its grants, the launcher's own intent sent first and the grants handed on after
it, and a share of nothing logged (`ShareIntentTest`); the entry handing a share over, bringing the
activity forward and finishing, and doing nothing else (`ShareTargetTest`); its route, a Fermix the phone no
longer trusts never among its targets, and the lock first (`ShareRouteTest`); the share taken from the entry
once (`ShareModelTest`); and the running app taking one (`ShareFlowTest`): words landing in
the one paired chat, "Send to which Fermix?" through a rotation and its pick, a Direct Share naming no paired
Fermix asking, the lock first, the sheet never drawn over it, and the share lost once the app leaves locked, and
nothing with none paired; a Direct Share that starts the activity opening its chat over the one kept from before
(`ShareColdStartTest`); the entry, an activity with no window, affinity or place in Recents, its filters, its
share target and the activity's `singleTop`, as the manifest and the shortcuts declare them (`ShareEntryTest`); the
launcher's adaptive icon and its layers (`LauncherIconTest`); the App lock switch turning on once a screen lock is set; the bars over the
scan in light mode and back after it; a second tap on a failure screen as it leaves dropped; the pairing-wait
service started as the app leaves Verify and not otherwise, and ending itself; and the upload hold, a session
with an upload in flight spared past the grace until its upload ends or the hold passes, and the upload
service started as the app leaves with an upload in flight and not otherwise, naming the computer and
ending itself when the upload ends, the app comes back or the platform's timeout comes (`onTimeout`). The
app's tests that need an upload run under `UploadingApplication`, whose services take each session's upload
from the test; the upload service's run on Robolectric, not on a device, as it is a service the app starts
and stops with no window of its own. The notifications' tests post into Robolectric's notification manager: a push
from the second of two daemons opened and posted on its conversation's channel with its shortcut, its
words and its chat's immutable tap; a row the notified set holds, from an earlier push or its socket row,
posting nothing, a row read before its push and a chat on screen likewise, and a frontier that empties the
set cancelling; an unknown kind, an unreadable plaintext, a profile other than main, a push no key opens,
one outside the envelope's bounds and a trial whose Keystore hangs past the budget each posting the generic
notification, the last at the budget on the virtual clock, and a push no key opens posting nothing while no
Fermix wants pushes; an approval's or a failed turn's id of spaces notified under that id; the app lock's
"New message" with no word of the message anywhere in what is posted; an approval's timeout and its late
copy, a failed turn by its turn, and `onDeletedMessages`' full pull; the session's side of the same owner
(`NotificationsTest`): a socket row posted by the app itself before its push, which then posts nothing, a
live approval off screen, one whose id is spaces, a row read on the phone left out of the next row's
notification, and a rebuild after a read, or as the app lock comes on, that alerts nobody; a read frontier
through `SessionEvents` rebuilding and then cancelling the conversation's notification, a reconciled
connection dropping the expired ids, and a resolved or closed approval's notification cleared; the
registrations over recorded sends, none without `POST_NOTIFICATIONS` or with the channel blocked, an
unregister on coming into sight, a renewal after 7 days and on a new token, an unregister a new token does
not cancel, and the full pull's flag left to the session opened to make it, which the opener's parts carry
(`AppSessions.sessionParts`); `FermixMessagingService` itself under `SoftKeysApplication`, whose services
hold the device key in software in place of the Keystore, a `notification` block shown by no one while the
app is out of sight and locked, the manifest's delegation flag off, the app lock and a chat's previews
rewriting the notification showing, and the registrations reading the permission and the channel as the
notifications do; and `GoogleServicesPlaceholderTest`, which refuses an `app/google-services.json` that is
not the placeholder's. The lock's check reads every string the posted notification carries, through its
parcel, and first finds a preview there with the lock off.

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
./gradlew :feature-chats:connectedDebugAndroidTest        # ... and the Chats list's
./gradlew :feature-chat:connectedDebugAndroidTest         # ... and the chat's
./gradlew :app:connectedDebugAndroidTest                  # ... and the app's share, on an app with nothing paired
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
`android.hardware.camera.any` is declared not required, as the pasted link is the other way in), the
exported components exactly those in `policy/exported.txt`, each with the permission it is behind and its
filters whole, their actions, categories and data and the filter's own attributes, so a `BROWSABLE` category, a
scheme or a type added to an exported filter fails it, the libraries' merged in among the app's, and no
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
starts to request lands in `policy/permissions.txt` in the same change, and a component it or a library
starts to export, or an action, a category or data one starts to take, in `policy/exported.txt`.

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

Where CI departs from CI/CD design section 3, for the owner to settle:

- Section 3 budgets `build` at 8 minutes, for compiling, lint, detekt, ktlint and the checksums. CI's build
  job runs all of `./gradlew build`, the done above and the gate's first line, so it runs the JVM tests and
  the screenshots too, as `unit` and `screens` do. It took 10.8 and then 14.6 minutes, and was cancelled at
  16 as stages A2 and A3 grew. Its timeout is 30 minutes.

## Instrumented tests

A `fermix.android.library.compose` module's `src/androidTest` runs on an emulator or a phone, with
AndroidX Test's runner, Compose's test rule and Espresso 3.7 (Compose's own 3.5 cannot start on API 36);
a `fermix.android.library` module that has a `src/androidTest` gets AndroidX Test's runner and its JUnit 4
runner class. `check` builds the test APK (`assembleDebugAndroidTest`), so `./gradlew build` holds the
tests to every gate; running them needs a device. Today `feature-onboarding`, `feature-chats`,
`feature-chat`, `push` and the app have them, and they need no daemon and no camera; the application convention
gives the app the same as a Compose library's once it has a `src/androidTest`. The app's `ShareDeviceTest`
shares through the share entry as the system's share sheet starts it. Another app's image is one the device's
shell, a uid of its own, puts in the media store and shares with `am start --grant-read-uri-permission`, which
the app cannot read without that grant: with two paired it asks "Send to which Fermix?", keeps asking through a
rotation, and lands, copied byte for byte through the grant the entry handed on, in the tray of the chat picked.
The rest share images the app saved itself, which it reads as their owner with no grant: one with two paired,
which asks and lands as the other app's does; twelve, of which ten land; words, which land in the composer; a
`file:` URI and the app's own provider, which never reach the tray; a provider that fails as its item is read
(`content://settings/global`), after which the app takes the next share; and, with a screen lock it sets and
takes away again, the app lock shown first, the share landing once the PIN is typed into the system's prompt.
Another shares the shell's image into a task it starts, finishes the activity and brings the task back as a tap
on its card in Recents does (`ActivityManager.AppTask.moveToFront`): the activity comes back, as the task's own
intent names no grant the app no longer holds. A last test opens the Files picker over the activity, goes home and
taps the launcher: the picker is still what shows, over the same activity. Nothing is sent in any of them. It pairs records no session can open and removes
them, so it needs an app with nothing paired: clear the app's data (`adb shell pm clear io.tezra.fermix`) on a
device that used it. `push`'s `KeystorePushTest`
generates an X25519 agree key in the device's AndroidKeyStore and opens a push sealed for it at test time,
once more with the screen locked behind a PIN it sets and clears again, and logs the trial's time under its
tag, an emulator's and not a phone's TEE. `OnboardingTestActivity` shows the entries in `NavDisplay` over a `TestRig` kept in the
activity's ViewModel store, which holds the fake pairing control (`FakeStarter`, from `src/sharedTest`,
which the JVM tests compile too), the gate's answer, the network facts, a stub preview that reads what a
test hands it and reports a torch, an `ActivityResultRegistry` that answers the camera prompt, and a fake
clip. They cover onboarding from Welcome through Scan to Paired, every failure screen with its title and
labelled actions, the scan's refusal, the camera denied with "Open settings" and the paste clearing only
a clip with a link in it, a rotation and a fold that keep Verify's code and countdown and the paste
sheet's text, TalkBack reaching "Paste a pairing link" first, reduce-motion's still code with the setting
the owner sets, the scan's own reader (`qrReader()`) on real QR, inverted QR, Data Matrix and Micro QR
images (`src/androidTest/assets/codes`), and its analysis (`qrAnalyzer`) on camera frames made of them,
and `clipboardClip` on the phone's own clipboard, a clip's URI given as its own words, never opened. The
test APK is signed with the SDK's debug key; it is not the app, and pairs with nothing. `ChatsTestActivity` shows the Chats list and the Instance screen
over fixed state, two rows and one connected Fermix, with a `ChatsTestRig` kept the same way that counts
the activity's creations and picks the screen; its tests long-press a row for Move to top · Rename · Details · Unpair…, and keep a rename
dialog's half-typed name, on the list and on the Instance screen, through a rotation and a fold.
`ChatTestActivity` shows the Chat screen through `ChatRoute`, its `ChatViewModel` over a fake session and
cache (`FakeChatSession`, `FakeChatStore`, from `feature-chat/src/sharedTest`) holding forty rows and the
daemon's model, kept with the app's fold of the events and a fake monotonic clock in a `ChatTestRig`; its
tests keep a half-typed draft, the row scrolled to, the working indicator's phrase and an approval card's
countdown and buttons through a rotation and a fold, read the long-press menu as Copy · Select text · Copy
code · Share · Info with no Reply or Forward, find one polite live region saying "Fermix is thinking" that
stays the same node with the same words as the phrase changes, find the approval card in the window's
accessibility tree as one node whose custom actions Approve and Deny answer it and its countdown's live
region saying 30 s, then 10 s, and nothing between, pick a model from the chip's sheet, send on Enter and
put a newline on Shift+Enter, open search with Ctrl+F sent through the system's input
(`input keycombination`), and find Copy's words on the clipboard. Beyond the screen the rig answers the
system's pickers and prompts (`PickerRegistry`, through `LocalActivityResultRegistryOwner`), keeps every
activity the chat starts, grants every permission, stands in for the camera (`FakeCamera`) and shows the
Photo Picker's tile in the sheet (`ChatOutside`); its tests bring the Photo Picker's, the documents UI's,
the clipboard's and the camera's items to the tray, cancel a held mic past 120 dp, send it short of it and
lock it slid up, keep a take the phone stopped while held (a lost audio focus, a touch the system took) a
draft that a release does not send, ask for the microphone with its rationale first and offer "Open
settings" once it is refused, open a document and raw HTML through the chooser from the module's provider,
hand one to the share sheet as the same URI and save one into Download/Fermix under its name, copy a document
whose ref climbs out of the cache under `shared/` with the app's own file left as it was, open one whose name
holds a lone surrogate under a name the filesystem holds, save an image whose type is a PDF's as a document,
save a name the media store numbers no further once Download/Fermix holds as many of it as it takes,
the app going on each time, keep a locked recording, its bars and its timer, and an upload with its ring through a rotation and a fold,
and keep the viewer open on its image through a rotation (`ViewerDeviceTest`). `MediaPipelineDeviceTest`
runs the phone's own image pipeline on a photo with GPS and a camera's EXIF, and on a 4,000 × 3,000 photo
that goes up at 2,048 × 1,536; `PhoneVoiceDeviceTest` the phone's own recorder and player, an OGG/Opus note
whose length and levels are read back, and a take another app's audio focus stops, each with the chat's
test activity resumed, as the chat records only on screen: Android 15's focus hardening refuses audio focus
to an app that is not on top and whose process lacks the audio capability (`FOREGROUND_AUDIO_CONTROL`),
and an instrumented process with no activity runs in the foreground-service state without it; Android
16 only logs that refusal. `LinkDeviceTest` taps an answer's links where they are drawn: `tel:`,
`fermix://pair?…`, the chat's own provider's `content:`, `file:`, `intent:` and a reference link's
`tel:` start no activity, and a web address starts the Custom Tab's intent at it, in the instance's
tint; and taps the words on the lines above and below a web address, a quarter of a line from it,
which start nothing, and long-presses them, which opens the message's menu. `PhoneChatTestActivity` is
the same host on the phone's own clipboard and media (`PhoneClip`, `PhoneMedia`), and `PhoneSourcesDeviceTest`
puts what another app would on the clipboard: Paste refuses a `file:` URI of a file planted in the app's
`noBackupFilesDir` or of a copy under the chat's cache, and a `content:` URI of the chat's own FileProvider,
with and without a `0@` prefix, each
logged by its scheme and authority and the tray left empty, while a photo the test puts in the media store, a
provider of another package, lands as the chat's copy, and the camera's capture lands. A provider the test
APK declared would be of the app's own package, the module's under test, and refused as such; the photo is a
row the test, running as the app, owns, so that read is the app's by ownership, not another app's grant.
`PhoneReadsDeviceTest` asks `PhoneMedia` and the tray's thumbnail directly: a file under the chat's cache lands
from the camera alone, never from Photos, Files, Paste or the keyboard, and the media store's photo from those and
never from the camera; a camera's capture as it lands, a
send's copy, a landing copy (`copyAtMost`, which copies the capture whole, or a byte past a limit of one) and the
tray's thumbnail each refuse a `file:` URI of the planted file, the same through a link
the cache holds and a file in a sibling `cache2/`, and the chat's own provider's URI, with a SecurityException
that names no path and nothing copied, while a file the chat made under its cache is read by each, and a
path with a lone surrogate is weighed, never thrown on. A test
that needs the window's focus, Espresso's back and the clipboard's read, waits for it
(`awaitWindowFocus`) and fails naming the window that holds it.

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

The development machine's local pair at API 35 sits beside Android Studio's `Medium_Phone_API_36.1` and
`Pixel_Fold_API_36.1`: cmdline-tools 20.0, the zip CI pins, checked against the sha256 its "Command-line tools
20.0" step names (`.github/workflows/ci.yml`), whose top directory `cmdline-tools/` becomes
`$ANDROID_HOME/cmdline-tools/latest`, then CI's API 35 image and the two profiles under these names:

```bash
zip=commandlinetools-linux-14742923_latest.zip
work="$(mktemp -d)"
curl -fsSL -o "$work/$zip" "https://dl.google.com/android/repository/$zip"
echo "04453066b540409d975c676d781da1477479dde3761310f1a7eb92a1dfb15af7  $work/$zip" | sha256sum -c -
unzip -q "$work/$zip" -d "$work"
mkdir -p "$ANDROID_HOME/cmdline-tools"
mv "$work/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager "system-images;android-35;google_apis;x86_64"
image="system-images;android-35;google_apis;x86_64"
echo no | avdmanager create avd -n Medium_Phone_API_35 -d medium_phone -k "$image"
echo no | avdmanager create avd -n Pixel_Fold_API_35 -d pixel_fold -k "$image"
```

CI's matrix has an API 35 row, and API 35 enforces what 36.1 lets through (audio focus refused to an app
with nothing on screen, which only API 35 failed), so a change to anything a device test touches runs on
both levels, the phone and the fold of each, before it is pushed: four runs, one emulator at a time. In the
script below, `-avd` then names `Medium_Phone_API_35`, `Pixel_Fold_API_35`, `Medium_Phone_API_36.1` or
`Pixel_Fold_API_36.1` in place of CI's `ui_35_medium_phone`.

Locally, run the tests as a script, with `emulator` and `adb` on the PATH; its trap and its exits end
the shell that runs it, so it is not for pasting into a terminal:

```bash
serial=emulator-5554
emulator -avd ui_35_medium_phone -port 5554 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim \
  -feature -QuickbootFileBacked &
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
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.tezra.fermix.onboarding.FoldingPhone || exit 1
ANDROID_SERIAL="$serial" ./gradlew :feature-chats:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.tezra.fermix.chats.FoldingPhone || exit 1
ANDROID_SERIAL="$serial" ./gradlew :feature-chat:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.tezra.fermix.chat.FoldingPhone || exit 1
ANDROID_SERIAL="$serial" ./gradlew :push:connectedDebugAndroidTest || exit 1
adb -s "$serial" shell pm clear io.tezra.fermix   # nothing paired for the app's share tests; a first run has none to clear
ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest
```

`-feature -QuickbootFileBacked` keeps the guest's 2 GB of RAM out of a file: a host that pins
`vm.dirty_bytes` low, as Pop!_OS's defaults do at 256 MB, throttles that file through writeback, and a
cold boot never comes online, while with the feature off it boots in 20 s.

`ANDROID_SERIAL` names the device to use. `scripts/settle_emulator.sh` waits, 30 polls 2 s apart, for
the home screen to have the focus, as a cold boot can leave a system dialog holding it, System UI's
"isn't responding" among them; each poll wakes the phone, dismisses the keyguard and closes system
dialogs, and it fails naming the window that has the focus. A fold test (each module's `@FoldingPhone`)
folds the phone and waits for its screen drawn again; a Pixel Fold locks as it folds, and the test
activity shows over the lock screen (`showWhenLocked` in `src/androidTest/AndroidManifest.xml`), as the
lock can come after the activity is made again. The chat's draft-keeping window-change test waits,
bounded, for the row it scrolled to to be displayed: over the window a rotation makes again, the soft
keyboard a typed draft brought up stays (Android 15) or comes back (16) until that window takes the
focus, which Compose's idle does not know, and while it is up a phone on its side has no room for
the list. It was so on the phone (API 35 and 36.1 alike); the fold test shares the wait, and its one
failure was never reproduced. On a device whose `cmd device_state` cannot close it, a fold test's
assumption skips it, and AGP's report counts that skip among the failures while the task passes, so
on a phone leave it out with the `notAnnotation` above, one module's annotation per run: of a list,
AGP hands the runner the class before the first comma alone. On a folding AVD run them with
`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`, which turns that skip into a failure.
Animations stay on: the reduce-motion test sets the animator scale itself and puts it back.

CI's `ui` job runs them on API 35 and 36, as `medium_phone` (the fold test left out) and as
`pixel_fold` (the fold required), four runs side by side on `ubuntu-24.04` with KVM opened by a udev
rule, through `reactivecircus/android-emulator-runner` (pinned by commit). The runner's cmdline-tools
are 12.0, which know no `pixel_fold`, so the job replaces them with 20.0, checked by its sha256, and
looks the profile up before any emulator starts. It signs the app under test with a debug key made for the
run, as the build job does, and builds the test APKs before the emulator starts, so a compile error is never
taken for a boot that failed. The action's pre-launch script marks the AVD made,
and the script settles the emulator (`scripts/settle_emulator.sh`) and marks the boot before the tests:
only when the AVD was made and the emulator never booted or never settled do the tests run once more,
and a setup that failed before the AVD existed is not retried. The job's summary
says which happened, or that the tests never ran, and whether KVM was open, and the reports are
uploaded. `gate` requires it.

## Firebase

The Google services Gradle plugin reads the app's Firebase configuration, `google-services.json`, into
resources. The repository holds a placeholder, `app/google-services.json`, for a project named
`fermix-placeholder` with no real key, so the app builds and its tests run anywhere; a build with it asks
FCM for a token and gets none, logs so, and registers nothing. Push needs the owner's Firebase project
(`MILESTONE_51_ANDROID_APP_DEVELOPER_ONBOARDING.md` section 2.4): its Android app is registered with the
package name `io.tezra.fermix` and the signing certificates' SHA-256, and its `google-services.json` is
downloaded from the Firebase console. Put that file at `app/src/debug/google-services.json` for your debug
builds, or `app/src/release/google-services.json` for a release, where the plugin looks before
`app/google-services.json`; `.gitignore` keeps both out of the tree. Never put it over
`app/google-services.json`, Firebase's default path: that file is tracked, and `GoogleServicesPlaceholderTest`
fails the build on one that is not the placeholder's. The file is not a secret, but it is
the owner's project, so it never enters the repository. The service account a daemon sends with is a
secret and lives on the daemon alone.

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
