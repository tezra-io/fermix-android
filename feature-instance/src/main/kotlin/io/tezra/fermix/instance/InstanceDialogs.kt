package io.tezra.fermix.instance

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAX_NICKNAME_CHARACTERS
import io.tezra.fermix.data.NicknameRefusal
import io.tezra.fermix.data.nicknameRefusal
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.textButtonColors

/**
 * "Unpair from {host}…" (design section 13.7): section 13.7's sentence, and "Unpair", which asks [host] to
 * forget this phone and removes it here; the title and the buttons are the visual canon's.
 */
@Composable
fun UnpairDialog(
    host: String,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.instance_unpair_title, host), style = FermixType.headline) },
        text = {
            Text(
                text = stringResource(R.string.instance_unpair_body, host),
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onUnpair, colors = textButtonColors(colors)) {
                Text(text = stringResource(R.string.instance_unpair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = textButtonColors(colors)) {
                Text(text = stringResource(R.string.instance_cancel))
            }
        },
        containerColor = colors.tonalSolid,
    )
}

/**
 * The owner's own name for [record] (design section 13.7): 1 to 40 characters, refused when another
 * Fermix on this phone, among [others], already has it, as data's rule says; the field says which rule
 * refused the name, and "Rename" waits for a name the rule takes. The field's text survives a rotation or
 * a fold.
 */
@Composable
fun RenameDialog(
    record: Instance,
    others: List<Instance>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    var text by rememberSaveable(record.id) { mutableStateOf(record.title) }
    val refusal = nicknameRefusal(text.trim(), record.id, others + record)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.instance_rename_title), style = FermixType.headline) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = refusal != null,
                supportingText = refusal?.let { { Text(text = refusalWords(it)) } },
                textStyle = FermixType.body,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.ink,
                        cursorColor = colors.ink,
                    ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(text.trim()) },
                enabled = refusal == null,
                colors = textButtonColors(colors),
            ) {
                Text(text = stringResource(R.string.instance_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = textButtonColors(colors)) {
                Text(text = stringResource(R.string.instance_cancel))
            }
        },
        containerColor = colors.tonalSolid,
    )
}

/** Which rule refused a name, in a line under the field. */
@Composable
private fun refusalWords(refusal: NicknameRefusal): String {
    val most = MAX_NICKNAME_CHARACTERS
    return when (refusal) {
        NicknameRefusal.BLANK -> stringResource(R.string.instance_rename_blank)
        NicknameRefusal.TOO_LONG -> pluralStringResource(R.plurals.instance_rename_too_long, most, most)
        NicknameRefusal.TAKEN -> stringResource(R.string.instance_rename_taken)
    }
}
