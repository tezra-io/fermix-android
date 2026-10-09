package io.tezra.fermix.onboarding

import androidx.compose.runtime.Composable
import io.tezra.fermix.design.BellSwing
import io.tezra.fermix.design.Drop
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.Hop
import io.tezra.fermix.design.LookDown
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.Narrow
import io.tezra.fermix.design.PairBuild
import io.tezra.fermix.design.ReticleMotion
import io.tezra.fermix.design.checkingAt
import io.tezra.fermix.design.hopAt
import io.tezra.fermix.design.lookingDownAt

// Onboarding's screenshot tests: every screen of design section 13.3 and every failure, each at the twelve
// windows of @FermixPreviews, with the visual canon's own example values: the host suj-mbp, the phone
// Pixel 9 Pro, 1:42 left, and for the code the vendored IKpsk2 vector's SAS (CodeAndCountdownTest holds
// it to noise_vectors.json). They draw each screen as the app does, from its state, with no ViewModel. Every
// screen that moves is drawn at one instant, a pose, through its stateless `…At`, its moment over and the mark at
// rest, or at the instant a preview names (the M51 update's 7.4), never on a clock: the preview's tester sets no
// inspection mode and draws whatever a clock has reached when it takes the image.

/** The SAS of noise_vectors.json's IKpsk2 vector, the pairing pattern. */
internal const val PREVIEW_SAS = "669979"

/** The canon's "1:42". */
private const val PREVIEW_SECONDS_LEFT = 102

/** The first of the countdown's two marks. */
private const val THIRTY_SECONDS = 30

private val NOTHING = {}

/** The mark at rest, as a screen's moment leaves it. */
private val AT_REST = { MarkPose.Rest }

@FermixPreviews
@Composable
fun WelcomePreview() {
    FermixPreviewTheme {
        WelcomeAt(pose = AT_REST, ms = { Drop.CLOCK_MILLIS.toFloat() }, actions = WelcomeActions(NOTHING, NOTHING))
    }
}

/** The diagram built, as it stands from 900 ms and on the way back from Scan. */
@FermixPreviews
@Composable
fun PairPreview() {
    FermixPreviewTheme {
        PairAt(
            deviceName = PHONE,
            actions = PairActions(NOTHING, NOTHING, {}, NOTHING, NOTHING),
            build = { PairBuild.CLOCK_MILLIS.toFloat() },
            check = { 0f },
        )
    }
}

/** The scan's actions, which no preview takes. */
private val SCAN_ACTIONS = ScanActions(NOTHING, {}, NOTHING, onAllowCamera = NOTHING, onOpenSettings = NOTHING)

/** The scan with its reticle in [reticle], standing whole by default, and its hint still. */
@Composable
private fun Scan(
    state: ScanUi,
    reticle: ReticlePose = ReticlePose.Rest,
) {
    val still = ScanMotion(reticle = { reticle }, shake = { 0f })
    FermixPreviewTheme { ScanAt(state = state, actions = SCAN_ACTIONS, motion = still) }
}

/** The frame with the torch a camera brings, off, over the camera's stand-in. */
@FermixPreviews
@Composable
fun ScanPreview() = Scan(ScanUi(refused = false, torchOn = false))

/** A link the phone refused: the hint becomes "That's not a Fermix pairing code.", its shake over. */
@FermixPreviews
@Composable
fun ScanRefusedPreview() = Scan(ScanUi(refused = true, torchOn = false))

/**
 * A Fermix code found: the reticle at the lock's end, 66 %, with the white flash at its peak, 14 %, the two shown
 * together, though the flash peaks at 60 ms and the lock ends at about 180.
 */
@FermixPreviews
@Composable
fun ScanLockedPreview() =
    Scan(
        ScanUi(refused = false, torchOn = false, found = true),
        ReticlePose(scale = ReticleMotion.LOCKED, flash = ReticleMotion.flash[1].value),
    )

/** Before the system's prompt: why the scan asks for the camera, which the canon does not draw. */
@FermixPreviews
@Composable
fun ScanCameraRationalePreview() = Scan(ScanUi(refused = false, torchOn = null, access = CameraAccess.RATIONALE))

/** After a no: "Camera is off for Fermix", with "Open settings" and the paste. */
@FermixPreviews
@Composable
fun ScanCameraOffPreview() = Scan(ScanUi(refused = false, torchOn = null, access = CameraAccess.DENIED))

/** The paste sheet's actions, which no preview takes. */
private val PASTE_ACTIONS = PasteActions(onEdit = {}, onPaste = NOTHING, onContinue = NOTHING, onDismiss = NOTHING)

