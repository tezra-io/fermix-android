package io.tezra.fermix.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** What a failure screen's button does: its label, and [stepAfter] says where it leads. */
enum class FailureAction(
    @param:StringRes val label: Int,
) {
    SCAN_AGAIN(R.string.onboarding_action_scan_again),
    START_OVER(R.string.onboarding_action_start_over),
    TRY_AGAIN(R.string.onboarding_action_try_again),
    OPEN_TAILSCALE(R.string.onboarding_action_open_tailscale),
    OPEN_VPN_SETTINGS(R.string.onboarding_action_open_vpn_settings),
    OK(R.string.onboarding_action_ok),
    OPEN_RELEASE_PAGE(R.string.onboarding_action_open_release_page),
    LEARN_MORE(R.string.onboarding_action_learn_more),
    PASTE_LINK(R.string.onboarding_paste_link),
    TROUBLESHOOTING(R.string.onboarding_action_troubleshooting),
}

/** "Paste a pairing link" and "Troubleshooting", section 13.3's secondary actions. */
private val PASTE_OR_HELP = listOf(FailureAction.PASTE_LINK, FailureAction.TROUBLESHOOTING)

/** Where a new link cannot help (the phone, this app or the computer must change, or it is a security event). */
private val HELP_ONLY = listOf(FailureAction.TROUBLESHOOTING)

/**
 * Design section 13.3's failure table, the one place each failure's copy is chosen: its icon, a
 * one-sentence title, one sentence of body (Newer Fermix has none in the design), one primary action and
 * the secondaries. A title or body that names the computer takes the host as its format argument. The
 * visual canon draws four of the screens and fixes their icons and secondaries: Expired, Wrong machine,
 * Attestation refused and "A VPN is on"; the other icons are the canon's own glyphs. "Paste a pairing
 * link" is left out wherever a new link cannot help, as the canon leaves it out for Wrong machine and
 * Attestation refused. Can't reach's "Try again · Open Tailscale" is drawn as one primary and Open
 * Tailscale first among the secondaries. [PROTOCOL_ERROR] is not in the table: a daemon that answers what
 * the ceremony does not take, titled with section 13.9's generic "Something went wrong on {host}".
 */
enum class FailureCase(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int?,
    val primary: FailureAction,
    val secondaries: List<FailureAction>,
    val tone: FailureTone = FailureTone.QUIET,
) {
    EXPIRED(
        R.drawable.ic_onboarding_hourglass,
        R.string.onboarding_failure_expired_title,
        R.string.onboarding_failure_expired_body,
        FailureAction.SCAN_AGAIN,
        PASTE_OR_HELP,
        FailureTone.REFUSAL,
    ),
    DENIED(
        R.drawable.ic_onboarding_declined,
        R.string.onboarding_failure_denied_title,
        R.string.onboarding_failure_denied_body,
        FailureAction.START_OVER,
        PASTE_OR_HELP,
        FailureTone.REFUSAL,
    ),
    RATE_LIMITED(
        R.drawable.ic_onboarding_paused,
        R.string.onboarding_failure_rate_limited_title,
        R.string.onboarding_failure_rate_limited_body,
        FailureAction.START_OVER,
        PASTE_OR_HELP,
        FailureTone.REFUSAL,
    ),
    ANOTHER_PAIRING(
        R.drawable.ic_onboarding_clock,
        R.string.onboarding_failure_another_pairing_title,
        R.string.onboarding_failure_another_pairing_body,
        FailureAction.TRY_AGAIN,
        PASTE_OR_HELP,
        FailureTone.REFUSAL,
    ),
    CANT_REACH(
        R.drawable.ic_onboarding_unreachable,
        R.string.onboarding_failure_cant_reach_title,
        R.string.onboarding_failure_cant_reach_body,
        FailureAction.TRY_AGAIN,
        listOf(FailureAction.OPEN_TAILSCALE) + PASTE_OR_HELP,
    ),
    EXCLUDED_FROM_TAILSCALE(
        R.drawable.ic_onboarding_unlink,
        R.string.onboarding_failure_excluded_title,
        R.string.onboarding_failure_excluded_body,
        FailureAction.OPEN_TAILSCALE,
        PASTE_OR_HELP,
    ),
    VPN_HOLDS_THE_SLOT(
        R.drawable.ic_onboarding_key,
        R.string.onboarding_failure_vpn_title,
        R.string.onboarding_failure_vpn_body,
        FailureAction.OPEN_VPN_SETTINGS,
        PASTE_OR_HELP,
    ),
    WRONG_MACHINE(
        R.drawable.ic_onboarding_warning,
        R.string.onboarding_failure_wrong_machine_title,
        R.string.onboarding_failure_wrong_machine_body,
        FailureAction.START_OVER,
        HELP_ONLY,
        FailureTone.SECURITY_EVENT,
    ),
    OLDER_FERMIX(
        R.drawable.ic_onboarding_laptop,
        R.string.onboarding_failure_older_title,
        R.string.onboarding_failure_older_body,
        FailureAction.OK,
        HELP_ONLY,
        FailureTone.REFUSAL,
    ),
    NEWER_FERMIX(
        R.drawable.ic_onboarding_phone,
        R.string.onboarding_failure_newer_title,
        null,
        FailureAction.OPEN_RELEASE_PAGE,
        HELP_ONLY,
        FailureTone.REFUSAL,
    ),
    ATTESTATION_REFUSED(
        R.drawable.ic_onboarding_shield,
        R.string.onboarding_failure_attestation_refused_title,
        R.string.onboarding_failure_attestation_refused_body,
        FailureAction.LEARN_MORE,
        HELP_ONLY,
        FailureTone.REFUSAL,
    ),
    ATTESTATION_UNAVAILABLE(
        R.drawable.ic_onboarding_globe,
        R.string.onboarding_failure_attestation_unavailable_title,
        R.string.onboarding_failure_attestation_unavailable_body,
        FailureAction.TRY_AGAIN,
        PASTE_OR_HELP,
        FailureTone.REFUSAL,
    ),
    NO_SECURE_HARDWARE(
        R.drawable.ic_onboarding_lock,
        R.string.onboarding_failure_no_secure_hardware_title,
        R.string.onboarding_failure_no_secure_hardware_body,
        FailureAction.OK,
        HELP_ONLY,
    ),
    LOST_MID_WAIT(
        R.drawable.ic_onboarding_retry,
        R.string.onboarding_failure_lost_title,
        R.string.onboarding_failure_lost_body,
        FailureAction.START_OVER,
        PASTE_OR_HELP,
    ),
    PROTOCOL_ERROR(
        R.drawable.ic_onboarding_info,
        R.string.onboarding_failure_protocol_title,
        null,
        FailureAction.START_OVER,
        PASTE_OR_HELP,
    ),
    ;

    /** Section 13.3's security event, drawn red: Wrong machine alone. */
    val alert: Boolean get() = tone == FailureTone.SECURITY_EVENT

    /** Section 13.1's refusal, which plays `REJECT`. */
    val refusal: Boolean get() = tone != FailureTone.QUIET
}

/** How a failure meets the owner beyond its words (design sections 13.1 and 13.3). */
enum class FailureTone {
    /** Nothing answered, or the phone's own gate or the protocol stopped the pairing: no haptic. */
    QUIET,

    /**
     * A refusal, which plays `REJECT`: the computer said no to this phone (a `pair_denied` row, or another
     * pairing it holds), or the phone said no to the computer's version.
     */
    REFUSAL,

    /** The phone refused the computer's identity: section 13.3's security event, red, and a refusal too. */
    SECURITY_EVENT,
}
