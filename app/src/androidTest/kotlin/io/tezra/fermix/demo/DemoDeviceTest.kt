package io.tezra.fermix.demo

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.STEP_MILLIS
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.data.Instance
import io.tezra.fermix.focusedWindow
import io.tezra.fermix.liveActivities
import io.tezra.fermix.resumedActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long after the phone's `pair_request` the demo's computer approves: DemoTimes.approve, 7.5 s, about 6 s of
 * them on Verify, which shows once the Connecting screen has paced "Securing the line…".
 */
private const val APPROVE_MILLIS = 7_500L

/**
 * The least time Verify must show for: the approval came after the owner saw the code, not as it was composed. About
 * 6 s on the emulators; the second left is a slow emulator's lag between the request and the code on screen.
 */
private const val VERIFY_SHOWN_MILLIS = 5_000L

/** How long a demo reply takes at most, from the card's thinking to its last word, with room for a slow emulator. */
private const val REPLY_MILLIS = 30_000L

/** A distinctive line of each answer a demo reply may give (DemoReplies), one of which the seed picks. */
private val ANSWERS =
    listOf(
        "Nothing else needs you.",
        "It prints only today",
        "The upload is the slow part.",
        "keep that in mind for the next run",
        "the export now passes 120 s.",
    )

/**
 * The debug app's demo on the device, the owner's diagonal (README, "The demo"): the "Fermix demo" entry copies a demo
 * link, and the app pairs over it as over a real one, Welcome, paste, Connecting, Verify for as long as the owner
 * reads the code, the computer's approval, Paired, the Chats list, the chat, a message sent and its reply streamed in.
 * The pairing is removed again as "Remove this Fermix" removes one, its record, its files and its key; the app must
 * hold no record before.
 */
@RunWith(AndroidJUnit4::class)
class DemoDeviceTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as DemoApplication
    private val services get() = app.services

    @Before
    fun nothingPaired() {
        assertEquals("the demo's test needs an app with no Fermix paired", emptyList<Instance>(), records())
    }

    @After
    fun removeTheDemo() {
        val paired = records()
        runBlocking { paired.forEach { services.chatsParts().unpair(it.id) } }
        val live = liveActivities()
        instrumentation.runOnMainSync { live.forEach { it.finishAndRemoveTask() } }
        assertEquals(emptyList<Instance>(), records())
        paired.forEach { record ->
            assertFalse("${record.keyAlias} is deleted with its record", DeviceKeys().exists(record.keyAlias))
            assertFalse("${record.id}'s files are gone", File(app.noBackupFilesDir, "instances/${record.id}").exists())
        }
    }

    @Test
    fun theDemoPairsThroughVerifyAndTheComputersApprovalAndAnswersAMessage() {
        app.startActivity(
            Intent().setComponent(ComponentName(app, DemoEntry::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        click("Get started")
        shows("Paste a pairing link")
        awaitWindowFocus()
        click("Paste a pairing link")
        rule.waitUntil("the sheet's Paste", STEP_MILLIS) {
            rule.onAllNodes(hasContentDescription("Paste")).fetchSemanticsNodes().isNotEmpty()
        }
        // Paste reads the link and starts the pairing: a link the scan takes closes the sheet.
        rule.onNodeWithContentDescription("Paste").performClick()
        shows("Checking it's really your machine…")
        shows("Do the codes match?")
        val verifyAt = SystemClock.elapsedRealtime()
        shows("Paired with suj-mbp", APPROVE_MILLIS + STEP_MILLIS)
        val shownFor = SystemClock.elapsedRealtime() - verifyAt
        assertTrue("Verify showed for $shownFor ms before the approval", shownFor >= VERIFY_SHOWN_MILLIS)
        click("Continue")
        gone("Paired with suj-mbp")
        notNow()
        click("suj-mbp", substring = true)

        rule.waitUntil(
            "the composer",
            STEP_MILLIS,
        ) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("How did the export go?")
        rule.onNodeWithContentDescription("Send").performClick()
        shows("How did the export go?")
        rule.waitUntil(
            "the demo's reply",
            REPLY_MILLIS,
        ) { ANSWERS.any { answered(it).fetchSemanticsNodes().isNotEmpty() } }
        assertEquals(1, records().size)
    }

    /**
     * The app's activity has the window focus, which the sheet's read of the clipboard needs (AGENTS.md): on an
     * emulator just booted a system dialog can hold it, and the read is then refused. Past [STEP_MILLIS] it fails
     * with the window that has the focus.
     */
    private fun awaitWindowFocus() {
        try {
            rule.waitUntil("the app has the window focus", STEP_MILLIS) { resumedActivity()?.hasWindowFocus() == true }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("the app never had the window focus: ${focusedWindow()}", timeout)
        }
    }

    private fun records(): List<Instance> = runBlocking { services.instances.instances.first() }

    private fun answered(line: String): SemanticsNodeInteractionCollection =
        rule.onAllNodes(hasText(line, substring = true))

    /** Waits for [text] on screen. */
    private fun shows(
        text: String,
        within: Long = STEP_MILLIS,
        substring: Boolean = false,
    ) = rule.waitUntil(text, within) { rule.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    private fun click(
        text: String,
        substring: Boolean = false,
    ) {
        shows(text, substring = substring)
        rule.onAllNodesWithText(text, substring)[0].performClick()
    }

    /** Waits for [text] to leave the screen. */
    private fun gone(text: String) =
        rule.waitUntil("$text gone", STEP_MILLIS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    /** Onboarding's notifications step, when it asks: "Not now"; then the Chats list, its "Add Fermix" showing. */
    private fun notNow() {
        val chats = hasContentDescription("Add Fermix") or hasText("Add Fermix")
        rule.waitUntil("the notifications step or the Chats list", STEP_MILLIS) {
            rule.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(chats).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Not now").performClick()
            gone("Not now")
        }
        rule.waitUntil("the Chats list", STEP_MILLIS) { rule.onAllNodes(chats).fetchSemanticsNodes().isNotEmpty() }
    }
}
