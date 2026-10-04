package io.tezra.fermix

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.chat.ChatViewModel
import io.tezra.fermix.chat.Picked
import io.tezra.fermix.chat.PickedFrom
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.Use
import io.tezra.fermix.session.MAX_ATTACHMENTS
import io.tezra.fermix.session.OutboxItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.net.URI

private const val TAG = "ShareDeviceTest"
private const val HOST_A = "share-test-a"
private const val HOST_B = "share-test-b"
private const val WORDS = "look at this"
private const val ASKS = "Send to which Fermix?"

/** The phone's screen lock the lock test sets, and takes away again. */
private const val PIN = "1357"

/**
 * Another app's share through the share entry, on the device (design section 13.6, "Share into Fermix"): the
 * system starts the entry, which hands the share to the app's one activity in its own task, and each item is copied
 * into the chat's own file as it lands, nothing sent. Most shares are the app's own, of media-store rows it saved and
 * reads with no grant; one is the shell's, another uid, of a row only its grant lets the app read, handed from the
 * entry to the activity, and held through a rotation and the pick. Each test pairs records that no session can
 * open, and removes them, with the media store's rows and the files it made; the app must hold no record before.
 */
@RunWith(AndroidJUnit4::class)
class ShareDeviceTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FermixApplication
    private val services get() = app.services
    private val resolver get() = app.contentResolver
    private val paired = mutableListOf<Instance>()
    private val images = mutableListOf<Uri>()
    private val shellImages = mutableListOf<Uri>()
    private val files = mutableListOf<File>()

    @Before
    fun nothingPaired() {
        runBlocking { services.instances.dismissRepairNotices() }
        val held = runBlocking { services.instances.instances.first() }
        assertEquals("the share tests need an app with no Fermix paired", emptyList<Instance>(), held)
    }

    @After
    fun cleanUp() {
        val live = liveActivities()
        instrumentation.runOnMainSync { live.forEach { it.finishAndRemoveTask() } }
        rule.waitUntil(STEP_MILLIS) { liveActivities().isEmpty() }
        runBlocking {
            paired.forEach { services.instances.remove(it.id) }
            services.settings.setAppLock(false)
            services.settings.setLastChat(null)
        }
        images.forEach { resolver.delete(it, null, null) }
        shellImages.forEach(::deleteFromShell)
        files.forEach { it.delete() }
    }

    private fun pair(vararg records: Instance) {
        runBlocking { records.forEach { services.instances.upsert(it) } }
        paired += records
    }

    /** A share to the entry, as the system's share sheet starts it from another app's task. */
    private fun shareIntent(
        action: String,
        type: String,
    ): Intent = Intent(action).setType(type).setComponent(ComponentName(app, SHARE_ENTRY))

    private fun start(intent: Intent) = app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun shows(text: String) =
        rule.waitUntil(STEP_MILLIS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** [record]'s main chat once it shows over the list, and its model, the one its screen draws. */
    private fun opened(record: Instance): ChatViewModel {
        val chat = ChatKey(record.id, MAIN_PROFILE)
        shows(record.host)
        rule.waitUntil(STEP_MILLIS) { above() == listOf(chat) }
        var model: ChatViewModel? = null
        val activity = liveActivities().single()
        instrumentation.runOnMainSync {
            model =
                ViewModelProvider(activity)["chat:${chat.instanceId}:main", ChatViewModel::class.java]
        }
        return checkNotNull(model)
    }

    private fun above(): List<NavKey>? {
        val activity = liveActivities().singleOrNull() ?: return null
        var keys: List<NavKey>? = null
        instrumentation.runOnMainSync { keys = ViewModelProvider(activity)[AppNavigator::class.java].above.value }
        return keys
    }

    private fun tray(model: ChatViewModel): List<Picked> = model.attach.ui.value.picked

    /** Nothing of [record]'s chat is waiting to go: no Send was tapped. */
    private fun sentNothing(record: Instance) {
        val pending = runBlocking { services.databases.withDatabase(record.id, MAIN_PROFILE) { it.pending().first() } }
        assertEquals(Use.Ran(emptyList<OutboxItem>()), pending)
    }

    @Test
    fun mediaStoreImageSharedWithTwoPairedAsksWhichAndLandsInThePickedChatsTrayWithNothingSent() {
        val a = testRecord(1, HOST_A)
        val b = testRecord(2, HOST_B)
        pair(a, b)
        val image = galleryImage(resolver, "share-test-1.png").also(images::add)
        start(shareIntent(Intent.ACTION_SEND, "image/png").putExtra(Intent.EXTRA_STREAM, image))
        shows(ASKS)
        val rows = rule.onAllNodes(hasAnyAncestor(isDialog()) and (hasText(HOST_A) or hasText(HOST_B)))
        assertEquals(2, rows.fetchSemanticsNodes().size)
        rule.onNode(hasAnyAncestor(isDialog()) and hasText(HOST_B)).performClick()
        val model = opened(b)
        rule.waitUntil(STEP_MILLIS) { tray(model).isNotEmpty() }
        val item = tray(model).single()
        assertEquals(PickedFrom.SHARE, item.from)
        // The chat's own copy, made as it landed.
        assertTrue(item.uri, item.uri.startsWith("file:"))
        val original = checkNotNull(resolver.openInputStream(image)).use { it.readBytes() }
        assertArrayEquals(original, File(URI(item.uri)).readBytes())
        sentNothing(a)
        sentNothing(b)
    }

    @Test
    fun twelveImagesSharedLandTenInTheOnePairedChatsTray() {
        val a = testRecord(1, HOST_A)
        pair(a)
        val twelve = (1..12).map { galleryImage(resolver, "share-test-$it.png").also(images::add) }
        val intent = shareIntent(Intent.ACTION_SEND_MULTIPLE, "image/*")
        start(intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(twelve)))
        val model = opened(a)
        rule.waitUntil(STEP_MILLIS) { tray(model).size == MAX_ATTACHMENTS }
        assertTrue(tray(model).all { it.from == PickedFrom.SHARE && it.uri.startsWith("file:") })
        assertEquals(MAX_ATTACHMENTS, tray(model).map { it.uri }.distinct().size)
        sentNothing(a)
    }

    @Test
    fun sharedWordsLandInTheComposerAsWordsWithNothingSent() {
        val a = testRecord(1, HOST_A)
        pair(a)
        start(shareIntent(Intent.ACTION_SEND, "text/plain").putExtra(Intent.EXTRA_TEXT, WORDS))
        val model = opened(a)
        rule.waitUntil(STEP_MILLIS) { model.composer.field.value.text == WORDS }
        rule.waitUntil(STEP_MILLIS) {
            rule.onAllNodes(hasSetTextAction() and hasText(WORDS)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(emptyList<Picked>(), tray(model))
        sentNothing(a)
    }

    @Test
    fun aFileUriAndTheAppsOwnProviderAreRefusedAndNothingOfThemReachesTheTray() {
        val a = testRecord(1, HOST_A)
        pair(a)
        val planted = File(app.cacheDir, "share-test-planted.txt").also(files::add)
        planted.writeText("planted")
        val ownFile = File(app.cacheDir, "shared/share-test-own.txt").also(files::add)
        check(
            ownFile.parentFile?.isDirectory == true || ownFile.parentFile?.mkdirs() == true,
        ) { "no ${ownFile.parent}" }
        ownFile.writeText("own")
        val own = FileProvider.getUriForFile(app, "${app.packageName}.chat.files", ownFile)
        // Another app's image after them: once it is in the tray, everything the share carried has landed.
        val image = galleryImage(resolver, "share-test-1.png").also(images::add)
        val streams = arrayListOf(Uri.fromFile(planted), own, image)
        val intent =
            shareIntent(
                Intent.ACTION_SEND_MULTIPLE,
                "*/*",
            ).putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
        withFileUrisSent { start(intent) }
        val model = opened(a)
        rule.waitUntil(STEP_MILLIS) { tray(model).isNotEmpty() }
        val item = tray(model).single()
        assertArrayEquals(
            checkNotNull(resolver.openInputStream(image)).use { it.readBytes() },
            File(URI(item.uri)).readBytes(),
        )
        sentNothing(a)
    }

    @Test
    fun withTheAppLockOnTheLockComesFirstAndTheShareLandsOnceItIsPassed() {
        val a = testRecord(1, HOST_A)
        pair(a)
        shell("locksettings set-pin $PIN")
        try {
            runBlocking { services.settings.setAppLock(true) }
            awayPastTheGrace()
            start(shareIntent(Intent.ACTION_SEND, "text/plain").putExtra(Intent.EXTRA_TEXT, WORDS))
            shows("Fermix is locked")
            rule.waitUntil(STEP_MILLIS) { services.lockGate.sight.value == Sight.LOCKED }
            // The share waits behind the lock: no chat, no sheet, no words.
            assertEquals(emptyList<NavKey>(), above())
            assertTrue(rule.onAllNodesWithText(WORDS).fetchSemanticsNodes().isEmpty())
            assertTrue(rule.onAllNodesWithText(ASKS).fetchSemanticsNodes().isEmpty())
            unlockWithPin()
            val model = opened(a)
            rule.waitUntil(STEP_MILLIS) { model.composer.field.value.text == WORDS }
            sentNothing(a)
        } finally {
            runBlocking { services.settings.setAppLock(false) }
            shell("locksettings clear --old $PIN")
        }
    }

    @Test
    fun anotherAppsImageLandsThroughTheGrantItsShareCarriedAfterARotationAndThePick() {
        val a = testRecord(1, HOST_A)
        val b = testRecord(2, HOST_B)
        pair(a, b)
        val bytes = png()
        val image = shellImage("share-test-shell.png", bytes).also(shellImages::add)
        assertFalse("the app reads the shell's row with no grant", readsAlone(image))
        shareFromShell(image, "image/png")
        shows(ASKS)
        rotated { shows(ASKS) }
        // Upright again, the activity made once more asks still.
        shows(ASKS)
        rule.onNode(hasAnyAncestor(isDialog()) and hasText(HOST_B)).performClick()
        val model = opened(b)
        rule.waitUntil(STEP_MILLIS) { tray(model).isNotEmpty() }
        val item = tray(model).single()
        // Copied as it landed, after the rotation and the pick, through the grant the entry handed the activity.
        assertTrue(item.uri, item.uri.startsWith("file:"))
        assertArrayEquals(bytes, File(URI(item.uri)).readBytes())
        sentNothing(a)
        sentNothing(b)
    }

    @Test
    fun aProviderThatFailsAsItsItemIsReadLeavesNothingInTheTrayAndTheAppGoesOn() {
        val a = testRecord(1, HOST_A)
        pair(a)
        // The settings provider takes any app's query and refuses the columns an item is described by.
        shareFromShell(Uri.parse("content://settings/global"), "image/png", granted = false)
        val model = opened(a)
        val image = galleryImage(resolver, "share-test-after.png").also(images::add)
        start(shareIntent(Intent.ACTION_SEND, "image/png").putExtra(Intent.EXTRA_STREAM, image))
        rule.waitUntil(STEP_MILLIS) { tray(model).isNotEmpty() }
        val item = tray(model).single()
        assertArrayEquals(
            checkNotNull(resolver.openInputStream(image)).use { it.readBytes() },
            File(URI(item.uri)).readBytes(),
        )
        sentNothing(a)
    }

    @Test
    fun aTaskAnotherAppsShareStartedIsStartedAgainFromRecentsOnceItsActivityIsGone() {
        val a = testRecord(1, HOST_A)
        pair(a)
        val image = shellImage("share-test-recents.png", png()).also(shellImages::add)
        shareFromShell(image, "image/png")
        val model = opened(a)
        rule.waitUntil(STEP_MILLIS) { tray(model).isNotEmpty() }
        // The activity finishes, as Back at the Chats list finishes it, and its task stays in Recents with none.
        val activity = liveActivities().single()
        val task = activity.taskId
        instrumentation.runOnMainSync { activity.finish() }
        rule.waitUntil(STEP_MILLIS) { liveActivities().isEmpty() }
        // A tap on its card starts the task's own intent again, which must name no grant the app no longer holds.
        val recent = app.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo?.taskId == task }
        recent.moveToFront()
        rule.waitUntil(STEP_MILLIS) { resumedActivity() != null }
        assertEquals(task, liveActivities().single().taskId)
        sentNothing(a)
    }

    @Test
    fun theLauncherBringsTheAppBackWithWhatItHadOpenOverItsActivity() {
        val a = testRecord(1, HOST_A)
        pair(a)
        start(launcher())
        shows(HOST_A)
        val activity = liveActivities().single()
        // The documents UI, as the attach sheet's Files opens it, in the app's task over the activity.
        val files = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        instrumentation.runOnMainSync { activity.startActivity(files) }
        rule.waitUntil(STEP_MILLIS) { "documentsui" in focusedWindow() }
        shell("input keyevent KEYCODE_HOME")
        rule.waitUntil(STEP_MILLIS) { "documentsui" !in focusedWindow() }
        start(launcher())
        // The picker the owner left is still there, over the activity, which never came back on top of it.
        rule.waitUntil(STEP_MILLIS) { "documentsui" in focusedWindow() }
        assertEquals(null, resumedActivity())
        assertTrue(liveActivities().single() === activity)
    }

    /** The launcher's own intent for the app, as a tap on its icon sends it. */
    private fun launcher(): Intent =
        Intent
            .makeMainActivity(ComponentName(app, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Whether the app reads [uri] with no grant, by its own rights. */
    private fun readsAlone(uri: Uri): Boolean =
        try {
            resolver.openInputStream(uri)?.use { it.read() }
            true
        } catch (refused: SecurityException) {
            Log.i(TAG, "the app may not read $uri alone", refused)
            false
        } catch (missing: FileNotFoundException) {
            Log.i(TAG, "the app finds no $uri alone", missing)
            false
        }

    /**
     * [check] once the display turned a quarter and the activity was made again, then the display as it was, and the
     * activity made again once more and resumed: what follows acts on that one, never on the turned one as it goes.
     */
    private fun rotated(check: () -> Unit) {
        val automation = instrumentation.uiAutomation
        val before = liveActivities().single()
        assertTrue("the display turns", automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        var turned: MainActivity? = null
        try {
            rule.waitUntil(STEP_MILLIS) { liveActivities().singleOrNull().let { it != null && it !== before } }
            turned = liveActivities().single()
            check()
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
        rule.waitUntil(STEP_MILLIS) { resumedActivity().let { it != null && it !== turned } }
    }

    /** The app into sight from the launcher, then home, and away past the lock's grace. */
    private fun awayPastTheGrace() {
        start(launcher())
        shows(HOST_A)
        shell("input keyevent KEYCODE_HOME")
        rule.waitUntil(STEP_MILLIS) { services.lockGate.sight.value == Sight.AWAY }
        SystemClock.sleep(BACKGROUND_GRACE_MILLIS + 1_000)
    }

    /** The system's prompt has the focus over the app; the PIN goes into it. */
    private fun unlockWithPin() {
        rule.waitUntil(
            STEP_MILLIS,
        ) { "io.tezra.fermix/" !in focusedWindow() && "mCurrentFocus=null" !in focusedWindow() }
        // The prompt's PIN field takes the focus as its window comes in.
        SystemClock.sleep(1_000)
        shell("input text $PIN")
        shell("input keyevent KEYCODE_ENTER")
        rule.waitUntil(STEP_MILLIS) { services.lockGate.sight.value == Sight.OPEN }
    }

    /**
     * [send] with `file:` URIs let out of the process: the platform's StrictMode stops an app targeting Android 7
     * or later from sending one, as another app may still do.
     */
    private fun withFileUrisSent(send: () -> Unit) {
        val policy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        try {
            send()
        } finally {
            StrictMode.setVmPolicy(policy)
        }
    }
}
