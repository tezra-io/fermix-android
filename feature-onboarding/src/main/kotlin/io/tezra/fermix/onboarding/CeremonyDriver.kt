package io.tezra.fermix.onboarding

import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.InstanceFacts
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.Retry
import io.tezra.fermix.transport.reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.HexFormat

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

    /** Each paired Fermix's row title by its id, as the records last said. */
    private val pairedTitles: StateFlow<Map<String, String>> =
        parts.instances.instances
            .map { records -> records.associate { it.id to it.title } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Section 9.2's question, asked about a link whose daemon this phone is paired with already. */
    val pairAgain = PairAgainQuestion(ui, onYes = { begin(it) }, onNo = { show(OnboardingKey.Pair) })

    /**
     * A link the owner scanned or pasted, on Pair, Scan or a failure screen, as [readLink] read it, after
     * ending any ceremony the owner left: a new ceremony over a link it takes, or first, for a daemon
     * this phone is paired with already, section 9.2's question; the "Older Fermix" or "Newer Fermix"
     * screen, which the link alone tells; or the scan's "That's not a Fermix pairing code." While the
     * question waits for its answer, the camera's next reads are not taken.
     */
    fun onLink(outcome: LinkOutcome) {
        val top = stack.value.lastOrNull()
        require(top == OnboardingKey.Pair || top == OnboardingKey.Scan || top is OnboardingKey.Failure) {
            "a link arrives on Pair, Scan or a failure screen, not on $top"
        }
        if (pairAgain.asking) return
        leave()
        if (outcome !is LinkOutcome.Link) return refuse(outcome)
        val paired = pairedTitle(outcome.link, pairedTitles.value, ui.value.mergeInto)
        if (paired != null) pairAgain.ask(outcome.link, paired) else begin(outcome.link)
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
     * its key and zeroes the link's secret (PairingHandle.cancel); an approved one handed its session over
     * already. Either way its scope ends, and with it anything of the attempt still running.
     */
    internal fun leave() {
        pairAgain.forget()
        val left = attempt ?: return
        attempt = null
        left.following?.cancel()
        left.stopTimer()
        scope.launch {
            if (!left.approved) left.control.cancel()
            left.scope.cancel()
        }
    }

    /**
     * A link the phone refused itself: a version's failure screen, about the host the link names, or the
     * scan's "That's not a Fermix pairing code.", with a reason the screen does not show.
     */
    private fun refuse(outcome: LinkOutcome) {
        val shown =
            when (outcome) {
                is LinkOutcome.OlderFermix -> {
                    ui.update { it.copy(host = outcome.host) }
                    OnboardingKey.Failure(FailureCase.OLDER_FERMIX)
                }

                LinkOutcome.NewerFermix -> {
                    // The link is a newer Fermix's, and names no host this app reads.
                    ui.update { it.copy(host = "") }
                    OnboardingKey.Failure(FailureCase.NEWER_FERMIX)
                }

                LinkOutcome.NotAFermixCode -> {
                    ui.update { it.copy(scanRefusal = "not a pairing link") }
                    OnboardingKey.Scan
                }

                is LinkOutcome.Invalid -> {
                    val reason = "the link's ${outcome.field} is missing, malformed or out of range"
                    ui.update { it.copy(scanRefusal = reason) }
                    OnboardingKey.Scan
                }

                is LinkOutcome.Link -> {
                    error("a link the ceremony takes is not refused")
                }
            }
        show(shown)
    }

    private fun begin(link: PairingLink) {
        val context = scope.coroutineContext
        val own = CoroutineScope(context + SupervisorJob(context.job) + parts.pairingDispatcher)
        val identity = parts.identity.copy(deviceName = ui.value.deviceName)
        val started = Attempt(parts.pairings.start(link, identity, own), own, link.name, identity.deviceName)
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
     * paired session the approval opened goes to the app's keeper of sessions with the record, in one
     * step ([SessionHandover]): it is the instance's session from then on, and no second socket is opened.
     */
    private suspend fun approve(
        started: Attempt,
        state: PairingState.Approved,
    ) {
        started.approved = true
        parts.pairingWait.value = null
        val mergeInto = ui.value.mergeInto
        started.control.commit { facts ->
            parts.handover.adopt(facts.id, state.session) { store(parts, facts, started.deviceName, mergeInto) }
        }
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

/**
 * Stores the approved [facts] as paired by [deviceName] in [parts]' records: merged into the row whose "Pair
 * again" began the pairing when [mergeTarget] allows it, else as any pairing is. Returns the key alias the
 * record replaced.
 */
private suspend fun store(
    parts: OnboardingParts,
    facts: InstanceFacts,
    deviceName: String,
    mergeInto: String?,
): String? {
    val records = parts.instances.instances.first()
    val paired = instanceOf(facts, pickTint(records), deviceName, parts.now())
    val target = mergeTarget(mergeInto, facts, records)
    return if (target != null) {
        parts.instances.merge(target, paired).keyAlias
    } else {
        parts.instances.upsert(paired)?.keyAlias
    }
}

/** One ceremony as the driver follows it, the phone's name it began with, and the "Trying Tailscale…" timer. */
private class Attempt(
    val control: PairingControl,
    val scope: CoroutineScope,
    val host: String,
    val deviceName: String,
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

/**
 * "Already paired; pair again to replace this phone's key?" (design section 9.2) about a link whose daemon
 * this phone is paired with already: the link is held, and its row's title shown in [ui], until the owner
 * answers. Yes pairs over the link ([onYes]), whose approval replaces the record and its key (section 6.1);
 * no zeroes the link's secret ([onNo] then shows Pair); leaving the screen is no answer, and zeroes it too.
 */
class PairAgainQuestion internal constructor(
    private val ui: MutableStateFlow<OnboardingUi>,
    private val onYes: (PairingLink) -> Unit,
    private val onNo: () -> Unit,
) {
    private var held: PairingLink? = null

    /** Whether the question waits for its answer; the camera's reads are not taken meanwhile. */
    internal val asking: Boolean get() = held != null

    internal fun ask(
        link: PairingLink,
        title: String,
    ) {
        check(held == null) { "section 9.2's question is asked once at a time" }
        held = link
        ui.update { it.copy(alreadyPaired = title, scanRefusal = null) }
    }

    /** The owner's answer, "Pair again" or "Cancel". */
    fun answer(yes: Boolean) {
        val link = checkNotNull(held) { "no link waits for section 9.2's question" }
        held = null
        ui.update { it.copy(alreadyPaired = null) }
        if (yes) {
            onYes(link)
        } else {
            link.secret.fill(0)
            onNo()
        }
    }

    /** A question the screens no longer show is no answer: its link's secret is zeroed. */
    internal fun forget() {
        val link = held ?: return
        held = null
        link.secret.fill(0)
        ui.update { it.copy(alreadyPaired = null) }
    }
}

/**
 * The title of the row [link]'s daemon is paired as among [titles] (by instance id), none for a daemon
 * this phone is not paired with, or for the row [mergeInto] names, whose "Pair again" asked already.
 */
private fun pairedTitle(
    link: PairingLink,
    titles: Map<String, String>,
    mergeInto: String?,
): String? {
    val id = instanceIdOf(link.gatewayPublicKey)
    return titles[id]?.takeIf { id != mergeInto }
}

/** An instance's id, as data keys its record: `sha256(gateway_pk)` in lowercase hex (design section 9.1). */
private fun instanceIdOf(gatewayPublicKey: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gatewayPublicKey))
