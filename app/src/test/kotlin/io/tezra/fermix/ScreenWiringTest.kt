package io.tezra.fermix

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chats.ChatsParts
import io.tezra.fermix.chats.ChatsViewModel
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NOTIFIED_ID_RETENTION_MS
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.data.launchCheck
import io.tezra.fermix.instance.InstanceParts
import io.tezra.fermix.instance.InstanceViewModel
import io.tezra.fermix.instance.NotificationsPolicy
import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * How long a test may wait, in real time, for what DataStore's and Room's own threads deliver to what it
 * awaits; past it the test fails.
 */
private val SETTLE = 10.seconds

private val TAILNET = Candidate("100.101.102.103", Candidate.Scope.TAILNET, Candidate.Kind.IP)
private val LAN = Candidate("192.168.1.20", Candidate.Scope.LAN, Candidate.Kind.IP)

private val RECORD =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        tlsFp = "02".repeat(32),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = listOf(TAILNET),
        port = 4031,
        deviceId = "device-1",
        keyAlias = "fermix.device.1.0102030405060708",
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x73 }),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/** [RECORD]'s twin for the daemon whose gateway key is every byte [gateway], titled suj-mbp as well. */
private fun gateway(gateway: Int): Instance =
    RECORD.copy(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { gateway.toByte() }),
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
    )

private fun helloAck(): ServerEvent.HelloAck =
    ServerEvent.HelloAck(
        sessionId = "s-1",
        minVersion = 1,
        maxVersion = 1,
        profiles = listOf(Profile(id = MAIN_PROFILE, name = "Jarvis")),
        candidates = emptyList(),
        historyHeadSeq = 0uL,
        readUpToSeq = 0uL,
        caps = Caps(commands = emptyList(), maxMediaBytes = 1_000, push = listOf(PushPlatform.FCM)),
        instance =
            io.tezra.fermix.protocol
                .Instance(label = "Studio Mac", host = "studio", profile = "fermix"),
    )

/** A policy that remembers its one call. */
private class RecordingPolicy : NotificationsPolicy {
    val call = CompletableDeferred<Pair<Instance, Boolean>>()

    override suspend fun apply(
        instance: Instance,
        on: Boolean,
    ) {
        call.complete(instance to on)
    }
}