/** "Paste a pairing link" opens the sheet, its field waiting for the link. */
@FermixPreviews
@Composable
fun PasteLinkSheetPreview() {
    FermixPreviewTheme { PasteLinkSheet(field = PasteField("", refused = false), actions = PASTE_ACTIONS) }
}

/** A pasted text the scan refuses, which stays in the field with the refusal under it. */
@FermixPreviews
@Composable
fun PasteLinkRefusedPreview() {
    FermixPreviewTheme {
        PasteLinkSheet(field = PasteField("https://example.com/pair", refused = true), actions = PASTE_ACTIONS)
    }
}

/**
 * The canon draws Connecting on its second line: the eyes narrowed, checking, and the current step's pill on the
 * second step.
 */
@FermixPreviews
@Composable
fun ConnectingPreview() {
    FermixPreviewTheme {
        ConnectingAt(
            pose = { checkingAt(Narrow.MILLIS.toFloat(), MarkPose.Rest) },
            phase = ConnectingPhase.CHECKING,
            host = HOST,
        )
    }
}

/** Connecting's first line, which names the host. */
@FermixPreviews
@Composable
fun ConnectingReachingPreview() {
    FermixPreviewTheme { ConnectingAt(pose = AT_REST, phase = ConnectingPhase.REACHING, host = HOST) }
}

@FermixPreviews
@Composable
fun ConnectingTailscalePreview() {
    FermixPreviewTheme { ConnectingAt(pose = AT_REST, phase = ConnectingPhase.TRYING_TAILSCALE, host = HOST) }
}

@FermixPreviews
@Composable
fun ConnectingSecuringPreview() {
    FermixPreviewTheme { ConnectingAt(pose = AT_REST, phase = ConnectingPhase.SECURING, host = HOST) }
}

/** The sheet Pair's pencil opens, holding the phone's name. */
@FermixPreviews
@Composable
fun RenameSheetPreview() {
    FermixPreviewTheme { RenameSheet(deviceName = PHONE, onRename = {}, onDismiss = NOTHING) }
}

/** The eyes looking down at the code, as they stay from 500 ms. */
private val LOOKING_DOWN = { lookingDownAt(LookDown.CLOCK_MILLIS.toFloat()) }

@FermixPreviews
@Composable
fun VerifyPreview() {
    FermixPreviewTheme {
        VerifyAt(pose = LOOKING_DOWN, state = VerifyUi(PREVIEW_SAS, PREVIEW_SECONDS_LEFT, PHONE), onCancel = NOTHING)
    }
}

/** 30 s left: the ring's stroke at its thickest, 5 dp, 150 ms into its pulse. */
@FermixPreviews
@Composable
fun VerifyThirtySecondsPreview() {
    FermixPreviewTheme {
        VerifyAt(
            pose = LOOKING_DOWN,
            state = VerifyUi(PREVIEW_SAS, THIRTY_SECONDS, PHONE),
            onCancel = NOTHING,
            ring = RingState(pose = { RingPose.at(THIRTY_SECONDS).copy(thick = 1f) }, announcing = true),
        )
    }
}

@FermixPreviews
@Composable
fun PairedPreview() {
    // The hop over: the happy eyes, the title risen in.
    FermixPreviewTheme {
        PairedAt(
            pose = { hopAt(Hop.CLOCK_MILLIS.toFloat()) },
            ms = { Hop.CLOCK_MILLIS.toFloat() },
            host = HOST,
            NOTHING,
        )
    }
}

/** A second Fermix on suj-mbp, in Ocean as the canon draws it. */
@FermixPreviews
@Composable
fun NamePreview() {
    val first = record(gateway = 5)
    val second = record(gateway = 1, tint = "Ocean")
    FermixPreviewTheme {
        NameScreen(
            paired = PairedFacts(second, listOf(first), needsName = true, offerNotifications = false),
            onContinue = {},
        )
    }
}

/** Notifications with the bell at [swing] degrees, turned into the check as far as [check] says. */
@Composable
private fun Notifications(
    swing: Float,
    check: Float,
) {
    FermixPreviewTheme { NotificationsAt(swing = { swing }, check = { check }, onAllow = NOTHING, onNotNow = NOTHING) }
}

@FermixPreviews
@Composable
fun NotificationsPreview() = Notifications(swing = 0f, check = 0f)

/** The bell at its swing's first extreme, 14 degrees, at 420 ms. */
@FermixPreviews
@Composable
fun NotificationsSwingPreview() = Notifications(swing = BellSwing.angle[2].value, check = 0f)

/** A grant: the bell turned into the check. */
@FermixPreviews
@Composable
fun NotificationsGrantedPreview() = Notifications(swing = 0f, check = 1f)
