package io.tezra.fermix.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri

private const val TAG = "FermixOnboarding"

/** The Tailscale app's package, which the manifest's `<queries>` lets this app see. */
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Tailscale's Play listing, for a phone without the app. */
private const val TAILSCALE_LISTING = "https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE"

/** A failure screen's action that leads out of the app (design section 13.3's table). */
fun openOutside(
    context: Context,
    action: FailureAction,
) {
    when (action) {
        FailureAction.OPEN_TAILSCALE -> start(context, tailscale(context))
        FailureAction.OPEN_VPN_SETTINGS -> start(context, Intent(Settings.ACTION_VPN_SETTINGS))
        FailureAction.OPEN_RELEASE_PAGE -> openPage(context, R.string.onboarding_url_release)
        FailureAction.LEARN_MORE -> openPage(context, R.string.onboarding_url_learn_more)
        FailureAction.TROUBLESHOOTING -> openPage(context, R.string.onboarding_url_troubleshooting)
        else -> throw IllegalArgumentException("$action stays in the app")
    }
}

/** Opens the page strings.xml holds at [url] in the browser. */
fun openPage(
    context: Context,
    @StringRes url: Int,
) {
    start(context, Intent(Intent.ACTION_VIEW, context.getString(url).toUri()))
}

private fun tailscale(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        ?: Intent(Intent.ACTION_VIEW, TAILSCALE_LISTING.toUri())

/**
 * Starts [intent] from the screen. A phone with nothing to open it, no browser, say, has the action do
 * nothing, which is logged: the screen stays as it was.
 */
private fun start(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent)
    } catch (nothing: ActivityNotFoundException) {
        Log.w(TAG, "nothing on this phone opens ${intent.action} ${intent.data}", nothing)
    }
}
