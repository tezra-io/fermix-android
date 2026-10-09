package io.tezra.fermix.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.session.InstanceFacts
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Onboarding's end naming the Fermix it added (the M51 update's 7.4), whose row rises in on the Chats list until the
 * list says it has taken it: after Notifications' answer, straight from Paired for a daemon without push, on Back from
 * Paired or Notifications, and never on Back before a pairing is stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingArrivalTest {
    @TempDir
    lateinit var directory: File

    private val main = StandardTestDispatcher()

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    /** Onboarding over a fake ceremony and real records, its ViewModel kept in [viewModels], with a pasted link. */
    private inner class Rig(
        scope: TestScope,
    ) {
        private val starter = FakeStarter()
        private val store = instanceStore(directory, scope.backgroundScope)
        private val parts =
            OnboardingParts(
                gate = { GateResult.Ok },
                pairings = starter,
                identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
                instances = store,
                network = MutableStateFlow(NetworkFacts(1L, false, false, false)),
                pairingDispatcher = main,
                pairingWait = MutableStateFlow(null),
                handover = FakeHandover(store.instances),
                notifications = { _, _ -> },
                now = { PAIRED_AT },
            )
        val viewModels = ViewModelStore()
        val model: OnboardingViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { OnboardingViewModel(parts) } })
                .get(OnboardingViewModel::class)

        /** Paired, as the ceremony reaches it from a pasted link the daemon [approved]. */
        suspend fun paired(
            scope: TestScope,
            approved: InstanceFacts,
        ) {
            model.getStarted()
            model.ceremony.onLink(readLink(linkText()))
            scope.runCurrent()
            starter.control.state.value = PairingState.Approved(approved, idleSession(scope.backgroundScope))
            shows(OnboardingKey.Paired)
        }

        suspend fun shows(vararg keys: OnboardingKey) = model.stack.first { it == keys.toList() }
    }

    @Test
    fun `leaving onboarding names the new Fermix, whose row rises in on the Chats list, until it has`() =
        runTest(main) {
            val rig = Rig(this)
            assertNull(rig.model.arrival.id.value)
            rig.paired(this, facts(gateway = 1))
            rig.model.continueFromPaired()
            assertNull(rig.model.arrival.id.value)
            rig.model.notificationsAnswered(granted = false)
            rig.shows()
            assertEquals(facts(gateway = 1).id, rig.model.arrival.id.value)
            rig.model.arrival.arrived()
            assertNull(rig.model.arrival.id.value)
            rig.viewModels.clear()
        }

    @Test
    fun `back from Paired leaves for the Chats list naming the new Fermix`() =
        runTest(main) {
            val rig = Rig(this)
            rig.paired(this, facts(gateway = 1))
            rig.model.back()
            rig.shows()
            assertEquals(facts(gateway = 1).id, rig.model.arrival.id.value)
            rig.viewModels.clear()
        }

    @Test
    fun `back from Notifications leaves for the Chats list naming the new Fermix`() =
        runTest(main) {
            val rig = Rig(this)
            rig.paired(this, facts(gateway = 1))
            rig.model.continueFromPaired()
            rig.shows(OnboardingKey.Notifications)
            assertNull(rig.model.arrival.id.value)
            rig.model.back()
            rig.shows()
            assertEquals(facts(gateway = 1).id, rig.model.arrival.id.value)
            rig.viewModels.clear()
        }

    @Test
    fun `a daemon without push names its Fermix as Paired goes straight to the root, and Back names none`() =
        runTest(main) {
            val rig = Rig(this)
            val approved = facts(gateway = 1, push = emptyList())
            rig.paired(this, approved)
            rig.model.continueFromPaired()
            assertEquals(approved.id, rig.model.arrival.id.value)
            rig.model.arrival.arrived()
            rig.model.getStarted()
            rig.model.back()
            assertNull(rig.model.arrival.id.value)
            rig.viewModels.clear()
        }
}
