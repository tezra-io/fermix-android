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
- **The wire codec.** `core-protocol` frames, encodes and decodes every protocol v1 event and the
  protocol v2 changes, joins continuation runs and reads the pairing link, and refuses anything
  outside the contract with an error that names it; its JVM tests replay the engine's fixtures
  byte for byte.
- **The transport.** `core-transport` opens the WebSocket to a daemon pinned to the certificate its
  pairing link names, races the daemon's candidates, and reads the network facts behind the
  reachability copy; its JVM tests run the pinned TLS against a local server and the race on a
  virtual clock.
- **The session.** `core-session` keeps one paired session with a daemon: hello first, the outbox,
  the cursors, an ack only for a row the owner was told of or has read, across restarts too,
  reconnect reconciliation, the keepalive and the close codes, and the turn machines with the
  working indicator; its JVM tests run it against a fake daemon built from the protocol's own
  models, on a virtual clock.
- **Pairing.** `attest` checks that the phone can hold a Fermix key, makes each pairing attempt's
  key in AndroidKeyStore under a fresh alias, attested with the pairing secret's challenge, and
  checks its chain's shape; `core-session` runs the pairing ceremony over it, from the scanned link to
  the code to compare and the owner's decision, and hands the approved connection on as the first
  session. The key the phone held for that daemon before is deleted only once the new record is
  stored, and every other ending deletes the attempt's key. A newer pairing link is refused as such.
- **The design language.** `design` holds design section 13.1 as code: the colours, the six tints,
  the type scale in the bundled Google Sans Flex and Google Sans Code (google/fonts' files,
  unmodified, with their OFL), the shapes, spacing, the centred column, motion with reduce-motion,
  and the haptics, all handed to Material 3, never Dynamic Color. The launcher icon is the two-dot
  mark, with a themed monochrome layer. Every preview is drawn at twelve windows and compared with
  its reference image, in `check` and in CI's new `screens` job, which uploads any image that changed
  and also fails on a reference image that no preview drew.
- **The phone's durable state.** `data` keeps the paired daemons' records, public data only, in a
  typed DataStore, with the re-pairing and renaming rules of the design; one database per paired
  daemon and profile, holding the timeline cache with its offline full-text search, the outbox, the
  cursors and the set of what the phone has already notified; and a media cache checked by digest and
  bounded at 512 MiB. A daemon whose key the phone no longer holds is dropped at launch, and the
  "Re-pair this Fermix" notice for it survives the app being closed before it is shown. Its JVM tests
  run the same bundled SQLite the app ships, and CI fails a database change that would not open on a
  phone already holding the old one.
- **Onboarding.** The app opens on Welcome, in the design's theme, and pairs with a Fermix: the
  hardware check, the two ways to open pairing on the computer, the scan's frame and the pasted link,
  the connecting steps, the code to compare with its countdown, Paired, a name for a second Fermix on
  the same computer, and the notifications question, with every failure its own screen in the design's
  words. Back follows the screens with the predictive gesture, onboarding is kept out of screenshots, a
  rotation keeps the pairing and its countdown, and leaving the code for another app shows "Waiting for
  approval" until the computer answers. TalkBack reads the code digit by digit and reaches "Paste a
  pairing link" first on the scan, and a refusal is felt as well as read. The app now requests the
  network, a foreground service and notifications, each listed in `policy/permissions.txt`.
- **Scanning and pasting the code.** The scan reads the computer's QR code with the camera, on the
  phone, a dark terminal's inverted code too, with the torch once the camera has one. It says why it
  wants the camera before asking, and after a no offers the app's settings and the paste. "Paste a
  pairing link" opens a sheet that takes the link from the clipboard, out of `fermix pair`'s whole
  "Manual pairing URI:" line too, and clears a clipboard with a pairing link in it once it is read,
  whatever the link turns out to be; a half-typed link survives a rotation. A code from an older or a newer Fermix gets its screen at once. The app now also requests
  the camera, and runs on phones without one, which the release policy now checks. Under "Remove
  animations" the code to compare stands still. CI's new `ui` job runs onboarding's tests on
  emulators, Android 15 and 16, as a phone and as a folding phone.
- **The release policy.** CI's new `policy` job checks the release APK: its SDK levels, not
  debuggable, no cleartext traffic, no resizability opt-out, nothing in backup or device transfer, now
  also for the platforms before Android 12 and in every platform-specific override of the rules, each
  rule counted only where a phone reads it, exactly the permissions `policy/permissions.txt` lists, and
  no test key, vector, vector key or fixture inside, under its own name or any other.
