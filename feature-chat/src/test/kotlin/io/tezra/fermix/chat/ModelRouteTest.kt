package io.tezra.fermix.chat

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The model switcher through the screen (design section 8.6): the chip opens the "Model" sheet, which pulls the
 * models, and a pick sends `command{model}`; `/model` on the palette, or typed alone, opens the same sheet; and
 * with no connection the chip takes no press, nor does the palette's `/model` open it. JUnit 4, in Roborazzi's
 * activity, on the compact window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ModelRouteTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun backgroundEnds() = background.cancel()

    private fun show(
        store: FakeChatStore,
        session: FakeChatSession,
    ): ChatViewModel {
        val model = ChatViewModel(fakeParts(withModel(), session, store, background), SavedStateHandle())
        rule.setContent {
            FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
        }
        rule.waitForIdle()
        return model
    }

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    @Test
    fun `the chip opens the sheet, and a pick sends the model command`() {
        val store = FakeChatStore()
        val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
        show(store, session)
        rule.onNodeWithContentDescription(string(R.string.chat_model_chip, "GPT-6 Astra")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.chat_model_title)).assertExists()
        rule.onNodeWithText(string(R.string.chat_model_default, "GPT-6 Astra")).assertExists()
        rule.onNodeWithText(string(R.string.chat_model_trait, "GPT-6 Luna", "fast, cheaper")).performClick()
        rule.waitForIdle()
        assertEquals(1, session.pulls.value)
        assertEquals(
            listOf<ClientEvent>(ClientEvent.Command("${MODEL_PICK_PREFIX}m1", PROFILE, "model", "codex/gpt-6-luna")),
            session.sent.value,
        )
        rule.onNodeWithText(string(R.string.chat_model_title)).assertDoesNotExist()
    }

    @Test
    fun `slash model on the palette opens the same sheet`() {
        val store = FakeChatStore()
        val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
        val model = show(store, session)
        model.composer.openPalette()
        rule.waitForIdle()
        rule.onNodeWithText("/model").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.chat_model_title)).assertExists()
        assertEquals(1, session.pulls.value)
        assertEquals("", model.composer.field.value.text)
    }

    @Test
    fun `with no connection the chip takes no press, and the line above the composer says why`() {
        val store = FakeChatStore()
        show(store, FakeChatSession(store, SessionState.Connecting))
        rule.onNodeWithContentDescription(string(R.string.chat_model_chip, "GPT-6 Astra")).assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.chat_connect_to_change_model)).assertExists()
    }

    @Test
    fun `with no connection slash model on the palette opens no sheet, as the chip does not`() {
        val store = FakeChatStore()
        val session = FakeChatSession(store, SessionState.Connecting)
        val model = show(store, session)
        model.composer.openPalette()
        rule.waitForIdle()
        rule.onNodeWithText("/model").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.chat_model_title)).assertDoesNotExist()
        assertEquals(null, model.models.sheet.value)
        assertEquals(0, session.pulls.value)
    }

    @Test
    fun `slash model typed alone opens the sheet and sends nothing, and with words it goes as the command`() {
        val store = FakeChatStore()
        val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
        val model = show(store, session)
        model.composer.edit(TextFieldValue("/model"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription(string(R.string.chat_send)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.chat_model_title)).assertExists()
        assertEquals(emptyList<ClientEvent>(), session.sent.value)
        assertEquals("", model.composer.field.value.text)
        model.models.close()
        model.composer.edit(TextFieldValue("/model reset"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription(string(R.string.chat_send)).performClick()
        rule.waitForIdle()
        assertEquals(listOf<ClientEvent>(ClientEvent.Command("m1", PROFILE, "model", "reset")), session.sent.value)
    }
}
