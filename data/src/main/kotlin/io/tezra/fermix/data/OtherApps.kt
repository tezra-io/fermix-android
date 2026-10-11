package io.tezra.fermix.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether a URI of [scheme] and [authority], as android.net.Uri parses them, is another app's for the app to read
 * (AGENTS.md: a string from another app never names a path or picks what the app reads): a `content:` URI whose
 * authority, without the `user@` prefix the ContentResolver strips up to its last `@`, names a provider, and none of
 * the app's own package ([own], asked only of such a URI), whose checks the app would pass with its own rights. A
 * `file:` URI, which the resolver opens as a path, and any other scheme are none, a scheme compared exactly, as the
 * ContentResolver compares it: `CONTENT:` is none. The one rule the chat's landings (mayRead) and the avatar's photo
 * (avatarSource) hold to, kept here where both reach it.
 */
fun isOtherAppsContent(
    scheme: String?,
    authority: String?,
    own: (String) -> Boolean,
): Boolean {
    val name = authority?.substringAfterLast('@').orEmpty()
    return scheme == "content" && name.isNotEmpty() && !own(name)
}

/**
 * Whether [authority] names a provider of the app's own package, which the app reads with its own rights: one its
 * manifest declares, each provider's authorities split as the manifest lists them.
 */
fun ownsProvider(
    context: Context,
    authority: String,
): Boolean {
    val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong())
    val providers =
        context.packageManager
            .getPackageInfo(context.packageName, flags)
            .providers
            .orEmpty()
    return providers.any { provider -> authority in provider.authority.orEmpty().split(';') }
}

/** A URI as a refusal names it: its scheme and its authority, never its path, which can say what the phone holds. */
fun outsideOf(
    scheme: String?,
    authority: String?,
): String = "$scheme URI of ${authority.orEmpty().ifEmpty { "no authority" }}"
