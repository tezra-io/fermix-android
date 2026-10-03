package io.tezra.fermix.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.attest.GateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Onboarding from Welcome to Notifications (design section 13.3), for the screens of the app's back stack:
 * [stack] is onboarding's part of it, above the app's root, and [ui] what the screens show. The pairing
 * itself is [ceremony]'s. It lives as long as the activity it serves, across a rotation, a fold or a
 * resize (section 13.11, rule 3), so the pairing countdown and the state survive them; a process that
 * dies loses the pairing, as its link's secret was never written anywhere.
 */
class OnboardingViewModel(
    private val parts: OnboardingParts,
) : ViewModel() {
    private val stackState = MutableStateFlow<List<OnboardingKey>>(emptyList())

    /** Onboarding's screens above the app's root, the top one showing; empty shows the root alone. */
    val stack: StateFlow<List<OnboardingKey>> = stackState.asStateFlow()

    private val uiState = MutableStateFlow(OnboardingUi(deviceName = parts.identity.deviceName))
    val ui: StateFlow<OnboardingUi> = uiState.asStateFlow()

    /** The link and the failure screens' actions: one ceremony at a time. */
    val ceremony = CeremonyDriver(parts, viewModelScope, uiState, stack, ::show)

    /** "Paste a pairing link"'s sheet, over the screen that opened it. */
    val paste = PasteSheetModel(stack, ceremony)

    /**
     * "Get started" on Welcome (section 13.3, step 1), and as it, "Add Fermix" from the Chats list, its
     * empty state or the app shortcut (section 9.4): the hardware gate of section 6.1, then Pair, or the one
     * screen that says why this phone cannot pair. "Pair again" on a Revoked or Identity-changed screen
     * (sections 9.2 and 9.4) names its row, [mergeInto], which the approved pairing merges into when
     * [mergeTarget] allows it.
     */
    fun getStarted(mergeInto: String? = null) {
        require(stackState.value.isEmpty()) { "a pairing starts from the app's root" }
        require(mergeInto == null || mergeInto.isNotBlank()) { "Pair again names its row" }
        uiState.update { it.copy(mergeInto = mergeInto) }
        val passed = parts.gate() == GateResult.Ok
        show(if (passed) OnboardingKey.Pair else OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE))
    }

    /** "Scan the code" on Pair. */
    fun scan() {
        stackState.value.requireTop(OnboardingKey.Pair)
        uiState.update { it.copy(scanRefusal = null) }
        show(OnboardingKey.Scan)
    }

    /**
     * Back, the gesture or the screen's own button, Verify's "Cancel" among them: the top screen goes, and a
     * pairing whose screens are no longer shown ends.
     */
    fun back() {
        val top = checkNotNull(stackState.value.lastOrNull()) { "back from the root is the system's" }
        set(stackOf(top).dropLast(1))
    }

    /** Names the phone for the pairings to come (the rename sheet on Pair), a name `pair_request` can carry. */
    fun rename(deviceName: String) {
        stackState.value.requireTop(OnboardingKey.Pair)
        require(deviceNameRefusal(deviceName) == null) { "pair_request cannot carry the name '$deviceName'" }
        uiState.update { it.copy(deviceName = deviceName) }
    }

    /** "Continue" on Paired: the name section 9.2 asks for, then notifications, then the Chats list. */
    fun continueFromPaired() {
        stackState.value.requireTop(OnboardingKey.Paired)
        val paired = checkNotNull(uiState.value.paired) { "Paired shows a stored pairing" }
        if (paired.needsName) show(OnboardingKey.Name) else afterName(paired)
    }

    /** "Continue" on Name with [nickname], which the screen holds to data's rename rule before it offers it. */
    fun name(nickname: String) {
        stackState.value.requireTop(OnboardingKey.Name)
        val paired = checkNotNull(uiState.value.paired) { "Name names a stored pairing" }
        viewModelScope.launch {
            val refusal = parts.instances.rename(paired.record.id, nickname)
            check(refusal == null) { "the Name screen offered a nickname the rule refuses: $refusal" }
            afterName(paired)
        }
    }

    /**
     * "Allow notifications" answered by the system's prompt, or "Not now" (section 13.3, step 7): the record
     * says whether this Fermix notifies, and onboarding is over. It is asked once; the Instance screen has
     * the switch.
     */
    fun notificationsAnswered(granted: Boolean) {
        stackState.value.requireTop(OnboardingKey.Notifications)
        val paired = checkNotNull(uiState.value.paired) { "Notifications follow a stored pairing" }
        viewModelScope.launch {
            parts.instances.update(paired.record.id) { it.copy(notificationsEnabled = granted) }
            set(emptyList())
        }
    }

    override fun onCleared() {
        parts.pairingWait.value = null
    }

    private fun show(key: OnboardingKey) = set(stackOf(key))

    /**
     * Shows [stack]. A pairing lives on Connecting, Verify and its failure screens: anywhere else it ends.
     * The pairing-wait notification has something to say on Verify alone, the paste sheet belongs to the
     * screen that opened it, and the row "Pair again" began on is forgotten with onboarding.
     */
    private fun set(stack: List<OnboardingKey>) {
        val top = stack.lastOrNull()
        if (!showsCeremony(top)) ceremony.leave()
        if (stack.isEmpty()) uiState.update { it.copy(mergeInto = null) }
        if (top != OnboardingKey.Verify) parts.pairingWait.value = null
        if (top != stackState.value.lastOrNull()) paste.close()
        stackState.value = stack
    }

    private fun afterName(paired: PairedFacts) {
        if (paired.offerNotifications) show(OnboardingKey.Notifications) else set(emptyList())
    }
}

private fun showsCeremony(top: OnboardingKey?): Boolean =
    top == OnboardingKey.Connecting || top == OnboardingKey.Verify || top is OnboardingKey.Failure

private fun List<OnboardingKey>.requireTop(key: OnboardingKey) {
    require(lastOrNull() == key) { "$key is not showing; ${lastOrNull()} is" }
}
