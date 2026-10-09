package io.tezra.fermix.onboarding

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.iconFocusRing

private val SHEET_SIDES = 24.dp
private val SHEET_BOTTOM = 16.dp
private val SHEET_GAP = 16.dp

/** A link wraps; the field grows to this many lines and scrolls past them. */
private const val FIELD_LINES = 4

/**
 * The primary clip, which a pasted link comes from (design section 12.4): the phone's [ClipboardManager]
 * ([clipboardClip]), or a test's fake.
 */
interface PrimaryClip {
    /** The clip's first item as text, or null when the clipboard holds nothing. */
    fun text(): String?

    /** Clears the clip: all an app can do about a link in it (section 6.5). */
    fun clear()
}

/** The phone's primary clip, through [ClipboardManager]. */
fun clipboardClip(context: Context): PrimaryClip =
    SystemClip(checkNotNull(context.getSystemService(ClipboardManager::class.java)) { "no clipboard service" })

/**
 * The clip's first item's text, or else its URI's own words, never opened, and none for an item with neither:
 * ClipData's coerceToText would read a `content:` URI another app put there with this app's own rights, its
 * non-exported providers among them.
 */
private class SystemClip(
    private val clipboard: ClipboardManager,
) : PrimaryClip {
    override fun text(): String? {
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.text?.toString() ?: item.uri?.toString()
    }

    override fun clear() = clipboard.clearPrimaryClip()
}

/**
 * The paste sheet's field: its [text], which may carry a pairing's secret and is never printed, and whether
 * the scan [refused] it.
 */
data class PasteField(
    val text: String,
    val refused: Boolean,
) {
    override fun toString(): String = "PasteField(${text.length} characters, refused=$refused)"
}

/** The paste sheet's actions: the field's edits, Paste, Continue, and the sheet dismissed. */
@Immutable
data class PasteActions(
    val onEdit: (String) -> Unit,
    val onPaste: () -> Unit,
    val onContinue: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * "Paste a pairing link" (design section 13.3, step 2), the second way in, not a fallback: one field for
 * the `fermix://pair?…` link, with Paste, which brings the clipboard's link into it, and "Continue". A text
 * the scan refuses stays in the field, with "That's not a Fermix pairing code." under it, felt and read out
 * as on the scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteLinkSheet(
    field: PasteField,
    actions: PasteActions,
) {
    val colors = LocalFermixColors.current
    // Material draws a sheet's scrim at 32 % of the role, not the canon's; the design passes its own.
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        shape = FermixShapes.sheet,
        containerColor = colors.tonalSolid,
        scrimColor = colors.scrim,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = SHEET_SIDES, end = SHEET_SIDES, bottom = SHEET_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
        ) {
            LinkField(field = field, actions = actions)
            PrimaryAction(
                text = stringResource(R.string.onboarding_continue),
                onClick = actions.onContinue,
                enabled = field.text.isNotBlank(),
            )
        }
    }
}

/** The link's field, the paste button at its end; a URI keyboard that corrects nothing in it. */
@Composable
private fun LinkField(
    field: PasteField,
    actions: PasteActions,
) {
    val colors = LocalFermixColors.current
    OutlinedTextField(
        value = field.text,
        onValueChange = actions.onEdit,
        modifier = Modifier.fillMaxWidth().focusRing(FermixShapes.card),
        label = { Text(text = stringResource(R.string.onboarding_paste_link)) },
        placeholder = { Text(text = stringResource(R.string.onboarding_paste_hint)) },
        trailingIcon = {
            IconButton(onClick = actions.onPaste, modifier = Modifier.iconFocusRing()) {
                Icon(
                    painter = painterResource(R.drawable.ic_onboarding_paste),
                    contentDescription = stringResource(R.string.onboarding_paste),
                )
            }
        },
        supportingText = if (field.refused) refusal() else null,
        isError = field.refused,
        maxLines = FIELD_LINES,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.ink,
                focusedLabelColor = colors.ink,
                cursorColor = colors.ink,
            ),
    )
}

/**
 * The scan's refusal under the field, as the scan gives it: `REJECT` (design section 13.1), and a polite live
 * region, so TalkBack reads it as it comes.
 */
private fun refusal(): @Composable () -> Unit =
    {
        HapticOnce(HapticUse.Refusal)
        Text(
            text = stringResource(R.string.onboarding_scan_not_fermix),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
