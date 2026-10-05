# Releasing

A release of Fermix for Android is two workflows and one thing a person does between them
(`MILESTONE_51_ANDROID_CI_CD.md` section 4). A `vX.Y.Z` tag on `main` runs `.github/workflows/candidate.yml`,
which builds one candidate, signs it with the release key and stages it as a draft release that only people
with write access see. A person installs that draft's APK on real phones, runs the device gate and the eight
release scenarios against a released engine, and records them in `release-evidence/vX.Y.Z.json` by pull
request. Then `.github/workflows/promote.yml`, run by hand, checks that record, publishes the same files and
sends the app bundle to Play's internal testing track.

**The owner's rule (2026-09-27): the owner tests the app on their own phone before it reaches a store.** Play
sees nothing until the evidence is merged and the owner approves the promotion, and the public sees nothing
until the owner moves a build past internal testing in the Play Console, which no workflow does.

Every step is a script under `scripts/`, so the workflows only wire them, and every refusal is a test in
`scripts/tests` that plants the failure it exists for; CI's `release-scripts` job runs them and lints the
three workflows. Nothing is retried, nothing is rebuilt at promotion (C7), and there is no input, variable or
flag that lets a release past a check (section 8).

## The order, end to end

The order of CI/CD section 6, as this repository walks it:

