package io.tezra.fermix.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.session.UploadStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The pasted image the upload sends. */
private const val PASTED = "content://clip/rack.png"

/** How much of it is up when the window changes: the ring at 40 %, in fifths. */
private const val SENT_FIFTHS = 2L
private const val FIFTHS = 5L

/** A level the fake microphone reports while the take records: loud enough to stand above the quiet line. */
private const val LOUD = 20_000

/** The samples the take is given before it is paused, two bars' worth of them at most. */
private const val LOUD_SAMPLES = 12

/**
 * A rotation and a fold on a device (design section 13.11, rule 3) while a voice note records locked and an
 * image goes up: the activity is made again, and the recording goes on, never stopped, its waveform with the bars
 * it had and its timer where it stood (the take is paused for the change, so that nothing moves meanwhile), then
 * resumed; and the upload with it, never sent again, its ring where it stood. A rotation while the mic is still
 * held locks the take, which goes on hands-free in the new window. The folds run on a foldable, as
 * WindowChangeTest's do.
 */
class MediaWindowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    /** An image sent from the tray, its upload 40 % in; its `msg`'s id, and the share its ring shows. */
    private fun uploading(): Pair<String, Float> {
        rule.runOnUiThread {
            rule.activity.model.attach
                .add(listOf(PASTED), PickedFrom.PASTE)
        }
        rule.waitUntil("the tray holds it", STEP_MILLIS) {
            rule.activity.model.media.value.attach.picked
                .isNotEmpty()
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_send)).performClick()
        val rig = rule.activity.rig
        rule.waitUntil("the item is in the outbox", STEP_MILLIS) {
            rig.store.outbox.value
                .isNotEmpty()
        }
        val item =
            rig.store.outbox.value
                .single()
        val attachment = item.attachments.single()
        val sent = attachment.sizeBytes * SENT_FIFTHS / FIFTHS
        val progress =
            UploadProgress(item.clientMsgId, attachment.attachId, sent, attachment.sizeBytes, UploadStage.UPLOADING)
        rig.session.uploads.value = mapOf(attachment.attachId to progress)
        val share = sent.toFloat() / attachment.sizeBytes
        rule.waitUntil("its ring shows", STEP_MILLIS) { ringOf(item.clientMsgId) == share }
        return item.clientMsgId to share
    }

    /** The share the ring of [clientMsgId]'s image shows; none while it shows no ring. */
    private fun ringOf(clientMsgId: String): Float? =
        rule.activity.model.state.value
            ?.items
            ?.filterIsInstance<ChatItem.Message>()
            ?.find { it.key == outboxKey(clientMsgId) }
            ?.message
            ?.media
            ?.singleOrNull()
            ?.sent

    private fun recording(): VoiceUi.Recording {
        val shown = rule.activity.model.voice.ui.value
        assertTrue("recording: $shown", shown is VoiceUi.Recording)
        return shown as VoiceUi.Recording
    }

    /** A take locked hands-free, recorded until its waveform shows the loud samples, then paused: as it stands. */
    private fun recordingLockedAndPaused(): VoiceUi.Recording {
        rule.activity.rig.recorder.amplitudes = ArrayDeque(List(LOUD_SAMPLES) { LOUD })
        rule.slideMic(Offset(0f, -rule.px(LOCK_SLIDE + 12.dp)))
        assertTrue("locked", recording().locked)
        rule.waitUntil("the waveform shows the take", STEP_MILLIS) { recording().bars.count { it > QUIET_LEVEL } > 1 }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_pause_recording)).performClick()
        rule.waitUntil("the take pauses", STEP_MILLIS) { recording().paused }
        return recording()
    }

    private fun assertBothGoOn(
        upload: Pair<String, Float>,
        take: VoiceUi.Recording,
    ) {
        val rig = rule.activity.rig
        assertEquals("the same take, its bars and its timer", take, recording())
        assertEquals(1, rig.recorder.starts.value)
        assertEquals(0, rig.recorder.stops.value)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_stop_recording)).assertExists()
        assertEquals(1, rig.session.sent.value.size)
        assertEquals(upload.second, ringOf(upload.first))
    }

    /** After the change, the take resumes where it stood. */
    private fun resumes() {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_resume_recording)).performClick()
        rule.waitUntil("the take records again", STEP_MILLIS) { !recording().paused }
        assertEquals(1, rule.activity.rig.recorder.resumes.value)
        assertEquals(0, rule.activity.rig.recorder.stops.value)
    }

    @Test
    fun a_rotation_keeps_the_recording_and_the_upload() {
        val sent = uploading()
        val take = recordingLockedAndPaused()
        rule.rotated {
            assertBothGoOn(sent, take)
            resumes()
        }
    }

    @Test
    fun a_rotation_mid_hold_keeps_the_take_going_hands_free() {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_record)).performTouchInput {
            down(center)
        }
        rule.waitUntil("the hold records", STEP_MILLIS) { rule.activity.model.voice.ui.value is VoiceUi.Recording }
        rule.rotated {
            assertTrue("the hold the window took is locked", recording().locked)
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_pause_recording)).assertExists()
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_stop_recording)).assertExists()
            assertEquals(0, rule.activity.rig.recorder.stops.value)
            assertTrue(
                "nothing goes",
                rule.activity.rig.session.sent.value
                    .isEmpty(),
            )
        }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_recording_and_the_upload() {
        val sent = uploading()
        val take = recordingLockedAndPaused()
        rule.folded {
            assertBothGoOn(sent, take)
            resumes()
        }
    }
}
