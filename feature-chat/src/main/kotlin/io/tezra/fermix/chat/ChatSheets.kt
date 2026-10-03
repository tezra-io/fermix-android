package io.tezra.fermix.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.transport.Candidate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Milliseconds in a second, for Info's whole seconds. */
private const val SECOND_MILLIS = 1_000L

/**
 * Info on a message (design section 13.7), as a sheet: sent and delivered, `server_seq` and `client_msg_id`;
 * then its turn, when this process saw it: the duration, "Thought for 12 s · 3 tools", the model and the path;
 * then each tool with its status. Thought text is never here: it is never kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheet(
    info: ShownInfo,
    context: TimelineContext,
    modelOf: (Route) -> String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalFermixColors.current.tonalSolid) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            info.sentWall?.let { Fact(stringResource(R.string.chat_info_sent), stampOf(it, context)) }
            info.deliveredWall?.let { Fact(stringResource(R.string.chat_info_delivered), stampOf(it, context)) }
            info.serverSeq?.let { Fact(stringResource(R.string.chat_info_seq), "$it", mono = true) }
            info.clientMsgId?.let { Fact(stringResource(R.string.chat_info_client_msg_id), it, mono = true) }
            info.route?.let { Fact(stringResource(R.string.chat_info_model), modelOf(it)) }
            info.turn?.let { TurnFacts(it) }
        }
    }
}

@Composable
private fun TurnFacts(turn: TurnInfo) {
    Section(stringResource(R.string.chat_info_turn))
    turn.durationMs?.let {
        Fact(
            stringResource(R.string.chat_info_duration),
            stringResource(
                R.string.chat_info_seconds,
                it / SECOND_MILLIS,
            ),
        )
    }
    val seconds = (turn.thoughtMs / SECOND_MILLIS).toInt()
    Fact(pluralStringResource(R.plurals.chat_info_thought, turn.tools.size, seconds, turn.tools.size), null)
    turn.path?.let { Fact(stringResource(R.string.chat_info_path), pathOf(it)) }
    if (turn.tools.isEmpty()) return
    Section(stringResource(R.string.chat_info_tools))
    turn.tools.forEach { tool ->
        val status = stringResource(if (tool.running) R.string.chat_info_started else R.string.chat_info_finished)
        Fact(verbWords(toolVerb(tool.tool)), status)
    }
}

@Composable
private fun pathOf(scope: Candidate.Scope): String =
    when (scope) {
        Candidate.Scope.LAN -> stringResource(R.string.chat_path_lan)
        Candidate.Scope.TAILNET -> stringResource(R.string.chat_path_tailnet)
    }

/** A time to the second in the owner's own form, as Info shows it. */
private fun stampOf(
    wallMs: Long,
    context: TimelineContext,
): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(context.locale)
        .format(Instant.ofEpochMilli(wallMs).atZone(context.zone))

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = FermixType.labelSmall,
        color = LocalFermixColors.current.inkSecondary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp),
    )
}

/** One of Info's rows (the canon's `.kv`): its name, and its value in the second ink, mono for an id. */
@Composable
private fun Fact(
    name: String,
    value: String?,
    mono: Boolean = false,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = FermixType.body, color = colors.ink, modifier = Modifier.weight(1f))
        value?.let {
            val style =
                if (mono) {
                    FermixType.mono.copy(
                        color = colors.inkSecondary,
                    )
                } else {
                    FermixType.bodyMedium.copy(color = colors.inkSecondary)
                }
            Text(it, style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** "Select text" (design section 13.7): the message's words in a sheet, selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectTextSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalFermixColors.current.tonalSolid) {
        SelectionContainer(
            modifier =
                Modifier
                    .verticalScroll(
                        rememberScrollState(),
                    ).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(text, style = FermixType.body, color = LocalFermixColors.current.ink)
        }
    }
}

/** How many messages are selected, as the selection bar says it. */
@Composable
internal fun selectedWords(count: Int): String = pluralStringResource(R.plurals.chat_selected, count, count)
