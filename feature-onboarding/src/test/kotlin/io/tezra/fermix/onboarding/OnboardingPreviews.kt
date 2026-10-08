package io.tezra.fermix.onboarding

import androidx.compose.runtime.Composable
import io.tezra.fermix.design.Drop
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.Hop
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.hopAt

// Onboarding's screenshot tests: every screen of design section 13.3 and every failure, each at the twelve
// windows of @FermixPreviews, with the visual canon's own example values: the host suj-mbp, the phone
// Pixel 9 Pro, 1:42 left, and for the code the vendored IKpsk2 vector's SAS (CodeAndCountdownTest holds
// it to noise_vectors.json). They draw each screen as the app does, from its state, with no ViewModel. A screen
// with the Fermix mark is drawn at one instant, its moment over and the mark at rest, never on a clock: the
// preview's tester sets no inspection mode and draws whatever a clock has reached when it takes the image.

/** The SAS of noise_vectors.json's IKpsk2 vector, the pairing pattern. */
internal const val PREVIEW_SAS = "669979"

/** The canon's "1:42". */
private const val PREVIEW_SECONDS_LEFT = 102

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

@FermixPreviews
@Composable
fun PairPreview() {
    FermixPreviewTheme {
        PairScreen(deviceName = PHONE, actions = PairActions(NOTHING, NOTHING, {}, NOTHING, NOTHING))
    }
}

/** The scan's actions, which no preview takes. */
private val SCAN_ACTIONS = ScanActions(NOTHING, {}, NOTHING, onAllowCamera = NOTHING, onOpenSettings = NOTHING)

/** The frame with the torch a camera brings, off, over the camera's stand-in. */
@FermixPreviews
@Composable
fun ScanPreview() {
    FermixPreviewTheme { ScanScreen(state = ScanUi(refused = false, torchOn = false), actions = SCAN_ACTIONS) }
}

/** A link the phone refused: the hint becomes "That's not a Fermix pairing code." */
@FermixPreviews
@Composable
fun ScanRefusedPreview() {
    FermixPreviewTheme { ScanScreen(state = ScanUi(refused = true, torchOn = false), actions = SCAN_ACTIONS) }
}

/** Before the system's prompt: why the scan asks for the camera, which the canon does not draw. */
@FermixPreviews
@Composable
fun ScanCameraRationalePreview() {
    FermixPreviewTheme {
        ScanScreen(
            state = ScanUi(refused = false, torchOn = null, access = CameraAccess.RATIONALE),
            actions = SCAN_ACTIONS,
        )
    }
}

/** After a no: "Camera is off for Fermix", with "Open settings" and the paste. */
@FermixPreviews
@Composable
fun ScanCameraOffPreview() {
    FermixPreviewTheme {
        ScanScreen(
            state = ScanUi(refused = false, torchOn = null, access = CameraAccess.DENIED),
            actions = SCAN_ACTIONS,
        )
    }
}

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

/** The canon draws Connecting on its second line. */
@FermixPreviews
@Composable
fun ConnectingPreview() {
    FermixPreviewTheme { ConnectingAt(pose = AT_REST, phase = ConnectingPhase.CHECKING, host = HOST) }
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

@FermixPreviews
@Composable
fun VerifyPreview() {
    FermixPreviewTheme {
        VerifyAt(pose = AT_REST, state = VerifyUi(PREVIEW_SAS, PREVIEW_SECONDS_LEFT, PHONE), onCancel = NOTHING)
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

@FermixPreviews
@Composable
fun NotificationsPreview() {
    FermixPreviewTheme { NotificationsScreen(onAllow = NOTHING, onNotNow = NOTHING) }
}