| Step | Where | What | Who |
|---|---|---|---|
| 1 | engine | The engine releases what the app needs: tag, candidates, draft, approval, published (its `docs/RELEASING.md`). The app's first release needs the engine release that serves mobile protocol 2 (design section 7, stage D1) and ships `android_signers.json` with the release entry (stage D2, E1) | CI, the owner approves |
| 2 | macOS app | Re-vendor the management contract from that engine release, pin the engine, release: the Phone pane that enables the channel and pairs on a Mac whose engine the app manages | CI, the owner approves |
| 3 | here | Re-vendor `contracts/mobile/` from that engine release (`README.md`, "The vendored contract"): `contracts/SOURCE.json` names the release in `upstream.release` and the commit it was built from; a pull request to `dev`, `gate` green | a developer |
| 4 | daemons | Each daemon the phones will pair with runs that engine release, installed as a user installs it, and is prepared for phones ([below](#each-daemon-before-the-phones)) | the owner |
| 5 | here | The release pull request, `dev` to `main`: raise `versionCode` in `version.properties` and write the version's `CHANGELOG.md` entry (below); `gate` green; merge | a developer, the owner merges |
| 6 | here | Wait for `ci`'s `gate` on the merge commit on `main` to be green, then tag that commit: `git tag vX.Y.Z <merge commit> && git push origin vX.Y.Z` | the owner |
| 7 | here | `candidate.yml`: `preflight`, `build`, `sign` (the owner approves it in the `release` environment), `verify`, `stage`: a draft release | CI |
| 8 | phones | The device gate and the eight scenarios on the wave-1 phones, with the draft's APK, against the released engine; `release-evidence/vX.Y.Z.json` merged to `main` by pull request | a person |
| 9 | here | `gh workflow run promote.yml --ref vX.Y.Z -f tag=vX.Y.Z`: `evidence`, `publish` (the owner approves), `play` (the owner approves) and `after-publish` | CI, the owner approves |
| 10 | Play | On a phone that does not have the app, install from internal testing, pair, and receive one push (section 4.3): Play delivers split APKs it signs itself with the release certificate, and the gate ran on the universal APK | the owner |
| 11 | site | The mobile page | the owner |

Steps 2 and 3 do not depend on each other. Step 8 needs step 2 only for the gate and the scenarios run against
an engine the macOS app manages: such a Mac has no supported way to enable the channel or pair but the app's
Phone pane (onboarding section 2.3); against a Homebrew, Linux package or standalone engine, `fermix pair`
covers them. Steps 1 to 3 are needed only when the app needs something the pinned engine does not serve;
otherwise a release starts at step 4, and `preflight` still holds the pin to a published engine release that
serves the app's protocol.

Step 10 needs a phone without this build: a phone that took the draft's universal APK already has this
`versionCode` with this certificate, so Play installs nothing there. Use another phone, or uninstall first,
which deletes that phone's device keys and every pairing.

## Versions and the changelog

`versionName` is the tag without its `v`, and the build reads it from git: the nearest `vX.Y.Z` tag behind the
commit, or `0.0.0-dev` when there is none, as on a shallow pull-request checkout (`build-logic`'s
`AppVersion.kt`). `versionCode` is the one line of `version.properties` and only ever goes up (section 4.4).

The release pull request does two things, and `preflight` refuses a tag whose commit has not:

1. It raises `versionCode` above every earlier release tag's.
2. It renames `## [Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD` and opens a new, empty
   `## [Unreleased]` above it. The entry becomes the draft's notes (`scripts/changelog_entry.sh`).

Once `stage` has made its draft, a tag's version is spent: a tag builds one candidate (C7). A candidate that
fails on a phone is discarded, and the fix ships as the next patch version with the next `versionCode`; its
draft can stay or be deleted, and is never built again under its version either way. `preflight` refuses a tag
that has a draft; it cannot see a draft that was deleted, so that a deleted draft's tag is never built again is
a rule people keep, not one a check holds.

A candidate refused before `stage`, which left no draft, is run again: the tag came before `ci`'s `gate` on
its commit was green, say, or before the `release` environment held its secrets. Re-run that workflow run, all
its jobs, with *Re-run all jobs* on the run's page in the Actions tab or `gh run rerun <run id>`
(`gh run list --workflow candidate.yml` lists the runs); never delete, move or push the tag again. The rerun
builds the same commit from the start, and the owner approves `sign` again. A refusal that the commit itself
causes, a `versionCode` not raised or a changelog entry missing, is not fixed by a rerun: the fix is a new
release pull request and the next version's tag.

## What each job refuses

`candidate.yml`, on a `vX.Y.Z` tag:

| Job | Refuses when | Script |
|---|---|---|
| `preflight` | the tagged commit is not on `main`; a release with the tag exists, published or a draft; the tag does not name the commit, or the build would name the commit by another tag; `versionCode` is not above every other release tag's; `contracts/SOURCE.json` names no published engine release, or one built from another commit than the contract's; the protocol the app speaks (core-session's `SESSION_VERSION`) is outside the contract's `supported_version_range`, so the pinned engine release does not serve it; `CHANGELOG.md` has no entry for the version | `release_preflight.sh` |
| `build` | `ci.yml`'s latest `gate` check run on the commit is missing, still running, or not a success (a job named `gate` in another workflow does not count); the release variant does not build | `release_gate.sh` |
| `sign` (`release` environment) | `GOOGLE_SERVICES_JSON` is missing, is not JSON, is the placeholder's project or has no client for `io.tezra.fermix`; a `FERMIX_RELEASE_*` key variable is set for the build; the checkout holds a release `google-services.json` already; the APK does not carry the file's project; a release key secret is missing; the APK or the bundle is not signed by exactly the `release` entry of `contracts/mobile/android_signers.json`, or that file is absent or not shaped as engine E1 says | `build_candidate.sh`, `sign_candidate.sh`, `sign_check.sh` |
| `verify` | `apksigner` does not verify the APK, or it is not aligned; `scripts/check_release_policy.sh` fails on the signed APK, or on the bundle's base module laid out as the APK Play builds from it; the bundle holds a module besides base; the APK or the bundle is debuggable, another package, or not the tag's `versionName` with `version.properties`' `versionCode`, or they disagree | `verify_candidate.sh` |
| `stage` | the files no longer hash to what `verify` wrote; a release with the tag exists; cosign does not verify its own keyless signature of `SHA256SUMS` as `candidate.yml` on the tag | `stage_candidate.sh` |

The draft holds exactly five files: `fermix-android-X.Y.Z.apk` (the universal APK), `fermix-android-X.Y.Z.aab`,
`fermix-android-X.Y.Z-mapping.txt` (R8's), `SHA256SUMS` over those three, and `SHA256SUMS.cosign.bundle`.

`sign` builds the release with the owner's `google-services.json`, unsigned, and only once that build has
ended writes the keystore and signs with `apksigner` and `jarsigner`: Gradle, and the plugins and libraries it
runs, never hold the key. They do share the job's runner, as section 4.5 has it, and GitHub hands a job every
secret of its environment when the job starts, not only those a step names, to a runner whose user may `sudo`;
a build dependency that meant harm could wait there for the key. Gradle's dependency verification and actions
pinned by commit (C12) are what keep one out. Keeping the key off that runner altogether takes a build job in
an environment of its own that holds `GOOGLE_SERVICES_JSON` alone, which is the owner's call
([below](#where-this-departs-from-cicd-section-4-for-the-owner-to-settle)).

The jobs pass the files on as workflow artifacts kept for one day, the signed candidate from `sign` and the
verified one from `verify`, as the engine's `release.yml` passes its own. This repository is public, so for
that day anyone signed in to GitHub can download them, a release-signed APK among them, which a daemon would
pair with as it pairs with any release. The draft itself stays hidden; the artifacts do not.

`promote.yml`, by hand, on the tag:

| Job | Refuses when | Script |
|---|---|---|
| `evidence` | it runs on any ref but the tag; `release-evidence/vX.Y.Z.json` is not on `main`, or does not parse against `release-evidence/schema.json`; any check of [the evidence file](#the-evidence-file) fails | `check_evidence.py` |
| `publish` (`release` environment) | the tag has no one draft; the draft holds other files than the five; cosign does not verify `SHA256SUMS` as signed by `candidate.yml` on the tag; a file does not hash to it; the APK is not the evidence's; the certificate is not the `release` entry of `android_signers.json`; the draft's files changed after they were checked, before the publish or as it happened | `publish_release.sh`, `check_release.sh` |
| `play` (`release` environment) | the published files fail `after-publish`'s checks; Play holds this `versionCode` as another bundle; Play refuses the edit | `check_release.sh`, `play_upload.py` |
| `after-publish` | the release is not published or holds other files than the five; cosign does not verify `SHA256SUMS` as signed by `candidate.yml` on the tag; a file does not hash to it; the APK is not the evidence's; the certificate is not the `release` entry of `android_signers.json` | `check_release.sh` |

`publish` checks the whole draft again before it publishes anything, as someone with write access could have
changed it since `evidence`, and publishes the draft it checked, by its id. A file swapped between that check
and the publish, deleted and uploaded again under its name, has another asset id: `publish` holds the release's
assets to the checked ones just before it publishes, when a change publishes nothing, and again just after,
when it makes the release a draft again at once and fails, the swapped file public for seconds. GitHub's
*immutable releases*, a repository setting the owner can turn on, would close that window.

`publish` and `play` both run in the `release` environment, as the Play service account is one of its secrets
(section 4.5), so its reviewer approves each: once to publish on GitHub, once to send the bundle to Play.
`after-publish` needs `publish` alone, so a release Play refused is still checked.

`preflight` and `evidence` hold `contents: write` though they write nothing: GitHub shows a draft release only
to a token that can push, and each must see the tag's draft. The scripts' tests run each script with the token
its job holds, read from the workflow, and the fake GitHub hides drafts from a token that cannot push.

## Between the two workflows

What a person does once the draft exists:

1. Download the draft's APK and check it, as anyone checks a Fermix download:

   ```bash
   gh release download vX.Y.Z --repo tezra-io/fermix-android --dir candidate
   cd candidate
   cosign verify-blob --bundle SHA256SUMS.cosign.bundle \
     --certificate-identity https://github.com/tezra-io/fermix-android/.github/workflows/candidate.yml@refs/tags/vX.Y.Z \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com SHA256SUMS
   sha256sum --check SHA256SUMS
   ```

2. Install the APK on each wave-1 phone (`adb install -r fermix-android-X.Y.Z.apk`), stock and with a locked
   bootloader, against daemons prepared as [below](#each-daemon-before-the-phones), running the engine release
   the contract pins, or a later one, installed the way a user installs it: Homebrew, the Mac app, the Linux
   package or the standalone binary, never a source checkout (section 6, step 6).
3. Run the device gate of design section 12.6 on one phone with a freshly generated key: pairing, attestation,
   and a push that arrives and decrypts while the phone is locked.
4. Run the eight release scenarios of design section 15.3, each on a listed phone, scenario 8 on two.
5. Write `release-evidence/vX.Y.Z.json`, with `apk_sha256` the `sha256sum` of the APK installed, and merge it
   to `main` by pull request from a branch off `main`. A failed scenario is recorded as failed: the file says
   what was seen, and `promote.yml` refuses it. The candidate is then discarded (above).

**The owner's own phone keeps its pairings.** The candidate is signed with the release certificate, the same
as every published release and as Play's builds (design D4), so installing it updates the release already on
the phone in place: the Keystore keys and every pairing stay. The release `promote.yml` publishes is the very
APK that was tested, so nothing is installed again after promotion, and Play's later builds update it too.

**A candidate cannot be installed over a development build.** A build from Android Studio is signed with the
developer's own key (`README.md`, "Developer keystore"), and Android refuses an update signed by another
certificate (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Going from a development build to a candidate means
uninstalling first, which deletes the phone's device keys, so every daemon must be paired again
(onboarding section 2.5). Keep the phone you test candidates on to release builds, and develop on another.

## Each daemon, before the phones

The publishing checklist's section B, on every daemon the gate and the scenarios pair with: each runs the
released engine installed the way a user installs it (section 6, step 6), a Linux daemon from its package
(onboarding section 2.2) or a Mac whose engine the macOS app manages (onboarding section 2.3), and is recorded
in the evidence's `engine.install`. Without it the device gate does not pass: a daemon with no FCM credentials
sends no push, so `device_gate.locked_push` and scenarios 2, 5, 6, 7 and 8, which wait on a push, cannot pass.

1. Enable the mobile channel and set its port: on a Linux daemon in `config.toml`, keeping `4031`, and
   `fermix restart`; on a Mac through the macOS app's Phone pane (step 2 of the order), never the CLI.
2. On Linux, open the mobile TCP port and, with mDNS advertising on, UDP 5353 in the firewall.
3. Put the Firebase service account's JSON key in place (below): the keyring or the file store through the CLI
   on Linux, the app's secret row on a Mac; never `config.toml`.
4. `fermix doctor`, or the Mac app's Doctor, shows the mobile listener up and FCM credentials resolvable.
5. Pair each phone (`fermix pair`, or the Phone pane on a Mac), compare the six digits, and check the
   prompt's "Secure hardware" and "bootloader locked".

The development daemon of onboarding section 2.1, run from a source checkout on port `4131` with its key in
`FERMIX_FCM_SERVICE_ACCOUNT`, is prepared the same way for development. The gate and the scenarios never run
against it: a source checkout is no released engine, and `engine.install` has no value for one.

## The evidence file

`release-evidence/vX.Y.Z.json`, against `release-evidence/schema.json` (section 4.2):

| Field | What it records | What `check_evidence.py` holds it to |
|---|---|---|
| `version` | the candidate's `X.Y.Z` | the tag's |
| `apk_sha256` | the sha256 of the APK installed and tested | the draft's APK, downloaded again |
| `engine.version`, `engine.commit` | the engine release the phones paired with | a published, not pre-release, engine release, built from that commit, no older than `contracts/SOURCE.json`'s `upstream.release` |
| `engine.install` | how it was installed: `homebrew`, `macos_app`, `linux_package` or `standalone` | one of those |
| `recorded_by` | who ran the scenarios: a role or a name, never a contact detail | not empty |
| `handsets[]` | each phone: an `id` for this file, its `model`, `os_build` and security `patch` date | ids unique |
| `device_gate` | `pairing`, `attestation` and `locked_push`, on `handset`, `at` a date | all three true, on a listed handset |
| `scenarios[]` | each of design section 15.3's eight: `n`, `name`, `passed`, `handset`, `at`, `note`, and for scenario 8 alone `second_handset` | all eight, once each, under their own names, each passed, each on a listed handset; scenario 8 on two different listed handsets |

The eight, by number: 1 `process_death`, 2 `doze`, 3 `lost_acknowledgement`, 4 `network_change`,
5 `daemon_restart`, 6 `approval_away`, 7 `frozen_socket`, 8 `two_devices`. Dates are `YYYY-MM-DD`. CI checks
that the record is complete and names the right build; it cannot check that it is true (C6).

<!-- check_evidence's tests validate this example -->
```json
{
  "version": "1.0.0",
  "apk_sha256": "9f2c4a7e1b3d5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8",
  "engine": {"version": "0.14.0", "commit": "3b9a2c1d4e5f60718293a4b5c6d7e8f90a1b2c3d", "install": "homebrew"},
  "recorded_by": "owner",
  "handsets": [
    {"id": "p9", "model": "Pixel 9 Pro", "os_build": "BP4A.251205.006", "patch": "2026-09-05"},
    {"id": "s24", "model": "Galaxy S24", "os_build": "AP3A.240905.015", "patch": "2026-09-01"}
  ],
  "device_gate": {"handset": "p9", "pairing": true, "attestation": true, "locked_push": true, "at": "2026-10-12"},
  "scenarios": [
    {"n": 1, "name": "process_death", "passed": true, "handset": "p9", "at": "2026-10-12", "note": ""},
    {"n": 2, "name": "doze", "passed": true, "handset": "p9", "at": "2026-10-12", "note": "idle 70 min, unplugged"},
    {"n": 3, "name": "lost_acknowledgement", "passed": true, "handset": "p9", "at": "2026-10-12", "note": ""},
    {"n": 4, "name": "network_change", "passed": true, "handset": "p9", "at": "2026-10-12", "note": ""},
    {"n": 5, "name": "daemon_restart", "passed": true, "handset": "s24", "at": "2026-10-13", "note": ""},
    {"n": 6, "name": "approval_away", "passed": true, "handset": "p9", "at": "2026-10-13", "note": ""},
    {"n": 7, "name": "frozen_socket", "passed": true, "handset": "s24", "at": "2026-10-13", "note": ""},
    {"n": 8, "name": "two_devices", "passed": true, "handset": "p9", "second_handset": "s24", "at": "2026-10-13", "note": ""}
  ]
}
```

## What only the owner does

Once, before the first release, in this order; each step uses what the one before it made:

1. **The release keystore and its certificate** (onboarding section 2.5), backed up in the password manager
   and offline; its SHA-256 read with `keytool -list -v`, lower case, no colons.
2. **The Firebase project** (onboarding section 2.4; the publishing checklist, A4): an Android app registered
   as `io.tezra.fermix` with the release certificate's SHA-256 from step 1, and its `google-services.json`;
   the Firebase Cloud Messaging API (V1) enabled; a service account with the FCM role, whose JSON key is the
   credential each daemon sends pushes with. That key is a secret of the daemons, never of this repository
   (section 4.5): it goes onto each daemon as [above](#each-daemon-before-the-phones).
3. **Play enrolment** (the publishing checklist, A1 and C): the app record for `io.tezra.fermix`; *App
   integrity*, Play App Signing with the release key uploaded through PEPK, never a key Google generates, and
   the release key as the upload key too, as `sign` signs the bundle with it and nothing re-signs it at
   promotion (C7; [below](#where-this-departs-from-cicd-section-4-for-the-owner-to-settle)); the *App signing key certificate* SHA-256
   checked against the release digest; the internal testing track with its testers. And **developer
   verification** (design D16): the package name and the release certificate registered in the Android
   Developer Console.
4. **The `release` environment** in this repository's settings, **before any `v*` tag is pushed**: the owner as
   its required reviewer, deployment limited to `v*` tags, and these secrets. A workflow that names an
   environment that does not exist makes GitHub create it with no reviewer and no rule, so a tag pushed first
   would run `sign` unapproved and leave the environment open until it is set up.

   | Secret | What it holds | Reaches |
   |---|---|---|
   | `RELEASE_KEYSTORE_BASE64` | the release keystore in base64 on one line: `base64 < fermix-release.jks \| tr -d '\n' \| gh secret set RELEASE_KEYSTORE_BASE64 --env release` | `candidate.yml` `sign`, `sign_candidate.sh` alone |
   | `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` | its passwords | the same |
   | `RELEASE_KEY_ALIAS` | its alias, `fermix-release` | the same |
   | `GOOGLE_SERVICES_JSON` | the Firebase project's `google-services.json`, as text: `gh secret set GOOGLE_SERVICES_JSON --env release < google-services.json` | `candidate.yml` `sign`, `build_candidate.sh` alone |
   | `PLAY_SERVICE_ACCOUNT_JSON` | the JSON key, as text, of a Google Cloud service account with the Google Play Android Developer API enabled in its project, invited in the Play Console's *Users and permissions* with release rights to the app alone | `promote.yml` `play`, `play_upload.py` |

   cosign needs no secret: it signs keyless, as the workflow (C9). Tag `v*` should be pushable by the owner
   alone (a repository ruleset), as in the engine.
5. **The engine's `android_signers.json` release entry** (CI/CD design E1, engine stage D2): the release digest
   in `apps/fermix_core/priv/mobile/android_signers.json`, an engine release, and a re-vendor here (step 3 of
   the order).
6. **Each daemon**, [prepared for phones](#each-daemon-before-the-phones), and the macOS app's release with the
   Phone pane for a Mac whose engine it manages (step 2 of the order).

The first release, once: the Play Developer API creates nothing but a draft release for an app that has never
been rolled out, so the first `play` job fails with Play's words. Upload that release's
`fermix-android-X.Y.Z.aab` to internal testing in the Play Console by hand, roll it out, and run the `play`
job again: it finds Play holding that `versionCode` as the same bundle, uploads nothing, and puts it on the
track. Every later release needs nothing in the console but moving a build past internal testing, which is
always the owner's act.

`scripts/play_upload.py <tag> <aab> --dry-run`, run from a checkout with the account's key as text,
`PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)"`, reads the bundle and the account and signs the token request
with the account's key, and prints what it would send, with nothing sent. It shows that the key file is whole
and that its private key signs; it cannot show that Google takes the key, that the API is enabled, or that the
account has release rights to the app, as only a call to Google does.

## Where this departs from CI/CD section 4, for the owner to settle

- **Not a departure, but the owner's call: the release key shares `sign`'s runner with the build,** as
  section 4.5 has both secrets reach `sign` (above). A `release-build` environment holding
  `GOOGLE_SERVICES_JSON` alone, with the same reviewer and `v*` rule, for a build job before `sign`, would keep
  the key off every runner that ran Gradle, at a second approval per candidate and a departure from the 4.5
  table. Splitting the jobs within the one `release` environment would not: each job's runner holds all of the
  environment's secrets.
- **The app bundle is signed with the release key, in `sign`, and Play takes it as the upload key's.** Section
  4.5 lists a separate "Play upload key" for `promote.yml`'s `play`, and onboarding section 2.5 allows one. A
  separate upload key would mean signing the bundle again at promotion, and C7 publishes the tested bytes
  unchanged; so the release key is the upload key, which Play enrolment (step 3 above) must match. This wants
  the owner's word before Play enrolment.
- **The release is signed outside Gradle,** with `apksigner` and `jarsigner`, where onboarding section 2.5 has
  `signingConfigs.release` read the key; a developer's own release build still reads it as README.md says.
- **`after-publish` needs `publish` alone and runs beside `play`,** where section 4.3 lists it after `play`, so
  that a Play refusal never leaves the published release unchecked.
- **`play` runs in the `release` environment too,** as the Play service account is its secret, so a promotion
  takes two approvals; section 4.3 names the environment on `publish` alone.
- **`promote.yml` runs on the tag** (`--ref vX.Y.Z`), as the environment admits `v*` tags alone, and reads the
  evidence from `main`.
- **`publish` checks the whole draft again** before publishing it, as the engine's `verify-published` does, and
  holds the release to the files it checked across the publish (above); the section asks only `after-publish`
  to check.
- **The signed candidate is a workflow artifact for a day** that anyone signed in to GitHub can download
  (above), as the engine's are; section 4 says the draft is seen only by people with write access.

## What the pipeline cannot prove yet

- `contracts/mobile/android_signers.json` is not vendored: it joins the engine's export in stage D2 (E1), which
  has not shipped. Until a re-vendor brings it, `sign` refuses every candidate and `publish` and
  `after-publish` every release, so no release can complete; the refusal says so.
- No released engine serves the app's protocol. The app speaks mobile protocol 2 (design section 7), which
  engine stage D1 brings; the pinned release, v0.12.1, serves protocol 1 alone. `preflight` refuses every tag
  until the engine release that serves protocol 2 is out and re-vendored (steps 1 and 3 of the order), and
  the device gate could not pass against v0.12.1 either.
- The `release` environment and its secrets do not exist yet. Without its secrets `sign` refuses at its build
  (`GOOGLE_SERVICES_JSON` is empty), and the environment must be made, with its reviewer, before any tag is
  pushed (above).
- No run on GitHub has exercised what needs those: the environment's approvals, the release key, cosign's
  keyless certificate for this workflow's identity, the token's view of a draft, and the Play Developer API.
  The scripts' tests stand in a fake GitHub that hides drafts from a token that cannot push, a fake cosign
  that checks a bundle against its blob and identity, throwaway keys, small packages aapt2 links, and
  `--dry-run` for them.
- Which phones are wave 1 is still open (design section 19, Q12; CI/CD section 9, question 6): the evidence
  check holds each scenario to a handset the file lists, not to a list of its own.

## Checking the scripts locally

On Linux x86-64, as CI is (`lint_workflows.sh` downloads Linux x86-64 builds of its tools), with a JDK, an SDK
holding build tools 36.0.0 and platform 36 (`sdkmanager "build-tools;36.0.0" "platforms;android-36"`),
`python3`, `git`, `jq`, `zip`, `unzip`, `openssl`, `shred`, `sha256sum`, `curl` and network access:

```bash
export JAVA_HOME=... ANDROID_HOME=...
python3 -m unittest discover --start-directory scripts/tests --verbose
scripts/lint_workflows.sh                    # actionlint, shellcheck, ruff and check_workflows.py, pinned by checksum
```
