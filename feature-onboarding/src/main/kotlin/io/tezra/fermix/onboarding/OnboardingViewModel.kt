package io.tezra.fermix.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.ReticleMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    val ceremony = CeremonyDriver(parts, viewModelScope, uiState, stack) { key -> set(stackOf(key)) }

    /** "Paste a pairing link"'s sheet, over the screen that opened it. */
    val paste = PasteSheetModel(stack, ceremony)

    /** The Fermix a pairing just added, as onboarding leaves for the Chats list, whose row rises in there. */
    val arrival = ArrivingFermix()

    /** Whether the system's back gesture is under way, as the screens that hold a wait follow it (FollowBackSwipe). */
    val backSwipe = BackSwipe()

    /**
     * The one wait that hands the top screen's work on: the scan's 250 ms between a Fermix code and the ceremony
     * ([found]), or Notifications' check after a grant ([notificationsAnswered]). A change of the top screen, back
     * among them, ends it.
     */
    private var waiting: Job? = null

    /** The name each screen's entry goes by in NavDisplay, anew each time the screen is pushed. */
    val entryNames = EntryNames()

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
        set(stackOf(if (passed) OnboardingKey.Pair else OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)))
    }

    /** "Scan the code" on Pair. */
    fun scan() {
        stackState.value.requireTop(OnboardingKey.Pair)
        uiState.update { it.copy(scanRefusal = null) }
        set(stackOf(OnboardingKey.Scan))
    }

    /**
     * A Fermix code the camera read on Scan, [link]: the reticle locks onto it ([OnboardingUi.scanFound]), and the
     * ceremony takes it 250 ms later (the M51 update's 7.4), the camera's reads meanwhile not taken. The wait is held
     * here, not in the screen, so that a rotation in it keeps the code; anything that takes Scan off the top, back
     * among them, ends it and zeroes the link's secret, and no ceremony starts.
     */
    fun found(link: LinkOutcome.Link) {
        stackState.value.requireTop(OnboardingKey.Scan)
        check(!uiState.value.scanFound) { "the scan holds one code at a time" }
        uiState.update { it.copy(scanFound = true, scanRefusal = null) }
        waiting =
            viewModelScope.launch {
                lockOnto(link, backSwipe)
                waiting = null
                ceremony.onLink(link)
            }
    }

    /**
     * Back, the gesture or the screen's own button, Verify's "Cancel" among them: the top screen goes, and a
     * pairing whose screens are no longer shown ends. Back from the last screen of a stored pairing leaves
     * onboarding for the Chats list as its "Continue" would, the new Fermix's row rising in there.
     */
    fun back() {
        val top = checkNotNull(stackState.value.lastOrNull()) { "back from the root is the system's" }
        val below = stackOf(top).dropLast(1)
        if (below.isEmpty() && showsStoredPairing(top)) {
            val paired = checkNotNull(uiState.value.paired) { "$top shows a stored pairing" }
            arrival.arriving(paired.record.id)
        }
        set(below)
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
        if (paired.needsName) set(stackOf(OnboardingKey.Name)) else afterName(paired)
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
     * says whether this Fermix notifies, then the app acts on it, a grant with the first `push_register`
     * (design section 10), at once; onboarding is over [endAfterMillis] later, after a grant the check's time (the
     * M51 update's 7.4), or at once. Back in that wait leaves sooner, the answer given all the same. It is asked
     * once; the Instance screen has the switch.
     */
    fun notificationsAnswered(
        granted: Boolean,
        endAfterMillis: Long = 0L,
    ) {
        stackState.value.requireTop(OnboardingKey.Notifications)
        require(endAfterMillis >= 0L) { "onboarding ends after the answer, not $endAfterMillis ms before it" }
        val paired = checkNotNull(uiState.value.paired) { "Notifications follow a stored pairing" }
        val answer = viewModelScope.launch { answerNotifications(parts, paired.record.id, granted) }
        waiting =
            viewModelScope.launch {
                delay(endAfterMillis)
                backSwipe.ended()
                answer.join()
                waiting = null
                arrival.arriving(paired.record.id)
                set(emptyList())
            }
    }

    override fun onCleared() {
        parts.pairingWait.value = null
    }

    /**
     * Shows [stack]. A pairing lives on Connecting, Verify and its failure screens: anywhere else it ends.
     * The pairing-wait notification has something to say on Verify alone, the paste sheet and the wait belong
     * to the screen on top, and the row "Pair again" began on is forgotten with onboarding. A code the scan
     * holds goes as the scan leaves the top, which starts its next visit, so that it searches anew, and settles
     * in anew, when it comes back; a screen pushed is named anew ([entryNames]).
     */
    private fun set(stack: List<OnboardingKey>) {
        val top = stack.lastOrNull()
        val leaving = stackState.value.lastOrNull()
        if (!showsCeremony(top)) ceremony.leave()
        if (stack.isEmpty()) uiState.update { it.copy(mergeInto = null) }
        if (top != OnboardingKey.Verify) parts.pairingWait.value = null
        if (top != leaving) paste.close()
        if (top != leaving) waiting?.cancel()
        if (leaving == OnboardingKey.Scan && top != OnboardingKey.Scan) {
            uiState.update { it.copy(scanFound = false, scanVisit = it.scanVisit + 1) }
        }
        entryNames.pushed(stack - stackState.value.toSet())
        stackState.value = stack
    }

    /** Notifications when the daemon has push; else onboarding is over, for the Chats list, its row rising in. */
    private fun afterName(paired: PairedFacts) {
        if (paired.offerNotifications) {
            set(stackOf(OnboardingKey.Notifications))
            return
        }
        arrival.arriving(paired.record.id)
        set(emptyList())
    }
}

