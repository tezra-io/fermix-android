package io.tezra.fermix.onboarding

import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.Retry
import io.tezra.fermix.transport.reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * How long the Connecting screen says "Securing the line…" before Verify (design section 13.3, step 4).
 * core-session's Securing gives way to Verify with no suspension between, so the screen paces the line.
 */
const val SECURING_LINE_MILLIS = 600L

/**
 * One pairing ceremony at a time as the onboarding screens follow it (design section 13.3): it starts a
 * ceremony over the link the owner scanned or pasted, shows each of its states through [show] as the pure
 * [screenFor] maps it, paces "Securing the line…", keeps "Can't reach" for a retry, commits the record on
 * approval before Paired shows, and ends an attempt the owner leaves. Its calls come on the main thread, as
 * the ViewModel's do, in the ViewModel's [scope]; each
 * ceremony runs in a scope of its own on the parts' dispatcher, a child of [scope], so it ends at the
 * latest with the ViewModel.
 */
class CeremonyDriver internal constructor(
    private val parts: OnboardingParts,
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<OnboardingUi>,
    private val stack: StateFlow<List<OnboardingKey>>,
    private val show: (OnboardingKey) -> Unit,
) {
    private var attempt: Attempt? = null

    /**
     * A link the owner scanned or pasted, on Pair, Scan or a failure screen: a new ceremony over it, after
     * ending any the owner left. A newer link is the "Newer Fermix" screen, which the parse alone knows;
     * any other link the parse refuses is the scan's "That's not a Fermix pairing code."
     */
    fun onLink(text: String) {
        val top = stack.value.lastOrNull()
        require(top == OnboardingKey.Pair || top == OnboardingKey.Scan || top is OnboardingKey.Failure) {
            "a link arrives on Pair, Scan or a failure screen, not on $top"
        }
        leave()
        parse(text)?.let(::begin)
    }

    /** A failure screen's in-app action (design section 13.3's table): where [stepAfter] leads, or a retry. */
    fun act(
        case: FailureCase,
        action: FailureAction,
    ) {
        require(stack.value.lastOrNull() == OnboardingKey.Failure(case)) { "$case's screen is not showing" }
        when (val step = stepAfter(case, action)) {
            is FailureStep.Go -> show(step.key)
            FailureStep.Retry -> retry(checkNotNull(attempt) { "Can't reach holds its attempt for a retry" })
            FailureStep.Outside -> error("$action opens what lies outside the app, which its screen does")
        }
    }

    /**
     * Ends the attempt the screens no longer show: a ceremony not yet approved is cancelled, which deletes
     * its key and zeroes the link's secret (PairingHandle.cancel); an approved one closed its session
     * already. Either way its scope ends, and with it anything of the attempt still running.
     */
    internal fun leave() {
        val left = attempt ?: return
        attempt = null
        left.following?.cancel()
        left.stopTimer()
        scope.launch {
            if (!left.approved) left.control.cancel()
            left.scope.cancel()
        }
    }

    private fun parse(text: String): PairingLink? =
        try {
            PairingLink.parse(text)
        } catch (expected: ProtocolException.NewerLinkVersion) {
            // A refusal that is the screen itself: the link is a newer Fermix's, and names no host this app reads.
            ui.update { it.copy(host = "") }
            show(OnboardingKey.Failure(FailureCase.NEWER_FERMIX))
            null
        } catch (refused: ProtocolException) {
            ui.update { it.copy(scanRefusal = refused.message) }
            show(OnboardingKey.Scan)
            null
        }

    private fun begin(link: PairingLink) {
        val context = scope.coroutineContext
        val own = CoroutineScope(context + SupervisorJob(context.job) + parts.pairingDispatcher)
        val identity = parts.identity.copy(deviceName = ui.value.deviceName)
        val started = Attempt(parts.pairings.start(link, identity, own), own, link.name)
        attempt = started
        ui.update { it.copy(host = link.name, scanRefusal = null, connecting = ConnectingPhase.REACHING) }
        show(OnboardingKey.Connecting)
        started.following = scope.launch { started.control.state.collect { state -> follow(started, state) } }
    }

    private suspend fun follow(
        started: Attempt,
        state: PairingState,
    ) {
        val previous = started.latest
        started.latest = state
        when (state) {
            is PairingState.Approved -> approve(started, state)
            is PairingState.Verify -> verify(started, state, paced = previous !is PairingState.Verify)
            is PairingState.Ended -> showScreen(state, reachingLong = false)
            else -> connecting(started, state)
        }
        // A failure screen, or the scan for a link refused on the phone, ends the attempt; "Can't reach" keeps it.
        if (state is PairingState.Ended && state !is PairingState.CannotReach) leave()
    }

    /** Validating, Reaching, Checking and Securing: the Connecting screen's line (section 13.3, step 4). */
    private fun connecting(
        started: Attempt,
        state: PairingState,
    ) {
        // A retry races again, and the 4 s count again with it.
        if (state == PairingState.Validating) started.stopTimer()
        if (state is PairingState.Reaching) {
            started.timeReaching(scope) {
                val latest = started.latest
                if (latest is PairingState.Reaching) showScreen(latest, reachingLong = true)
            }
        }
        showScreen(state, started.reachingLong)
    }

    /**
     * Verify, and the pairing-wait notification's facts. Coming from the Connecting screen, its third line,
     * "Securing the line…", shows first for [SECURING_LINE_MILLIS], as no state of the ceremony holds it.
     */
    private suspend fun verify(
        started: Attempt,
        state: PairingState.Verify,
        paced: Boolean,
    ) {
        if (paced) {
            showScreen(PairingState.Securing, reachingLong = false)
            delay(SECURING_LINE_MILLIS)
        }
        showScreen(state, reachingLong = false)
        parts.pairingWait.value = PairingWait(started.host, state.expiresAt)
    }

    /**
     * `pair_approved`: the record is stored, with an auto-picked tint (design section 9.2), and only then
     * does Paired show; the commit deletes the key of a record the pairing replaced (section 6.1). The
     * paired session the approval handed over is closed once the record is stored: nothing here shows its
     * rows, and a session left running would reconnect in the background, against section 12.5. The Chats
     * list (a later change) opens the instance's session itself.
     */
    private suspend fun approve(
        started: Attempt,
        state: PairingState.Approved,
    ) {
        started.approved = true
        parts.pairingWait.value = null
        started.control.commit { facts ->
            val tint = pickTint(parts.instances.instances.first())
            parts.instances.upsert(instanceOf(facts, tint))?.keyAlias
        }
        state.session.close()
        val all = parts.instances.instances.first()
        val record = all.single { it.id == state.facts.id }
        // Step 7 is asked for each Fermix whose daemon has push; a phone that allows notifications answers at once.
        val offer = PushPlatform.FCM in state.facts.pushPlatforms
        ui.update { it.copy(paired = PairedFacts(record, all - record, needsName(record, all), offer)) }
        showScreen(state, reachingLong = false)
    }

    /** [state]'s screen as [screenFor] gives it, a "Can't reach" read through section 5.2's network facts. */
    private fun showScreen(
        state: PairingState,
        reachingLong: Boolean,
    ) {
        val tried =
            (state as? PairingState.CannotReach)
                ?.failures
                ?.keys
                ?.toList()
                .orEmpty()
        val (key, shown) = screenFor(state, ui.value, reachability(parts.network.value, tried), reachingLong)
        ui.value = shown
        show(key)
    }

    /** "Try again" after "Can't reach": the held link races again, or the scan when the handle refuses. */
    private fun retry(held: Attempt) {
        scope.launch {
            if (held.control.retry() == Retry.REFUSED) show(OnboardingKey.Scan)
        }
    }
}

/** One ceremony as the driver follows it, with the timer behind "Trying Tailscale…". */
private class Attempt(
    val control: PairingControl,
    val scope: CoroutineScope,
    val host: String,
) {
    var following: Job? = null
    var latest: PairingState = PairingState.Validating
    var approved = false
    var reachingLong = false
        private set
    private var timer: Job? = null

    /** Reaching began: after [TRYING_TAILSCALE_AFTER_MILLIS] of it, [onLong]; once per race. */
    fun timeReaching(
        scope: CoroutineScope,
        onLong: () -> Unit,
    ) {
        if (timer != null) return
        timer =
            scope.launch {
                delay(TRYING_TAILSCALE_AFTER_MILLIS)
                reachingLong = true
                onLong()
            }
    }

    fun stopTimer() {
        timer?.cancel()
        timer = null
        reachingLong = false
    }
}
