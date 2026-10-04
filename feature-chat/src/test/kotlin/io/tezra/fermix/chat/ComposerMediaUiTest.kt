package io.tezra.fermix.chat

import android.content.ClipDescription
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's media in the owner's hand (design sections 8.5, 13.1 and 13.6): an image the keyboard commits
 * (IME `commitContent`) goes to the tray, the field keeping its words; the tray's ✕ is a 48 dp target around its
 * 18 dp badge; the screen leaving the foreground stops a recording into a draft, and a rotation leaves it going.
 * JUnit 4, in Roborazzi's activity, on the compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ComposerMediaUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun backgroundEnds() = background.cancel()

    /**
     * The chat's parts over [store], its file work ([ChatParts.io]) on the main looper: Compose's idle drains that
     * looper and knows no other thread, so a state landed after a hop to `Dispatchers.IO` (a keyboard's copy, the
     * voice draft's file found as the chat opens) would race the assertion that follows the idle.
     */
    private fun partsOf(
        store: FakeChatStore,
        session: FakeChatSession = FakeChatSession(store),
    ): ChatParts = fakeParts(sample(), session, store, background).copy(io = Dispatchers.Main)

    /** The view the focused field takes the keyboard's input through, the first in [root]'s tree. */
    private fun editor(root: View): View? =
        when {
            root.onCheckIsTextEditor() -> root
            root is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { editor(root.getChildAt(it)) }
            else -> null
        }

    @Test
    fun `an image the keyboard commits goes to the tray, and the field keeps its words`() {
        val parts = partsOf(FakeChatStore())
        // Robolectric's ImageDecoder decodes no file, so the copy is described as one that draws no thumbnail.
        (parts.media as FakePipeline).describe = { uri, from ->
            Picked(uri, uri, PickedKind.FILE, "image/png", uri.substringAfterLast('/'), 1_000L, from)
        }
        val model = ChatViewModel(parts)
        rule.setContent {
            FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
        }
        val placeholder = rule.activity.getString(R.string.chat_placeholder, sample().title)
        rule.onNodeWithContentDescription(placeholder).performTextInput("look at this")
        val view = checkNotNull(editor(rule.activity.window.decorView)) { "no view takes the keyboard's input" }
        val info = EditorInfo()
        val connection = rule.runOnIdle { checkNotNull(view.onCreateInputConnection(info)) }
        val png = ClipDescription("sticker", arrayOf("image/png"))
        val sticker = InputContentInfo(Uri.parse("content://ime/sticker.png"), png)
        rule.runOnIdle { assertTrue("the field took the content", connection.commitContent(sticker, 0, null)) }
        rule.waitForIdle()
        val picked = model.attach.ui.value.picked
        assertEquals(listOf("sticker.png" to PickedFrom.KEYBOARD), picked.map { it.name to it.from })
        assertTrue("the keyboard's item is the chat's own copy", picked.single().uri.startsWith("file:"))
        assertEquals("look at this", model.composer.field.value.text)
    }

    /** The chat's screen over [model], recording once it is idle. */
    private fun recordingOn(model: ChatViewModel) {
        rule.setContent {
            FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
        }
        rule.runOnIdle { model.voice.start() }
        assertTrue("the take started: ${model.voice.ui.value}", model.voice.ui.value is VoiceUi.Recording)
    }

    @Test
    fun `leaving the foreground stops a recording into a draft and sends nothing`() {
        val store = FakeChatStore()
        val session = FakeChatSession(store)
        val model = ChatViewModel(partsOf(store, session))
        recordingOn(model)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("leaving kept no draft", model.voice.ui.value is VoiceUi.Draft)
        assertTrue(session.sent.value.isEmpty())
    }

    @Test
    fun `a rotation leaves the recording going`() {
        val model = ChatViewModel(partsOf(FakeChatStore()))
        recordingOn(model)
        rule.activityRule.scenario.recreate()
        assertTrue("a rotation stopped the take", model.voice.ui.value is VoiceUi.Recording)
    }

    @Test
    fun `the tray's remove is a 48 dp target`() {
        val item =
            Picked("p1", "content://media/1", PickedKind.IMAGE, "image/jpeg", "IMG_1.jpg", 1_000, PickedFrom.PHOTOS)
        val removed = mutableListOf<String>()
        rule.setContent { FermixTheme { Tray(listOf(item), { null }, { removed += it }) } }
        val remove = rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_remove_item, "IMG_1.jpg"))
        remove.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        remove.performClick()
        assertEquals(listOf("p1"), removed)
    }
}
