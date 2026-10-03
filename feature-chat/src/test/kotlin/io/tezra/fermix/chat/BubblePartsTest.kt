package io.tezra.fermix.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.Sender
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** How long a test waits for the renderer's streaming state to take a text. */
private const val PARSED_MS = 5_000L

/**
 * An answer's parts as the bubble draws them (design sections 8.3, 13.1 and 13.10, item 2): a fence that closes
 * or a table whose delimiter row lands while the answer streams leaves no "code…" chip and no raw header line
 * above its card, and an answer made only of a card still shows its time, and a job's delivery its tag.
 * JUnit 4, in Roborazzi's activity, on the compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class BubblePartsTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val context =
        TimelineContext(
            zone = UTC,
            locale = Locale.US,
            today = MORNING.atZone(UTC).toLocalDate(),
            host = HOST,
            model = "",
            nowMono = { 0L },
            text = TextActions({}, {}),
            selected = emptySet(),
            menuFor = null,
            onTap = {},
            onLongPress = {},
            onError = {},
            onOutbox = { _, _ -> },
        )

    private var shown by mutableStateOf(ShownMessage(Sender.Agent, "", null, Delivery.NONE))

    private fun show(message: ShownMessage) {
        shown = message
        rule.setContent { FermixTheme { MessageItem(ChatItem.Message("r1", shown), context) } }
    }

    private fun streaming(text: String) = ShownMessage(Sender.Agent, text, null, Delivery.NONE, streaming = true)

    private fun sealed(
        text: String,
        job: String? = null,
    ) = ShownMessage(Sender.Agent, text, wallAt(1), Delivery.NONE, job = job)

    private fun awaitText(
        text: String,
        substring: Boolean = false,
    ) {
        rule.waitUntil(PARSED_MS) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a fence that closes mid-stream leaves no code placeholder above its card`() {
        val placeholder = rule.activity.getString(R.string.chat_code_placeholder)
        show(streaming("Intro words.\n\n```kotlin\nval a = 1\n"))
        awaitText(placeholder)
        shown = streaming("Intro words.\n\n```kotlin\nval a = 1\n```\n")
        awaitText("val a = 1", substring = true)
        rule.waitForIdle()
        rule.onAllNodesWithText(placeholder).assertCountEquals(0)
        rule.onNodeWithText("Intro words.", substring = true).assertExists()
    }

    @Test
    fun `a table whose delimiter row lands mid-stream leaves no raw header line above its card`() {
        show(streaming("Intro words.\n\n| Job | Took |\n"))
        awaitText("| Job | Took |", substring = true)
        shown = streaming("Intro words.\n\n| Job | Took |\n|---|---|\n| export | 60 s |\n")
        awaitText("export")
        rule.waitForIdle()
        rule.onAllNodesWithText("| Job | Took |", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Intro words.", substring = true).assertExists()
    }

    @Test
    fun `an answer that is only a fence shows its time`() {
        show(sealed("```sh\nls -la\n```"))
        awaitText("ls -la", substring = true)
        rule.onNodeWithText(timeOf(wallAt(1), context)).assertExists()
    }

    @Test
    fun `a job's delivery that is only a table wears its tag and shows its time`() {
        show(sealed("| Job | Took |\n|---|---|\n| export | 60 s |\n", job = "nightly-export"))
        awaitText("export")
        rule.onNodeWithText(rule.activity.getString(R.string.chat_job, "nightly-export")).assertExists()
        rule.onNodeWithText(timeOf(wallAt(1), context)).assertExists()
    }
}