/**
 * The Fermix a pairing just added, by its record's id ([id]), from onboarding's end until the Chats list says it has
 * taken it, as its row starts rising in there ([arrived], the M51 update's 7.4); none otherwise.
 */
class ArrivingFermix {
    private val state = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = state.asStateFlow()

    internal fun arriving(record: String) {
        require(record.isNotBlank()) { "a stored record has an id" }
        state.value = record
    }

    /** The Chats list has taken the new Fermix, and its row is rising in. */
    fun arrived() {
        state.value = null
    }
}

/**
 * Whether the system's back gesture is under way over onboarding, from its start until it is let go or cancelled, as
 * the screens that hold a wait tell it ([follow]). Navigation 3 hears a swipe only once it is let go, so a wait that
 * ended mid-swipe would change the stack under it, and the swipe let go would take back the screen the wait brought.
 * A wait ends only once the swipe has ([ended]): one let go is back, which ends the wait first, and one cancelled
 * leaves the wait to hand its work on. A swipe always ends, the system cancelling one whose window goes.
 */
class BackSwipe {
    private val underWay = MutableStateFlow(false)

    internal fun follow(swiping: Boolean) {
        underWay.value = swiping
    }

    internal suspend fun ended() {
        underWay.first { !it }
    }
}

/**
 * Waits as the reticle locks onto [link], 250 ms, and then for a back [swipe] under way to end, and zeroes the link's
 * secret when the wait is ended first.
 */
private suspend fun lockOnto(
    link: LinkOutcome.Link,
    swipe: BackSwipe,
) {
    try {
        delay(ReticleMotion.LEAVE_AFTER_MILLIS.toLong())
        swipe.ended()
    } catch (ended: CancellationException) {
        link.link.secret.fill(0)
        throw ended
    }
}

/** The record of [instanceId] told whether it notifies, [granted], and the app acting on it (design section 10). */
private suspend fun answerNotifications(
    parts: OnboardingParts,
    instanceId: String,
    granted: Boolean,
) {
    parts.instances.update(instanceId) { it.copy(notificationsEnabled = granted) }
    val record =
        parts.instances.instances
            .first()
            .find { it.id == instanceId }
    if (record != null) parts.notifications(record, granted)
}

/**
 * The name of each onboarding screen's entry, the key its saved state goes by in NavDisplay ([of], onboardingEntries'
 * contentKey). A screen pushed onto the stack is named anew ([pushed]), unlike any name before it, in this process or
 * one before it, whose saved states the activity may still hold; it keeps its name while it stays there, and once it
 * is popped until it is pushed again, so a composition that has not yet caught up with the stack still finds it. So a
 * pairing never meets what an earlier one saved: NavDisplay forgets a popped entry's state only while it draws, and
 * nothing draws the screens behind the app lock, so screens popped there, or before a process died, would leave their
 * played moments and Verify's told seconds to the next pairing under the screen's own key. A screen named before it
 * was ever pushed, as only a test builds one, is named as it is asked for.
 */
class EntryNames {
    private val names = mutableMapOf<OnboardingKey, String>()

    fun of(key: OnboardingKey): String = names.getOrPut(key) { anew(key) }

    internal fun pushed(keys: Collection<OnboardingKey>) {
        for (key in keys) names[key] = anew(key)
    }

    private fun anew(key: OnboardingKey): String = "$key ${UUID.randomUUID()}"
}

private fun showsCeremony(top: OnboardingKey?): Boolean =
    top == OnboardingKey.Connecting || top == OnboardingKey.Verify || top is OnboardingKey.Failure

private fun showsStoredPairing(top: OnboardingKey): Boolean =
    top == OnboardingKey.Paired || top == OnboardingKey.Name || top == OnboardingKey.Notifications

private fun List<OnboardingKey>.requireTop(key: OnboardingKey) {
    require(lastOrNull() == key) { "$key is not showing; ${lastOrNull()} is" }
}
