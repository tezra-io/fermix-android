package io.tezra.fermix.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The paste sheet's primary clip on the device's own ClipboardManager, which lets an app read and clear the
 * clipboard only while one of its windows has the focus: an activity is shown for that, and has it.
 */
class SystemClipTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_clip_reads_the_primary_clips_text_and_clearing_it_leaves_none() {
        val activity = rule.activity
        rule.awaitWindowFocus(activity)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        rule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("pairing link", linkText())) }
        val clip = clipboardClip(activity)
        assertEquals(linkText(), rule.runOnUiThread { clip.text() })
        rule.runOnUiThread { clip.clear() }
        assertFalse("the link is off the clipboard", clipboard.hasPrimaryClip())
        assertNull(rule.runOnUiThread { clip.text() })
    }
}
