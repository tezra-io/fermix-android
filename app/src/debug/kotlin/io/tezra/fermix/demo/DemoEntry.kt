package io.tezra.fermix.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.tezra.fermix.MainActivity
import io.tezra.fermix.R
import io.tezra.fermix.protocol.PairingLink
import kotlinx.coroutines.launch

/**
 * The debug app's "Fermix demo" launcher entry (README, "The demo"): it starts the demo for this process
 * (DemoApplication.startDemo), copies the pairing link of the next demo Fermix no phone paired with to the
 * clipboard, under that Fermix's name, brings the app forward as its launcher icon does, and finishes. The system
 * confirms the copy itself, with its clipboard preview, as it does every copy from Android 13 on, so the entry says
 * nothing of its own: on Android 15 that preview covered a toast. It has no window of its own, and an empty affinity
 * keeps it out of the app's task, as the share entry is kept.
 */
class DemoEntry : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demo = (application as DemoApplication).startDemo()
        lifecycleScope.launch {
            copy(demo.nextLink())
            startActivity(appForward())
            finish()
        }
    }

    /** [link] on the clipboard, labelled with the demo Fermix it pairs, as Add Fermix will name it. */
    private fun copy(link: String) {
        val parsed = PairingLink.parse(link)
        val label = getString(R.string.demo_clip_label, parsed.name, parsed.profile)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, link))
    }

    /** The app's activity as the launcher starts it: its task brought forward as it was left, or a new one. */
    private fun appForward(): Intent =
        Intent
            .makeMainActivity(ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