/**
 * What reaches the screens from the stores and the sessions: the session events the app keeps (design
 * sections 9.2 and 10), the notified set's expired ids and an approval's notification cleared by its id among
 * them, an older page's rows, the Chats row's unread count from the notified set (section 9.4), and the Instance
 * screen's Notifications switch through its policy (section 13.7).
 *
 * On Robolectric, as MainActivityTest: the app's tests load the bundled SQLite library once for the JVM,
 * into Robolectric's classloader, so every test of the app that opens a database runs there. The plain
 * Application stands in for the app's, whose services these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenWiringTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory: File get() = folder.root

    private val main = StandardTestDispatcher()

    @Before
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @After
    fun mainBack() = Dispatchers.resetMain()

    private val databases by lazy {
        ProfileDatabases(ApplicationProvider.getApplicationContext(), File(directory, "profiles"))
    }

    /** The app's sink over [store], its chats' folds on a clock that stands still. */
    private fun events(
        store: InstanceStore,
        notifications: Notifications = testNotifications(databases),
    ) = SessionEvents(
        store,
        databases,
        ChatFolds(TestClock) { 0uL },
        NoAlerts,
        notifications,
        quietRegistrations(store),
    )

    private suspend fun TestScope.store(): InstanceStore {
        val records = instanceDataStore(File(directory, "instances.json"), backgroundScope)
        val store = InstanceStore(records, databases)
        store.upsert(RECORD)
        return store
    }

    @Test
    fun `hello_ack's facts reach the record and the agent's name the chat, and new routes the record`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val events = events(store)
            val session = idleSession(backgroundScope)
            events.take(RECORD.id, session, SessionEvent.Server(helloAck()))
            val acked = store.instances.first().single()
            assertEquals("Studio Mac", acked.label)
            assertEquals("studio", acked.host)
            assertEquals(listOf(PushPlatform.FCM), acked.pushPlatforms)
            val chat = databases.open(RECORD.id, MAIN_PROFILE).chat()
            assertEquals("Jarvis", chat.state().first { it.agentName != null }.agentName)

            events.take(RECORD.id, session, SessionEvent.Candidates(listOf(LAN, TAILNET)))
            assertEquals(
                listOf(LAN, TAILNET),
                store.instances
                    .first()
                    .single()
                    .candidates,
            )
        }

    @Test
    fun `the read frontier takes the rows it covers from the notified set`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val notified = databases.open(RECORD.id, MAIN_PROFILE).notified()
            listOf(3uL, 5uL, 9uL).forEach { assertTrue(notified.put(NotifiedEntry.Row(it), 1_000L)) }
            assertTrue(events(store).take(RECORD.id, idleSession(backgroundScope), SessionEvent.ReadFrontier(5uL)))
            assertEquals(listOf(9uL), notified.serverSeqs().first())
        }

    @Test
    fun `a read frontier the session says rebuilds the conversation's notification, and cancels it once empty`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val notifications = testNotifications(databases, MutableStateFlow(listOf(RECORD)))
            val events = events(store, notifications)
            val notified = databases.open(RECORD.id, MAIN_PROFILE).notified()
            listOf(3uL, 5uL).forEach { assertTrue(notified.put(NotifiedEntry.Row(it), 1_000L)) }
            assertTrue(notifications.postMessages(RECORD, MAIN_PROFILE))
            val tag = "${conversationId(RECORD.id, MAIN_PROFILE)}/messages"
            assertEquals(2, shownMessages(tag))

            assertTrue(events.take(RECORD.id, idleSession(backgroundScope), SessionEvent.ReadFrontier(3uL)))
            assertEquals(1, shownMessages(tag))
            assertTrue(events.take(RECORD.id, idleSession(backgroundScope), SessionEvent.ReadFrontier(5uL)))
            assertEquals(emptyList<String>(), notificationManager().activeNotifications.map { it.tag })
        }

    @Test
    fun `a reconciled connection takes the approvals and failed turns past their retention from the notified set`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val now = NOTIFIED_ID_RETENTION_MS + 2_000L
            val notified = databases.open(RECORD.id, MAIN_PROFILE).notified()
            assertTrue(notified.put(NotifiedEntry.Approval("ap-old"), 1_000L))
            assertTrue(notified.put(NotifiedEntry.TurnFailed("turn-new"), now - 500L))
            val events = events(store, testNotifications(databases, now = { now }))
            val reconciled = SessionEvent.Reconciled(pulledInFull = false)
            assertTrue(events.take(RECORD.id, idleSession(backgroundScope), reconciled))
            assertFalse(notified.contains(NotifiedEntry.Approval("ap-old")))
            assertTrue(notified.contains(NotifiedEntry.TurnFailed("turn-new")))
        }

    @Test
    fun `an approval the daemon resolved, or closed while the phone was away, takes its notification with it`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val notifications = testNotifications(databases, MutableStateFlow(listOf(RECORD)))
            val events = events(store, notifications)
            val card = SessionEvent.Approval("ap-1", "sandbox", "Run make test?", "make test", ttlS = 90)
            listOf(card, card.copy(approvalId = "ap-2"), card.copy(approvalId = "ap-3")).forEach {
                notifications.notify(RECORD.id, it)
            }
            val session = idleSession(backgroundScope)
            assertTrue(events.take(RECORD.id, session, SessionEvent.ApprovalResolved("ap-1", ApprovalOutcome.APPROVED)))
            assertTrue(events.take(RECORD.id, session, SessionEvent.ApprovalClosedWhileAway("ap-2")))
            val conversation = conversationId(RECORD.id, MAIN_PROFILE)
            assertEquals(
                listOf("$conversation/approval/ap-3"),
                notificationManager().activeNotifications.map { it.tag },
            )
        }

    private fun notificationManager(): NotificationManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java)

    /** How many messages the notification under [tag] lists; it must be showing. */
    private fun shownMessages(tag: String): Int {
        val shown = notificationManager().activeNotifications.single { it.tag == tag }.notification
        return shown.extras
            .getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            .orEmpty()
            .size
    }

    @Test
    fun `an older page's rows are kept in the chat's cache, which they were not announced to`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val older =
                listOf(3, 4).map { seq ->
                    TimelineRow.Message(
                        HistoryMessage(seq.toULong(), "assistant", "row $seq", "2026-09-27T09:0$seq:00Z", emptyList()),
                    )
                }
            val page = SessionEvent.OlderLoaded(older, 2uL)
            assertTrue(events(store).take(RECORD.id, idleSession(backgroundScope), page))
            val timeline = databases.open(RECORD.id, MAIN_PROFILE).timeline()
            assertEquals(listOf(4uL, 3uL), timeline.newest(10).first().map { it.serverSeq })
        }

    @Test
    fun `a Chats row's unread count is the size of its notified set`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val notified = databases.open(RECORD.id, MAIN_PROFILE).notified()
            listOf(4uL, 6uL).forEach { assertTrue(notified.put(NotifiedEntry.Row(it), 1_000L)) }
            val chats = ChatsViewModel(chatsParts(store))
            val ui = chats.ui.first { it != null && it.rows.isNotEmpty() }
            assertEquals(2, checkNotNull(ui).rows.single().unread)
        }

    @Test
    fun `each Fermix a launch dropped keeps its own Re-pair row, whatever its title, until its daemon is back`() =
        runTest(main, timeout = SETTLE) {
            val store = InstanceStore(instanceDataStore(File(directory, "instances.json"), backgroundScope), databases)
            // The canon's production and dev daemons on one computer, both titled suj-mbp, and a third of
            // that title whose key the restore kept.
            val production = RECORD
            val dev = gateway(2)
            val kept = gateway(3)
            listOf(production, dev, kept).forEach { store.upsert(it) }
            launchCheck(store) { it == kept.keyAlias }
            val chats = ChatsViewModel(chatsParts(store))
            val dropped = checkNotNull(chats.ui.first { it != null && it.rows.isNotEmpty() })
            assertEquals(listOf(kept.id), dropped.rows.map { it.record.id })
            assertEquals(listOf(production.id, dev.id), dropped.repairs.map { it.id })
            assertEquals(listOf("suj-mbp", "suj-mbp"), dropped.repairs.map { it.title })

            store.upsert(production.copy(keyAlias = "fermix.device.1.again"))
            val repaired = checkNotNull(chats.ui.first { it != null && it.rows.size == 2 })
            assertEquals(listOf(dev.id), repaired.repairs.map { it.id })
            assertEquals(listOf(dev.id), store.repairNotices.first().map { it.id })
        }

    private fun chatsParts(store: InstanceStore): ChatsParts =
        ChatsParts(
            instances = store,
            sessions = MutableStateFlow(emptyMap<String, Session>()),
            thinking = MutableStateFlow(emptySet()),
            profiles = databases,
            unpair = {},
            remove = {},
        )

    @Test
    fun `the Notifications switch is kept on the record and then handed to the policy`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val policy = RecordingPolicy()
            InstanceViewModel(instanceParts(store, databases, policy), RECORD.id).setNotifications(true)
            val (record, on) = policy.call.await()
            assertTrue(on)
            assertTrue(record.notificationsEnabled)
            assertTrue(
                store.instances
                    .first()
                    .single()
                    .notificationsEnabled,
            )
        }

    @Test
    fun `a session's event that comes after its Fermix's removal writes nothing, and makes none of its files again`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            databases.open(RECORD.id, MAIN_PROFILE)
            store.remove(RECORD.id)
            val events = events(store)
            assertFalse(
                "a removed Fermix's event kept something",
                events.take(RECORD.id, idleSession(backgroundScope), SessionEvent.ReadFrontier(5uL)),
            )
            assertFalse("the event made the removed files again", File(directory, "profiles/${RECORD.id}").exists())
        }

    @Test
    fun `the Instance screen of a Fermix removed meanwhile reads nothing, does nothing, and makes none of its files`() =
        runTest(main, timeout = SETTLE) {
            // The profiles on the test's own dispatcher, so that each action has run once the scheduler is idle.
            val profiles = ProfileDatabases(ApplicationProvider.getApplicationContext(), File(directory, "here"), main)
            val store = InstanceStore(instanceDataStore(File(directory, "instances.json"), backgroundScope), profiles)
            store.upsert(RECORD)
            profiles.open(RECORD.id, MAIN_PROFILE)
            store.remove(RECORD.id)
            val model = InstanceViewModel(instanceParts(store, profiles, RecordingPolicy()), RECORD.id)
            backgroundScope.launch { model.ui.collect {} }
            model.entered()
            model.setPreviews(false)
            model.clearCache()
            advanceUntilIdle()
            assertNull(model.ui.value)
            assertFalse("the screen made the removed files again", File(directory, "here/${RECORD.id}").exists())
        }

    private fun instanceParts(
        store: InstanceStore,
        profiles: ProfileDatabases,
        policy: NotificationsPolicy,
    ): InstanceParts =
        InstanceParts(
            instances = store,
            sessions = MutableStateFlow(emptyMap()),
            network = MutableStateFlow(NetworkFacts(1L, false, false, false)),
            profiles = profiles,
            tester = { error("no connection test in this test") },
            notifications = policy,
            unpair = {},
            releaseBuild = false,
        )
}
