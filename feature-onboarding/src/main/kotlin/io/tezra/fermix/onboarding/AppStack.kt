package io.tezra.fermix.onboarding

import androidx.navigation3.runtime.NavKey

/**
 * The app's back stack (design section 13.2): its root, Welcome until a Fermix is [paired] (for the app,
 * also while one a restore dropped waits to be paired again) and [chats] from then on, the app's own screens
 * [above] the Chats list, and onboarding's screens on top. The root turns to the Chats list under Paired,
 * as the record is stored before Paired shows, so the steps after it lead back to the list.
 */
fun appBackStack(
    paired: Boolean,
    chats: NavKey,
    above: List<NavKey>,
    onboarding: List<OnboardingKey>,
): List<NavKey> {
    require(chats !is OnboardingKey) { "$chats is onboarding's, not the Chats list" }
    require(above.none { it is OnboardingKey }) { "onboarding's screens are above the app's" }
    require(paired || above.isEmpty()) { "the app's screens stand on the Chats list, which a paired phone has" }
    return listOf(if (paired) chats else OnboardingKey.Welcome) + above + onboarding
}

/** Whether the window showing [top] is kept out of screenshots and the recents: every onboarding screen. */
fun securesWindow(top: NavKey): Boolean = top is OnboardingKey

/**
 * Whether [top] lies dark under the system bars in both modes, so that their icons are white over it: the
 * scan's camera, full bleed, as the visual canon draws its `.phone.bleed`. Every other screen is on the
 * theme's canvas, and its bars follow the theme.
 */
fun darkUnderBars(top: NavKey): Boolean = top == OnboardingKey.Scan
