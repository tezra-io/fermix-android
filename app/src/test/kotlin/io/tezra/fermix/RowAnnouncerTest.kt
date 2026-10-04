package io.tezra.fermix

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private val INSTANCE = "ab".repeat(32)

/** Another Fermix, whose chat may be the one on screen. */
private val OTHER = "cd".repeat(32)

private fun row(
    seq: Long,
    role: String = "assistant",
): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(
            serverSeq = seq.toULong(),
            role = role,
            content = "the backup finished",
            ts = "2026-10-02T09:00:00Z",
            mediaRefs = emptyList(),
        ),
    )

/** A notifier that can post while [able] says so, and remembers what it posted. */
private class RecordingNotifier : RowNotifier {
    var able = true
    val posted = mutableListOf<ULong>()

    override fun canNotify(
        instanceId: String,
        profileId: String,
    ): Boolean = able

    override suspend fun notify(
        instanceId: String,
        profileId: String,
        row: TimelineRow,
    ) {
        posted += row.serverSeq
    }
}

/**
 * The app's announcer (core-session's Announcer, design section 10): what it answers decides whether a row
 * is acked, and a row may be acked only once its owner was told of it (tla/specs/mobile_push, PUSH-2).
 *
 * On Robolectric, as MainActivityTest: the app's tests load the bundled SQLite library once for the JVM,
 * into Robolectric's classloader, so every test of the app that opens a database runs there. The plain
 * Application stands in for the app's, whose services these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RowAnnouncerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory: File get() = folder.root

    /** The (instance, profile) whose chat is on screen, none while no chat is. */
    private var showing: Pair<String, String>? = null
    private val notifier = RecordingNotifier()

    private fun database(): ProfileDatabase =
        ProfileDatabases(ApplicationProvider.getApplicationContext(), directory).open(INSTANCE, MAIN_PROFILE)

    private fun announcer(database: ProfileDatabase) =
        RowAnnouncer(
            INSTANCE,
            MAIN_PROFILE,
            lazyOf(database),
            { id, profile, _ -> showing == id to profile },
            notifier,
        ) {
            1_000L
        }

    private suspend fun ProfileDatabase.kept(): List<ULong> =
        timeline()
            .newest(10)
            .first()
            .map { it.serverSeq }

    @Test
    fun `a row of the chat on screen is shown there, kept, and neither notified nor put in the set`() =
        runTest {
            val database = database()
            showing = INSTANCE to MAIN_PROFILE
            assertEquals(Announcement.ON_SCREEN, announcer(database).announce(row(7)))
            assertEquals(
                listOf(7uL),
                database
                    .timeline()
                    .newest(10)
                    .first()
                    .map { it.serverSeq },
            )
            assertEquals(emptyList<ULong>(), database.notified().serverSeqs().first())
            assertEquals(emptyList<ULong>(), notifier.posted)
        }

    @Test
    fun `another Fermix's chat on screen shows nothing of this one's row, which is notified, or not announced`() =
        runTest {
            val database = database()
            showing = OTHER to MAIN_PROFILE
            assertEquals(Announcement.NOTIFIED, announcer(database).announce(row(5)))
            assertEquals(listOf(5uL), database.notified().serverSeqs().first())
            assertEquals(listOf(5uL), notifier.posted)
            notifier.able = false
            assertEquals(Announcement.NOT_ANNOUNCED, announcer(database).announce(row(6)))
            assertEquals(listOf(5uL), database.notified().serverSeqs().first())
            assertEquals(listOf(5uL), notifier.posted)
            assertEquals(listOf(6uL, 5uL), database.kept())
        }

    @Test
    fun `the owner's own message is kept and known already, wherever the chat is`() =
        runTest {
            val database = database()
            assertEquals(Announcement.ALREADY_KNOWN, announcer(database).announce(row(3, role = "user")))
            assertEquals(listOf(3uL), database.kept())
            assertEquals(emptyList<ULong>(), notifier.posted)
        }

    @Test
    fun `a row that can be notified of in no way is not announced, so it is never acked`() =
        runTest {
            val database = database()
            notifier.able = false
            assertEquals(Announcement.NOT_ANNOUNCED, announcer(database).announce(row(9)))
            assertEquals(
                listOf(9uL),
                database
                    .timeline()
                    .newest(10)
                    .first()
                    .map { it.serverSeq },
            )
            assertEquals(emptyList<ULong>(), database.notified().serverSeqs().first())
            assertEquals(emptyList<ULong>(), notifier.posted)
        }

    @Test
    fun `a row off screen is put in the set and notified once, and the same row again is known`() =
        runTest {
            val database = database()
            val announcer = announcer(database)
            assertEquals(Announcement.NOTIFIED, announcer.announce(row(12)))
            assertEquals(listOf(12uL), database.kept())
            assertEquals(listOf(12uL), database.notified().serverSeqs().first())
            assertEquals(Announcement.ALREADY_KNOWN, announcer.announce(row(12)))
            assertEquals(listOf(12uL), notifier.posted)
        }
}
