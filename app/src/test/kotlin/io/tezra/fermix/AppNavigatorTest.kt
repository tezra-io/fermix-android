package io.tezra.fermix

import io.tezra.fermix.data.AppSettingsStore
import io.tezra.fermix.data.ChatRef
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.appSettingsDataStore
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import java.util.Locale

private fun paired(gateway: Int): Instance =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { gateway.toByte() }),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(32),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x73 }),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/** The app's screens above the Chats list, the chat restored on return, and a chat's deep link (section 13.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigatorTest {
    @TempDir
    lateinit var directory: File

    private val main = StandardTestDispatcher()
    private val first = paired(1)
    private val second = paired(2)

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private fun TestScope.settings(name: String = "settings.json") =
        AppSettingsStore(appSettingsDataStore(File(directory, name), backgroundScope))

    @Test
    fun `a chat's link is fermix chat, an instance's hex id and a profile, and nothing else`() {
        val id = first.id
        assertEquals(ChatKey(id, "main"), chatOfLink(chatLink(id, "main")))
        assertNull(chatOfLink("fermix://chat/${id.uppercase()}/main"))
        assertNull(chatOfLink("fermix://chat/$id/main/more"))
        assertNull(chatOfLink("https://chat/$id/main"))
        assertNull(chatOfLink(null))
    }

    @Test
    fun `a revoked or changed identity opens its trust screen, and every other link opens the chat`() {
        assertEquals(RevokedKey(first.id), trustKey(Link.Revoked, first.id))
        assertEquals(IdentityChangedKey(first.id), trustKey(Link.IdentityChanged, first.id))
        val others =
            listOf(Link.ProtocolError, Link.Replaced, Link.CannotReach, Link.NotOpen, Link.OlderDaemon, Link.Closed)
        others.forEach { assertNull(trustKey(it, first.id), "$it") }
    }

    @Test
    fun `the chat on top is kept, and the one kept is restored while its instance is paired`() =
        runTest(main) {
            val settings = settings()
            val records = MutableStateFlow(listOf(first, second))
            val navigator = AppNavigator(settings, records)
            navigator.restore(link = null)
            navigator.open(ChatKey(first.id, "main"))
            navigator.open(InstanceKey(first.id))
            advanceUntilIdle()
            // DataStore writes on threads of its own; the kept chat is awaited, not polled.
            assertEquals(ChatRef(first.id, "main"), settings.settings.first { it.lastChat != null }.lastChat)

            val again = AppNavigator(settings, records)
            again.restore(link = null)
            assertEquals(listOf(ChatKey(first.id, "main")), again.above.value)
            again.back()
            advanceUntilIdle()
            assertNull(settings.settings.first { it.lastChat == null }.lastChat)
        }

    @Test
    fun `a link wins over the chat kept, and a chat whose instance is gone is neither restored nor shown`() =
        runTest(main) {
            val settings = settings()
            settings.setLastChat(ChatRef(first.id, "main"))
            val records = MutableStateFlow(listOf(first, second))
            val linked = AppNavigator(settings, records)
            linked.restore(link = ChatKey(second.id, "main"))
            assertEquals(listOf(ChatKey(second.id, "main")), linked.above.value)

            records.value = listOf(second)
            val restored = AppNavigator(settings, records)
            restored.restore(link = null)
            assertEquals(emptyList<Any>(), restored.above.value)
            restored.showChat(ChatKey(first.id, "main"))
            assertEquals(emptyList<Any>(), restored.above.value)
        }

    @Test
    fun `a screen whose instance is unpaired leaves the stack, and a chat gives way to its trust state`() =
        runTest(main) {
            val records = MutableStateFlow(listOf(first, second))
            val navigator = AppNavigator(settings(), records)
            navigator.restore(link = null)
            navigator.open(ChatKey(second.id, "main"))
            navigator.replace(ChatKey(second.id, "main"), RevokedKey(second.id))
            navigator.open(AppLockKey)
            navigator.open(InstanceKey(first.id))
            advanceUntilIdle()
            records.value = listOf(first)
            advanceUntilIdle()
            assertEquals(listOf(AppLockKey, InstanceKey(first.id)), navigator.above.value)
            navigator.toRoot()
            assertEquals(emptyList<Any>(), navigator.above.value)
        }
}
