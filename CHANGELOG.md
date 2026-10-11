# Changelog

All notable changes to the Fermix Android app are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). A release is a `vX.Y.Z` tag on `main`, and
the app's version is the tag's; its release pull request raises `versionCode` in `version.properties` and
renames `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` (`docs/RELEASING.md`).

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
  mark, with a themed monochrome layer. Every preview is drawn at twelve windows by
  `./gradlew recordRoborazziDebug`, which CI's new `screens` job runs on every run, failing on a
  preview that cannot be drawn and on a module whose previews are no longer found. No screenshot is
  kept in the repository (the owner's decision of 2026-10-05), so nothing compares the screens with
  stored images: a developer sees what a change moved against a recording made on their own machine
  before it.
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
- **Share into Fermix.** Another app's images, videos, files and words come in through the system's
  share sheet and Direct Share, whose targets are the paired chats' conversation shortcuts: one paired Fermix
  takes a share straight into its chat, several ask "Send to which Fermix?", and a Direct Share opens the chat it
  names, even as it starts the app; a Fermix revoked or whose identity changed is never listed on the sheet nor
  taken straight into, though its Direct Share target stays and asks among the others. Items land in the chat's
  tray, copied as they land, up to ten, and words at the end of the draft; nothing is sent until Send. An item
  past the daemon's limit is never copied in full, but an image, which goes as a smaller JPEG, is copied up to
  128 MiB as a picked one goes; no copy holds more than 128 MiB whatever the computer's limit, or before the
  computer has said it, so a stream that never ends fills nothing; an item's name and type are held to a file's
  name and a plain type, however long another app made them; shares that come at once land one after another, ten
  at most in the tray, each waiting its turn a minute at most and then given a minute to land, after which what had
  not landed is left out and the next share lands; the owner's own picks never wait behind another app's share; a
  provider that fails drops its item and not the app, a `file:` URI or one of the
  app's own is refused, the app lock comes first, a share is never taken again from Recents, a task a share
  started comes back from Recents, and the tray survives a rotation and a process death. The launcher icon has a
  round variant beside its themed monochrome layer. The release policy check holds the exported components to
  `policy/exported.txt`, each filter whole.
- **A message too long for one frame never stops the app.** Words past what one message carries, typed,
  pasted, edited back from the outbox or as a caption, are kept in the field and not sent, with "This message is
  too long to send" above the composer, the slash palette open or not; another app's shared words are cut to what
  one message carries as they land, which only the app's log says. A slash command's words, "Run again" on a long
  message, a model's name, a command's name, a picture's address and an approval's answer from the computer are
  weighed as they would go on the wire, so none of them stops the app; a message sent once the outbox holds 128
  waiting is refused and stays in the field, and so does an approval's answer. A paste of any length into the
  field outlives the app going to the background, the words kept by the chat rather than in the state the system
  saves for it.
- **A picture whose few bytes say it is huge is not decoded.** One another app shares, pastes or types in, or
  one picked, that says it is past a quarter of a gigapixel, draws no thumbnail in the tray and is not sent, refused
  before a pixel is decoded, so it holds no thread for the minutes its pixels would take.
- **A file that goes on past the limit does not fill the phone.** A document, a video or a photo sent as a file is
  copied for Send no further than a byte past the computer's size limit, once the computer has said it, so one whose
  app says it is small, or says nothing of its size, and then hands over more is refused as too big, with nothing of
  it sent and nothing of it kept on the phone; one at the limit or under it still goes, its size said or not. A send
  that waits on an app that stopped handing its file over is let go when the chat closes, though an app that ignores
  that can keep the copy's thread waiting until it answers. A file whose app hands it over in fits, with nothing to
  read for a moment, is waited for, a minute at most between its bytes, as it is sent and as it lands from a paste,
  the keyboard or a share.
- **A device test that hangs fails by its name.** Every instrumented test has three minutes, and the wait for the
  screen to settle after a rotation or a fold has fifteen seconds, so a run that would hang fails the test instead.
- **Who is paired and whether the app is locked are read as written.** Anything that reads the paired Fermix
  list or the app lock while it is being written, a screen, the lock, a notification or a push, gets what the
  write wrote once it ends, never the list or the lock from before it, nor an empty list with the lock off; and
  each file is on the disk whole before it replaces the old one in one step, so a phone that stops mid-write,
  the app killed or the power cut, keeps them as they were or as written. A window keeps its preview
  out of Recents until the app lock's setting is read. FCM's new token is taken before the app lets FCM go.
- **Releases.** A `vX.Y.Z` tag on `main` builds one candidate, signed with the release key once the build that
  never sees the key has ended, and stages it as a draft release with its universal APK, its app bundle, R8's
  mapping and `SHA256SUMS`, signed with cosign; a tag off `main`, one that is not the build's version, a
  `versionCode` not raised, a version with no changelog entry, a contract from an unreleased engine or from one
  that does not serve the app's protocol, a commit whose CI is not green, an APK or app bundle that breaks the
  release policy and a build signed by any key but the release entry of the engine's `android_signers.json` are
  each refused. A candidate is published, and its bundle sent to Play's internal testing track, only on a merged
  record of the device gate and all eight release scenarios passed on real phones, for that candidate's very APK,
  against a released engine, with the owner's approval; its files are checked again before they are published and
  after; no input skips a check. The app's version name is now its release tag, or `0.0.0-dev` on a build with no
  tag behind it. Until the engine serves the app's protocol 2 (stage D1) and ships `android_signers.json` (stage
  D2), every candidate is refused.
