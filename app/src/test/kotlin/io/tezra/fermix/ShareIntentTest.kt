package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chat.Shared
import io.tezra.fermix.chat.ownsProvider
import io.tezra.fermix.session.MAX_ATTACHMENTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

private const val PACKAGE = "io.tezra.fermix"

/** The chat's own FileProvider, as the app's manifest declares it. */
private const val OWN = "io.tezra.fermix.chat.files"

private fun photo(n: Int): Uri = Uri.parse("content://media/external/images/media/$n")

/** The share entry, as the chooser or a Direct Share starts it, or the activity itself. */
private val ENTRY = ComponentName(PACKAGE, SHARE_ENTRY)
private val ACTIVITY = ComponentName(PACKAGE, MainActivity::class.java.name)

private fun send(
    to: ComponentName = ENTRY,
    action: String = Intent.ACTION_SEND,
    type: String = "image/jpeg",
): Intent = Intent(action).setType(type).setComponent(to)

/**
 * The share entry's intent as it reads it (design section 13.6, "Share into Fermix"): a share and nothing else, its
 * streams, words and shortcut read as their types say, the streams weighed by mayRead with the app's own providers
 * as the package manager names them; the activity takes no share from any intent; and an intent the system replays
 * from Recents is taken for nothing, a share or the activity's own.
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {
    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    private fun read(intent: Intent): Share? = shareOf(intent) { ownsProvider(app, it) }

    @Test
    fun `the entry takes an image, a list of items and words, with the shortcut a Direct Share named`() {
        val image = send().putExtra(Intent.EXTRA_STREAM, photo(1)).putExtra(Intent.EXTRA_SHORTCUT_ID, "id:main")
        val one = Shared(listOf(photo(1).toString()), null, emptyList(), past = 0)
        assertEquals(Share(one, "id:main"), read(image))
        val many = send(action = Intent.ACTION_SEND_MULTIPLE, type = "image/*")
        many.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((1..12).map(::photo)))
        val ten = Shared((1..MAX_ATTACHMENTS).map { photo(it).toString() }, null, emptyList(), past = 2)
        assertEquals(Share(ten, null), read(many))
    }

    @Test
    fun `a share's words are its text without its links' spans, and a link in them is words`() {
        val text = SpannableString("see fermix://chat/x")
        text.setSpan(URLSpan("fermix://chat/x"), 4, text.length, 0)
        val words = send(type = "text/plain").putExtra(Intent.EXTRA_TEXT, text)
        assertEquals(Share(Shared(emptyList(), "see fermix://chat/x", emptyList(), past = 0), null), read(words))
    }

    @Test
    fun `a file URI and the app's own provider are refused, and a share of them alone is none`() {
        val planted = Uri.parse("file:///data/user/0/io.tezra.fermix/no_backup/instances.json")
        val own = Uri.parse("content://$OWN/shared/0123/report.pdf")
        val intent = send(action = Intent.ACTION_SEND_MULTIPLE, type = "*/*")
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(planted, own, photo(3)))
        val shared =
            Shared(listOf(photo(3).toString()), null, listOf("file URI of no authority", "content URI of $OWN"), 0)
        assertEquals(Share(shared, null), read(intent))
        // A share of which nothing lands, every item refused, is no share: no chat opens for it.
        val refused = send(action = Intent.ACTION_SEND_MULTIPLE, type = "*/*")
        refused.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(planted, own))
        assertNull(read(refused))
    }

    @Test
    fun `the activity takes no share, from its entry's name or its own`() {
        assertNull(appIntentOf(send(to = ACTIVITY).putExtra(Intent.EXTRA_STREAM, photo(1))))
        assertNull(appIntentOf(send(to = ACTIVITY, type = "text/plain").putExtra(Intent.EXTRA_TEXT, "hi")))
        assertNull(appIntentOf(send(type = "text/plain").putExtra(Intent.EXTRA_TEXT, "hi")))
    }

    @Test
    fun `the entry takes nothing but a share, no chat's link, no Add Fermix, no other action`() {
        val id = "ab".repeat(32)
        assertNull(read(Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/$id/main")).setComponent(ENTRY)))
        assertNull(read(Intent(ACTION_ADD_FERMIX).setComponent(ENTRY)))
        assertNull(read(Intent(Intent.ACTION_MAIN).setComponent(ENTRY)))
        // A share that also carries a chat's link is a share, and the link is not followed.
        val both =
            send(type = "text/plain").setData(Uri.parse("fermix://chat/$id/main")).putExtra(Intent.EXTRA_TEXT, "hi")
        assertEquals(Share(Shared(emptyList(), "hi", emptyList(), 0), null), read(both))
    }

    @Test
    fun `a stream or words of the wrong type, or none, are not read, and a share of nothing is nothing, logged`() {
        ShadowLog.clear()
        assertNull(read(send().putExtra(Intent.EXTRA_STREAM, "content://media/external/images/media/1")))
        assertNull(read(send(type = "text/plain").putExtra(Intent.EXTRA_TEXT, 7)))
        assertNull(read(send()))
        val list = send(action = Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, photo(1))
        assertNull(read(list))
        val dropped = ShadowLog.getLogsForTag("FermixShare").map { it.msg }
        assertEquals(List(4) { "a share carried nothing the app reads; it was dropped" }, dropped)
        // What is no share at all is not one dropped.
        ShadowLog.clear()
        assertNull(read(Intent(Intent.ACTION_MAIN).setComponent(ENTRY)))
        assertEquals(emptyList<String>(), ShadowLog.getLogsForTag("FermixShare").map { it.msg })
    }

    @Test
    fun `an intent the system replays from Recents is taken for nothing, a share or a chat's link`() {
        val replayed = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK
        val words = send(type = "text/plain").putExtra(Intent.EXTRA_TEXT, "hi")
        assertEquals(Share(Shared(emptyList(), "hi", emptyList(), 0), null), read(Intent(words)))
        assertNull(read(words.addFlags(replayed)))
        val image = send().putExtra(Intent.EXTRA_STREAM, photo(1)).addFlags(replayed)
        assertNull(read(image))
        val id = "ab".repeat(32)
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/$id/main"))
        assertEquals(AppIntent.OpenChat(ChatKey(id, "main")), appIntentOf(Intent(link)))
        assertNull(appIntentOf(link.addFlags(replayed)))
        assertNull(appIntentOf(Intent(ACTION_ADD_FERMIX).addFlags(replayed)))
    }

    @Test
    fun `the forward is the launcher's own intent, onto the activity, with the granted URIs and no extra`() {
        val granted = listOf(photo(1), photo(2))
        val forward = shareForward(app, granted)
        assertEquals(ComponentName(app, MainActivity::class.java), forward.component)
        assertEquals(Intent.ACTION_MAIN, forward.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), forward.categories)
        assertNull(forward.data)
        assertNull(forward.type)
        assertNull(forward.extras)
        val flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertEquals(flags, forward.flags)
        val clip = checkNotNull(forward.clipData)
        assertEquals(granted, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        // With no grant to hand on, it carries no URI and asks for no grant.
        val bare = shareForward(app, emptyList())
        assertNull(bare.clipData)
        assertEquals(0, bare.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `the entry first sends the launcher's own intent, which a task it starts keeps, then hands its grants on`() {
        val granted = listOf(photo(1), photo(2))
        val (first, withGrants) = shareForwards(app, granted)
        // The intent that starts a task stays the task's own, which Recents starts again once the activity is gone:
        // it names no grant the app would no longer hold by then.
        assertEquals(Intent.ACTION_MAIN, first.action)
        assertNull(first.clipData)
        assertEquals(0, first.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = checkNotNull(withGrants.clipData)
        assertEquals(granted, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertEquals(2, shareForwards(app, granted).size)
        // With no grant to hand on, the launcher's own intent alone.
        val alone = shareForwards(app, emptyList()).single()
        assertNull(alone.clipData)
        assertEquals(0, alone.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
