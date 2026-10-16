package io.tezra.fermix.instance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.transport.Candidate

// The Connection card's routes to the daemon (Advanced): the canon's `.cand` lines, 4 dp apart under 2 dp, over
// 6 dp, with 8 dp dots, each host with its port and scope tag, and then the daemon's iroh route (M62).
private val CANDIDATE_GAP = 4.dp
private val CANDIDATE_TOP = 2.dp
private val CANDIDATE_BOTTOM = 6.dp
private val CANDIDATE_DOT = 8.dp
private val CANDIDATE_INSET = 8.dp

/** The route's words, "From anywhere", on a candidate's line height. */
private val ROUTE_STYLE = FermixType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp)

/**
 * The record's hosts, each with its port, its scope and whether it is [reached] (reachableCandidates), then its
 * [route], when it has one, as "From anywhere" over its relays (M62), [sides] in from the page's or a card's edges.
 */
@Composable
internal fun Candidates(
    hosts: List<Candidate.Host>,
    route: Candidate.Route?,
    port: Int,
    sides: Dp,
    reached: (Candidate) -> Boolean,
) {
    Column(
        modifier = Modifier.padding(start = sides, end = sides, top = CANDIDATE_TOP, bottom = CANDIDATE_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(CANDIDATE_GAP),
    ) {
        for (host in hosts) CandidateLine(host, port, reached(host))
        route?.let { RouteLine(it, reached(it)) }
    }
}

@Composable
private fun CandidateLine(
    candidate: Candidate.Host,
    port: Int,
    reached: Boolean,
) {
    val colors = LocalFermixColors.current
    val scope =
        when (candidate.scope) {
            Candidate.Scope.LAN -> R.string.instance_scope_lan
            Candidate.Scope.TAILNET -> R.string.instance_scope_tailnet
            Candidate.Scope.REMOTE -> error("a host is never remote; the route is")
        }
    // The dot stands apart, on the middle of the address's line, and the address and its tag flow beside it:
    // at a large font the scope tag drops below the address, under it rather than under the dot, and never
    // splits it.
    Row(horizontalArrangement = Arrangement.spacedBy(CANDIDATE_INSET)) {
        CandidateDot(reached, CANDIDATE_STYLE)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CANDIDATE_INSET),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.instance_candidate, candidate.host, port),
                style = CANDIDATE_STYLE,
                color = colors.textSecondary,
            )
            Tag(stringResource(scope), TAG_STYLE, ends = 0.dp)
        }
    }
}

/** The route's row: "From anywhere", and under it the relays' hosts it is dialled through; never its node id. */
@Composable
private fun RouteLine(
    route: Candidate.Route,
    reached: Boolean,
) {
    val colors = LocalFermixColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(CANDIDATE_INSET)) {
        CandidateDot(reached, ROUTE_STYLE)
        Column {
            Text(text = stringResource(R.string.instance_route), style = ROUTE_STYLE, color = colors.textSecondary)
            Text(
                text = route.relays.joinToString(", ") { it.host },
                style = CANDIDATE_STYLE,
                color = colors.textSecondary,
            )
        }
    }
}

/** A candidate's dot, on the middle of the first line of [style]: ok while [reached]. */
@Composable
private fun CandidateDot(
    reached: Boolean,
    style: TextStyle,
) {
    val colors = LocalFermixColors.current
    val firstLine = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(modifier = Modifier.height(firstLine), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(CANDIDATE_DOT)
                    .background(if (reached) colors.ok else colors.inkTertiary, CircleShape),
        )
    }
}