- **The Chats list, the Instance screen and the sessions behind them.** Each paired Fermix is a row
  with its avatar, its connection dot, "thinking…", a draft or its last message, the time and the unread
  count, and a long-press for Move to top, Rename, Details and Unpair…. The app keeps one session per Fermix while it is in sight and puts them aside 5 s after it
  leaves, takes the pairing's own session over at approval, and shows a revoked phone or a changed
  identity on its own screen with "Pair again" and "Remove", a protocol error never as revoked. The
  Instance screen shows the connection, the candidates, each lit only by its own handshake, and a
  connection test, this phone's name and key, kept out of screenshots, notifications, storage, the
  diagnostics log and Unpair. A Fermix a restore left without its key is offered as "Re-pair this
  Fermix" until that same daemon is paired again. An app lock asks for a biometric or the
  screen lock after 5 s away, showing nothing of the app until it opens, with the window kept out of
  screenshots while locked. A link to a Fermix this phone is paired with already asks before it pairs
  again and replaces the phone's key. The last chat opens again at launch,
  `fermix://chat/{instance}/{profile}` from the app's own shortcuts opens one, each Fermix has a
  conversation shortcut and channel named as its row, and "Add Fermix" is a launcher shortcut. Until notifications come,
  a row the owner has not seen is kept and not acknowledged, so its push still comes. The app now also
  requests `USE_BIOMETRIC`.
- **The chat.** A Fermix's chat shows its conversation with the bar's live subtitle and a banner when the
  phone is offline or the computer is out of reach: the working indicator with its headings and tool
  chips while the agent works, answers streaming in as markdown with code and table cards and no raw
  HTML, the owner's messages with their one tick, queued, pending or refused, error cards with one action
  and no run again unless asked, notices, model changes and a job's deliveries. The composer sends and
  stops, a typed `/stop` too, at once or not at all, queues while a reply streams and sends again once a
  command's answer ends its turn, keeps a draft per chat, opens the slash palette, and takes Enter,
  Shift+Enter and Ctrl+K from a keyboard; a long-press copies, selects, shares or shows a message's Info,
  and several messages copy or share as a transcript. A row that lands on the chat on screen is
  acknowledged only once the list holds it. The thinking card becomes the answer's bubble, which then
  ticks and is read aloud once; a new bubble rises in, the date shows while scrolling, and older messages
  load under a skeleton. The Chats list's last message is the plain words of its markdown, and the next
  launch reaches a Fermix first over the route that worked last.
- **Cards and controls in the chat.** Approval polls count down and answer with Approve or Deny, as
  buttons or TalkBack actions, then become their receipt, an expired one at once; a reaction shows on the
  owner's message, and up to two link previews under their row, their thumbnails fetched from the
  computer only, opening in a Custom Tab. A link in a message opens there too when it is a web address,
  and nothing otherwise: a phone number, a file or an app's link is never handed to another app, and the
  words beside a link are the message's, a long-press on them opening its menu. The model chip and its
  "Model" sheet switch the chat's model, `/model` too. Search finds messages through the computer's index,
  or the phone's while offline, from the bar or Ctrl+F, says when the computer's search fails, and jumps
  to each hit with its words marked.
- **Photos, files and voice notes.** The composer's + opens the attach sheet: photos from the system's
  Photo Picker, files, the camera and the clipboard, with a caption, "Send as files" and the size limit
  said inline; pasted and keyboard images are kept as they land. Photos leave the phone as JPEGs with no
  location or camera data. Uploads show a ring, or a bar under a document or a voice note, survive a
  dropped connection, a rotation or a fold, start again when the computer stops answering, and keep going
  for a while when the app leaves the screen ("Sending to {computer}"); messages reach the computer in the
  order they were written, an upload ahead of them or not. Images from the computer show in grids and open
  in a full-screen viewer with zoom, share and save; documents open in another app. Holding the mic records
  a voice note: slide left to cancel, up to lock; a call, leaving the app or the system taking the touch
  keeps it as a draft, which only a tap sends, and a rotation mid-hold locks it. A draft stays with its
  chat until it is sent or discarded, even after the app closes. Notes play in the chat with their
  transcript and their own waveform. The app now asks for the microphone (`RECORD_AUDIO`). Nothing a
  computer names and nothing another app hands over reaches the app's own files: a document opened, shared
  or saved is copied only into the chat's own folder for it, whatever the computer calls it, and Photos,
  Files, Paste and the keyboard take another app's content only, never a file of the app's or its own. A
  name or a type the phone cannot take never stops the app: an image the computer says is a document is
  saved into Download/Fermix, and a save the phone refuses, the 33rd file of one name in a folder among
  them, is let go. Pasting a pairing link never opens a file another app put on the clipboard.
- **Notifications and push.** A reply, a proactive message, an approval or a failed turn on a computer
  the phone is not connected to arrives as an FCM push the phone decrypts with the key it paired with,
  and shows as the chat's conversation notification under that Fermix's name, with its words unless
  previews are off or the app lock is on, then "New message"; an approval times out with its expiry, and
  one that arrives late says it expired. A message the phone already showed, read on the computer or
  open on screen alerts no one, whichever of the push and the connection comes first. A push the phone
  cannot read still shows "New message" while a Fermix has its notifications on, and a notification
  someone else writes into a push is never shown. A message read on the phone leaves its notification at
  once, and a later one lists only what is unread; turning the app lock on, or a chat's previews off,
  takes the words out of a notification already showing; an approval answered or expired on the computer
  takes its notification with it. The phone registers for push when its notifications can show, again
  every 7 days and on a new token, and unregisters when they cannot, a new token included. The app now
  holds `WAKE_LOCK` and `com.google.android.c2dm.permission.RECEIVE`, from Firebase Messaging.
