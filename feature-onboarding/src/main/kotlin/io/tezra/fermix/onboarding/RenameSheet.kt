package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing

private val SHEET_SIDES = 24.dp
private val SHEET_BOTTOM = 16.dp
private val SHEET_GAP = 16.dp

/**
 * The phone's name for the pairings to come, `pair_request.device_name`: a field holding [deviceName]
 * and "Rename", which offers the name only when core-protocol's rule for that field takes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameSheet(
    deviceName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    var text by rememberSaveable(deviceName) { mutableStateOf(deviceName) }
    val name = text.trim()
    val takes = name.isNotEmpty() && deviceNameRefusal(name) == null
    // Material draws a sheet's scrim at 32 % of the role, not the canon's; the design passes its own.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRing(FermixShapes.card),
                label = { Text(text = stringResource(R.string.onboarding_rename)) },
                isError = !takes,
                singleLine = true,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.ink,
                        focusedLabelColor = colors.ink,
                        cursorColor = colors.ink,
                    ),
            )
            PrimaryAction(
                text = stringResource(R.string.onboarding_rename),
                onClick = { onRename(name) },
                enabled = takes,
            )
        }
    }
}
