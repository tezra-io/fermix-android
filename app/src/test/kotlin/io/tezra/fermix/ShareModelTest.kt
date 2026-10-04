package io.tezra.fermix

import io.tezra.fermix.chat.Shared
import io.tezra.fermix.data.Instance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private val SHARE = Share(Shared(emptyList(), "look at this", emptyList(), past = 0), shortcutId = null)

/**
 * The activity's share as it waits (design sections 13.6 and 13.7), over the gate's sight and the records as they
 * come: each share lost, dropped or put down is logged with why, and the sheet's pick lands only in a paired chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareModelTest {
    private val sight = MutableStateFlow(Sight.UNKNOWN)
    private val records = MutableStateFlow<List<Instance>>(emptyList())
    private val handed = MutableStateFlow<Share?>(null)
    private val lines = mutableListOf<String>()

    @BeforeEach
    fun mainUnconfined() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private fun model() = ShareModel(sight, records, handed) { lines += it }

    @Test
    fun `a share the entry handed over is taken once, by the activity's model, and the hand is empty again`() {
        handed.value = SHARE
        val model = model()
        assertEquals(null, handed.value)
        assertEquals(ShareState.Pending(SHARE, asked = false, behindLock = false), model.state.value)
        // A model made later, as after a rotation, finds nothing to take.
        val later = model()
        assertEquals(ShareState.None, later.state.value)
    }

    @Test
    fun `a share behind the lock, lost as the app leaves, is logged as the lock's`() {
        val model = model()
        sight.value = Sight.LOCKED
        model.take(SHARE)
        sight.value = Sight.AWAY
        assertEquals(ShareState.None, model.state.value)
        assertEquals(listOf("A share was dropped: the app left with its lock not passed"), lines)
    }

    @Test
    fun `a share that passes the lock with no Fermix the phone trusts is dropped, and logged so`() {
        val model = model()
        sight.value = Sight.LOCKED
        model.take(SHARE)
        // None paired, or each revoked or changed in identity, which shareTargetsOf leaves out: no record to go to.
        sight.value = Sight.OPEN
        assertEquals(ShareState.None, model.state.value)
        assertEquals(listOf("A share was dropped: no Fermix the phone still trusts is paired"), lines)
    }

    @Test
    fun `the sheet's pick lands only in a paired chat, and putting it down lets the share go`() {
        val model = model()
        records.value = listOf(pairedForShare(1), pairedForShare(2))
        sight.value = Sight.OPEN
        model.take(SHARE)
        assertEquals(ShareState.Pending(SHARE, asked = true, behindLock = false), model.state.value)
        model.picked(ChatKey("ab".repeat(32), "main"))
        assertEquals(ShareState.Pending(SHARE, asked = true, behindLock = false), model.state.value)
        val chat = ChatKey(pairedForShare(2).id, "main")
        model.picked(chat)
        val landing = ShareState.Landing(chat, SHARE.shared)
        assertEquals(landing, model.state.value)
        assertEquals(true, model.landed(landing))
        assertEquals(false, model.landed(landing))
        model.take(SHARE)
        model.dismissed()
        assertEquals(ShareState.None, model.state.value)
        assertEquals(listOf("A share was put down on Send to which Fermix?"), lines)
    }
}
