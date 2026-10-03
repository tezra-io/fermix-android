package io.tezra.fermix.chats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.InstanceActions
import io.tezra.fermix.instance.InstanceScreen
import io.tezra.fermix.instance.InstanceUi
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.TestState
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Which screen the host shows. */
internal enum class Shown { CHATS, INSTANCE }

/** Two Fermixes on the Chats list: production over Tailscale, and the dev daemon connecting. */
internal val TWO_ROWS =
    ChatsUi(
        rows =
            listOf(
                ChatRow(
                    record = sample(1),
                    profileId = "main",
                    agentName = null,
                    dev = false,
                    link = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true),
                    line = RowLine.Message("Raised the export timeout."),
                    time = "09:41",
                    unread = 2,
                ),
                ChatRow(
                    record = sample(2, profile = "fermix-dev", tint = "Ocean", nickname = "Dev"),
                    profileId = "main",
                    agentName = null,
                    dev = true,
                    link = Link.Connecting,
                    line = RowLine.Empty,
                    time = null,
                    unread = 0,
                ),
            ),
        repairs = emptyList(),
    )

/** The first Fermix's Instance screen, connected. */
internal val INSTANCE =
    InstanceUi(
        record = sample(1),
        others = listOf(sample(2, nickname = "Dev")),
        link = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true),
        diagnostics = emptyList(),
        reachable = sample(1).candidates.toSet(),
        previews = true,
        cacheBytes = 0,
        test = TestState.Idle,
        releaseBuild = false,
    )

/** What the tests set and count, kept across the activity's recreation as the app's ViewModels are. */
internal class ChatsTestRig : ViewModel() {
    /** How many times the activity was made: a rotation or a fold that recreated it adds one. */
    val creations = AtomicInteger()
    val shown = MutableStateFlow(Shown.CHATS)
}

/** The Chats list or the Instance screen over fixed state, in the design's theme, as the app shows them. */
class ChatsTestActivity : ComponentActivity() {
    internal val rig: ChatsTestRig by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rig.creations.incrementAndGet()
        setContent { FermixTheme { Host(rig) } }
    }
}

private val NO_CHATS_ACTIONS = ChatsActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
private val NO_INSTANCE_ACTIONS = InstanceActions({}, {}, {}, {}, {}, {}, {})

@Composable
private fun Host(rig: ChatsTestRig) {
    val shown by rig.shown.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(LocalFermixColors.current.canvas)) {
        when (shown) {
            Shown.CHATS -> ChatsScreen(TWO_ROWS, NO_CHATS_ACTIONS)
            Shown.INSTANCE -> InstanceScreen(INSTANCE, NO_INSTANCE_ACTIONS)
        }
    }
}
