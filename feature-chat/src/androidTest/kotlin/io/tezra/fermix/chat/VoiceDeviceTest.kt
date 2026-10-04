package io.tezra.fermix.chat

import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The steps a slide of the held mic is made in, as a finger moves. */
private const val SLIDE_STEPS = 12

/** How far short of or past a threshold a test's slide stops. */
private val MARGIN = 12.dp

/** [distance] in the rule's pixels. */
internal fun AndroidComposeTestRule<*, ChatTestActivity>.px(distance: Dp): Float = with(density) { distance.toPx() }

/** The mic held, slid by [by] in [SLIDE_STEPS] steps, and let go. */
internal fun AndroidComposeTestRule<*, ChatTestActivity>.slideMic(by: Offset) {
    onNodeWithContentDescription(activity.getString(R.string.chat_record)).performTouchInput {
        down(center)
        repeat(SLIDE_STEPS) { moveBy(by / SLIDE_STEPS.toFloat()) }
        up()
    }
    waitForIdle()
}

/**
 * The mic's hold on a device, over the rig's fake microphone (design sections 8.5 and 13.6): slid left past
 * 120 dp it cancels, and nothing goes; short of it, let go, the note goes as one `msg` with its `audio`; slid up it
 * locks hands-free and records on, with pause, stop and send in row 2. A take the phone stopped while the finger
 * still held it, or a hold the phone took from the finger, stays a draft: nothing goes without a tap. The first
 * hold without the microphone shows the rationale before the system's prompt, and a refusal says the microphone is
 * off, with "Open settings".
 */
class VoiceDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    @Test
    fun a_hold_slid_left_past_120_dp_cancels_the_take() {
        rule.slideMic(Offset(-rule.px(CANCEL_SLIDE + MARGIN), 0f))
        val recorder = rule.activity.rig.recorder
        assertEquals(1, recorder.starts.value)
        assertEquals(1, recorder.stops.value)
        assertEquals(VoiceUi.Idle, rule.activity.model.voice.ui.value)
        rule.waitForIdle()
        assertTrue(
            "nothing goes",
            rule.activity.rig.session.sent.value
                .isEmpty(),
        )
    }

    @Test
    fun a_hold_slid_left_short_of_120_dp_and_let_go_sends_the_note() {
        rule.slideMic(Offset(-rule.px(CANCEL_SLIDE - MARGIN), 0f))
        val session = rule.activity.rig.session
        rule.waitUntil("the note goes", STEP_MILLIS) { session.sent.value.isNotEmpty() }
        val sent = session.sent.value.single() as ClientEvent.Msg
        assertEquals(1, sent.attachIds.size)
        val item =
            rule.activity.rig.store.outbox.value
                .single()
        assertEquals(listOf(AttachKind.AUDIO), item.attachments.map { it.kind })
        assertEquals(VoiceUi.Idle, rule.activity.model.voice.ui.value)
    }

    @Test
    fun a_hold_slid_up_locks_it_hands_free() {
        rule.slideMic(Offset(0f, -rule.px(LOCK_SLIDE + MARGIN)))
        val shown = rule.activity.model.voice.ui.value
        assertTrue("locked: $shown", shown is VoiceUi.Recording && shown.locked)
        assertEquals(0, rule.activity.rig.recorder.stops.value)
        for (control in listOf(R.string.chat_pause_recording, R.string.chat_stop_recording, R.string.chat_send)) {
            rule.onNodeWithContentDescription(rule.activity.getString(control)).assertExists()
        }
    }

    @Test
    fun a_take_the_phone_stopped_while_held_stays_a_draft_when_let_go() {
        val recorder = rule.activity.rig.recorder
        val mic = rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_record))
        mic.performTouchInput {
            down(center)
            moveBy(Offset(-rule.px(MARGIN), 0f))
        }
        rule.waitForIdle()
        rule.runOnUiThread { recorder.loseFocus() }
        // The draft took the mic's place under the finger, so the lift goes to the window the hold began in.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        val shown = rule.activity.model.voice.ui.value
        assertTrue("a draft: $shown", shown is VoiceUi.Draft)
        assertTrue(
            "a recording was sent on a lost audio focus",
            rule.activity.rig.session.sent.value
                .isEmpty(),
        )
        rule.onNodeWithText(rule.activity.getString(R.string.chat_recording_stopped)).assertExists()
    }

    @Test
    fun a_hold_the_phone_takes_from_the_finger_stops_into_a_draft() {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_record)).performTouchInput {
            down(center)
            moveBy(Offset(-rule.px(MARGIN), 0f))
            cancel()
        }
        rule.waitForIdle()
        val shown = rule.activity.model.voice.ui.value
        assertTrue("a draft: $shown", shown is VoiceUi.Draft)
        assertEquals(1, rule.activity.rig.recorder.stops.value)
        assertTrue(
            "nothing goes",
            rule.activity.rig.session.sent.value
                .isEmpty(),
        )
    }

    @Test
    fun the_first_hold_without_the_microphone_explains_it_then_asks_and_a_refusal_offers_settings() {
        val rig = rule.activity.rig
        rig.micAllowed = false
        rig.results.granted = false
        rule.slideMic(Offset.Zero)
        rule.onNodeWithText(rule.activity.getString(R.string.chat_mic_rationale_title)).assertExists()
        assertTrue("no prompt before the rationale: ${rig.results.launched}", rig.results.launched.isEmpty())
        assertEquals(0, rig.recorder.starts.value)
        rule.onNodeWithText(rule.activity.getString(R.string.chat_continue)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("RequestPermission"), rig.results.launched.toList())
        rule.onNodeWithText(rule.activity.getString(R.string.chat_mic_off)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_open_settings)).performClick()
        rule.waitForIdle()
        val settings = rig.started.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${rule.activity.packageName}", settings.data.toString())
        assertEquals(0, rig.recorder.starts.value)
    }
}
