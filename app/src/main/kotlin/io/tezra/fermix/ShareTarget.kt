package io.tezra.fermix

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.core.net.toUri
import io.tezra.fermix.chat.ownsProvider

/**
 * The share entry (design section 13.6, "Share into Fermix"), the one exported way into the app that takes another
 * app's share, and nothing but a share: an activity with no window, in the task of the app that shared, that reads the
 * share (shareOf), hands it to the app in the process (AppServices.shares), brings the app's one activity forward in
 * the app's own task (shareForward), and finishes. Nothing of the share's intent goes on: no extra, no data, no
 * intent it names. A first forward carries no grant: the intent that starts a task stays the task's own, which Recents
 * starts again once the activity is gone, and a start that names a grant the app no longer holds fails, which takes
 * the task out of Recents. The read grant each URI came with is this entry's, and would end as it finishes, so a
 * second forward names the URIs it holds a grant to in its ClipData, with that grant, which the activity's record
 * holds from then on until it is destroyed: each item is read and copied as it lands, after the lock and the pick. A
 * URI the app reads with no grant, a media-store row it saved, needs none handed on.
 */
class ShareTarget : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = shareOf(intent) { ownsProvider(this, it) }
        if (share != null) handOver(share)
        finish()
    }

    /**
     * [share] to the app, and the activity forward (shareForwards), first with the launcher's own intent alone, which a
     * task it starts keeps, then with the grants this entry holds to the share's URIs.
     */
    private fun handOver(share: Share) {
        val shares = (application as FermixApplication).services.shares
        if (shares.value != null) logShare("A share came while another waited to be taken; the newer one is kept")
        shares.value = share
        val granted =
            share.shared.uris
                .map { it.toUri() }
                .filter(::grantedToRead)
        shareForwards(this, granted).forEach(::forward)
    }

    /**
     * [intent] started; one the platform refuses, a grant it will not let this entry hand on, is logged, and the
     * share's items then logged as they fail to land.
     */
    private fun forward(intent: Intent) {
        try {
            startActivity(intent)
        } catch (refused: SecurityException) {
            logShare("A share's forward was refused, its read grants not handed on: ${refused.javaClass.simpleName}")
        }
    }

    /** Whether the app holds a grant to read [uri], which the share's intent gave this entry. */
    private fun grantedToRead(uri: Uri): Boolean =
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * The intent that brings the app's one activity forward with a share (ShareTarget): the launcher's own, MAIN and
 * LAUNCHER, so a task it starts is one the owner leaves with Back as from the launcher; into the app's own task and
 * onto the activity there, what the app had over it cleared, the activity taking it as a new intent (NEW_TASK,
 * CLEAR_TOP and SINGLE_TOP). The share's URIs the entry holds a read grant to, [granted], go in its ClipData with
 * FLAG_GRANT_READ_URI_PERMISSION, so the grant is the activity's from then on, until it is destroyed; the entry sends
 * one with none granted first, which a task it starts keeps as its own (shareForwards). It carries no extra and no
 * data: the share itself is handed over in the process (AppServices.shares).
 */
internal fun shareForward(
    context: Context,
    granted: List<Uri>,
): Intent {
    val forward =
        Intent
            .makeMainActivity(ComponentName(context, MainActivity::class.java))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    if (granted.isEmpty()) return forward
    val clip = ClipData.newRawUri(null, granted.first())
    granted.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    forward.clipData = clip
    return forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * What the share entry starts, in order (ShareTarget): the forward with no grant first, which starts the app's task or
 * brings it forward, then, when the entry holds read grants to [granted], the forward with them, which the activity
 * takes as a new intent. The intent that starts a task stays the task's own, which Recents starts again once the
 * activity is gone, and a start that names a grant the app no longer holds by then fails, which takes the task out
 * of Recents: so no intent that carries a grant ever starts the task.
 */
internal fun shareForwards(
    context: Context,
    granted: List<Uri>,
): List<Intent> {
    val plain = shareForward(context, emptyList())
    return if (granted.isEmpty()) listOf(plain) else listOf(plain, shareForward(context, granted))
}