- **The app's screens from every CI run.** CI's `screens` job keeps every screen's preview in a phone's window,
  light and dark, as its `app-shots` download for 30 days (`app-shots-incomplete` when a preview failed to draw),
  and `scripts/app_shots.sh` makes the same pictures from a developer's own recording.
- **A demo Fermix in the debug app.** A launcher entry, **Fermix demo**, copies a pairing link the app
  pairs over as over a real one, through Connecting, Verify and the computer's approval, to six scripted
  Fermixes with chats, an "Unread messages" divider, a turn still running, an approval card waiting and
  replies streamed in, all in memory and with no network, so the whole app can be walked with no daemon.
  The demo offers no notifications. The release never holds it: its policy check refuses an APK with any
  class, component or resource of the demo, and, with the release's R8 mapping, a class of it under any name.

### Changed

- **A monochrome look.** The app is drawn in one ink, near-black on white in light mode and a soft off-white on
  near-black in dark mode: primary buttons, your own messages, links (always underlined), switches, chosen
  chips, progress and the running tool's arc are the ink, and Material's components are given the same colours,
  so none falls back to a colour of its own. Fermix blue is kept for what is unread, the count on a Chats row, the
  line above the first unread message and the count on the scroll-to-latest button, and for the Fermix wordmark's
  two eye-dots. Error text in dark mode is a lighter red that reads on the dark background. A link to a web page
  is underlined, such as "Don't have Fermix yet?" and "Troubleshooting", and so is an action in the app that only
  the blue told apart from the words around it, such as "Test connection", "Run again" or "Try again".
- **A focus ring.** When you move around the app with a keyboard or a d-pad, the button, chip, row, field or
  message that has the focus shows a thin ring around it (just inside the edges of a row that spans the screen),
  in the colour of the words around it, so you can always see where you are, and a link in a message turns
  light on dark. A list scrolls far enough to show the whole ring, and a menu or a dialog that opens takes the
  ring with the focus. A touch hides it again, and nothing else about the app changes.
- **The Fermix mark.** The two-dot mark gives way to the Fermix mark, the design's own drawing, vendored with its
  provenance. On Welcome it drops in: a dot falls, lands with a light tick, swells into the mark, the visor opens
  and the eyes pop in, and the words rise in under it; then it breathes and blinks. On Paired its eyes turn happy
  and it hops once as the phone confirms the pairing. Connecting and Verify show it at work (below). Each moment plays
  once, not again after a rotation, a fold or a return; with Remove animations on, the mark stands still from the
  first frame, and the tick never plays for a landing it did not draw. On a phone on its side Welcome's mark stands
  near the top, so "Get started" stays in view. TalkBack reaches Welcome's words and buttons from the start, before
  they rise in. The launcher
  icon, its themed layer and the system's splash are the mark too; the notification icon stays the two-dot mark,
  as does the chat's thinking card, its two dots orbiting in the ink. An instance's avatar is now its colour
  alone, everywhere it is drawn: the Chats list, the share sheet, a chat's bar, the Instance screen, the name
  step of onboarding and the conversation shortcuts.
- **Onboarding moves.** Its screens slide a short way forward and back between the steps, and fade through into
  and out of the camera, a failure and the app; a back swipe scrubs the same motion, letting go finishes it, and
  turning back before letting go settles it back. The mark moves and resizes from Connecting to Verify to Paired as
  one. Pair's diagram builds once, and Copy shows a check. The scan's frame settles in each time the camera shows,
  breathes while it looks, locks onto a Fermix code with a flash before Connecting follows, and a code it refuses
  makes its line shake. On Connecting the mark looks for your computer, narrows its eyes as it checks and opens
  them as the line is secured; the line changes in place, and the current step is a wider pill. On Verify the mark
  looks down at the code, the ring empties smoothly, and at 30 and 10 seconds left it pulses once and TalkBack says
  the time left. The bell on Notifications swings, and turns into a check when you allow them; your yes counts as
  you give it, even if you go back before onboarding ends. A failure's icon settles in, and the wrong-machine
  warning draws its red edge across. Your new Fermix's row rises into the Chats list, unless a back swipe has shown
  it already. A rotation in the middle of any of it shows it done, and plays none of it again, and a new pairing
  plays all of it anew, even after the app lock hid the last one. Going back just after the camera has read a code
  ends the pairing it would have started, and so does a back swipe begun then and let go. With Remove animations
  on, nothing moves and every screen changes at once.
- **CI's emulators keep the system's error dialogs off the screen, and end in time.** The `ui` job's emulators no
  longer draw an "isn't responding" or "has stopped" dialog, one of which, the launcher's on a slow runner, held
  the screen through eighteen of onboarding's tests; a device test that waits for the screen while such a dialog has
  it says so. Each run stops its emulator itself, within a minute, so an emulator that does not exit no longer
  keeps a job whose tests all passed running until it is cancelled, and a job has 50 minutes rather than 25, room
  for a runner twice as slow and for the second run after an emulator that did not boot.
- **The Fermix wordmark.** Welcome's title and the Chats list's bar show the Fermix wordmark that the Mac and Linux
  apps show, in place of the name set in type: its letters in the app's ink and its two eye-dots in Fermix blue, in
  light and dark mode alike. It is the apps' own drawing, vendored with its provenance, never redrawn. On Welcome it
  rises in where the name did; TalkBack reads it as "Fermix", as before.
