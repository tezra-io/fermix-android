package io.tezra.fermix.chats

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Send to which Fermix?" (design sections 13.6 and 13.9) on Robolectric: the question as a heading over each paired
 * chat drawn as the Chats list draws its title, the DEV tag beside a dev daemon's, and a tap hands that chat on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ShareSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private fun row(
        gateway: Int,
        profile: String = "fermix",
        host: String = HOST,
    ) = ChatRow(
        record = sample(gateway, host = host, profile = profile),
        profileId = "main",
        agentName = null,
        dev = profile == "fermix-dev",
        link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
        line = RowLine.Empty,
        time = null,
        unread = 0,
    )

    @Test
    fun `the question heads a row per paired chat, and a tap hands that chat on`() {
        val rows = listOf(row(1), row(2, profile = "fermix-dev"), row(3, host = LINUX_HOST))
        val picked = mutableListOf<ChatRow>()
        rule.setContent { FermixTheme { ShareSheetContent(rows) { picked += it } } }
        rule
            .onNodeWithText("Send to which Fermix?")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(2, rule.onAllNodesWithText(HOST).fetchSemanticsNodes().size)
        rule.onNodeWithText("DEV").assertExists()
        rule.onNodeWithText(LINUX_HOST).performClick()
        assertEquals(listOf(rows[2]), picked)
    }
}
