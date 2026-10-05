package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecSpec
import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * The version (CI/CD design section 4.4): the name is the nearest release tag without its `v`, and the code is
 * version.properties' own, which the release pull request raises.
 */
internal data class AppVersion(
    val name: String,
    val code: Int,
)

/** The versionName of a build with no release tag behind it. */
internal const val UNRELEASED_VERSION_NAME = "0.0.0-dev"

/**
 * The tags git describe takes for a release tag. A glob cannot say "digits only", so what it matches is held to
 * [RELEASE_TAG] whole; scripts/release_preflight.sh describes a tagged commit with the same glob.
 */
private const val RELEASE_TAG_GLOB = "v[0-9]*.[0-9]*.[0-9]*"
private val RELEASE_TAG = Regex("""v(\d+\.\d+\.\d+)""")

/** What describe's `--always` prints when no release tag lies behind the commit: its full id, SHA-1 or SHA-256. */
private val COMMIT_ID = Regex("""[0-9a-f]{40}|[0-9a-f]{64}""")

/** Parses version.properties, which holds versionCode alone. */
internal fun parseVersionCode(text: String): Int {
    val properties = Properties()
    StringReader(text).use(properties::load)
    val name = properties.getProperty("versionName")
    if (name != null) {
        throw GradleException(
            "version.properties holds versionCode alone: versionName is the nearest release tag without its v " +
                "(CI/CD design section 4.4), so remove versionName=$name from it.",
        )
    }
    val codeText = properties.getProperty("versionCode").orEmpty()
    val code = codeText.toIntOrNull() ?: 0
    if (code < 1) {
        throw GradleException(
            "version.properties needs versionCode as a whole number from 1; it has versionCode '$codeText'.",
        )
    }
    return code
}

/** The versionName from what `git describe` printed for the build's commit ([describeNearestReleaseTag]). */
internal fun versionNameOf(described: String): String {
    val line = described.trim()
    RELEASE_TAG.matchEntire(line)?.let { return it.groupValues[1] }
    if (COMMIT_ID.matches(line)) return UNRELEASED_VERSION_NAME
    throw GradleException(
        "The nearest tag, '$line', is not a release tag vMAJOR.MINOR.PATCH, so the build cannot take its " +
            "versionName from it (CI/CD design section 4.4). A release is a vX.Y.Z tag on main; delete the tag.",
    )
}

/**
 * Runs `git describe` for HEAD in [repository], through Gradle's providers, so that the configuration cache
 * runs it again to see whether a tag moved. It prints the nearest tag [RELEASE_TAG_GLOB] matches, or, with none
 * behind HEAD, the commit's id. Anything else git does, outside a checkout or before the first commit, fails the
 * build with git's own words.
 */
internal fun ProviderFactory.describeNearestReleaseTag(repository: File): String {
    // A function value with a named parameter, not an Action lambda (Signing.kt).
    val describe: (ExecSpec) -> Unit = { spec ->
        spec.commandLine(
            "git",
            "-C",
            repository.path,
            "describe",
            "--tags",
            "--abbrev=0",
            "--match",
            RELEASE_TAG_GLOB,
            "--always",
            "HEAD",
        )
        spec.isIgnoreExitValue = true
    }
    val output = exec(describe)
    val status = output.result.get().exitValue
    if (status != 0) {
        throw GradleException(
            "versionName is the nearest release tag, and git describe failed in $repository with status " +
                "$status: ${output.standardError.asText.get().trim()}",
        )
    }
    return output.standardOutput.asText.get()
}

/** The app's version: its name from the nearest release tag of [repository], its code from [versionProperties]. */
internal fun ProviderFactory.appVersion(
    repository: File,
    versionProperties: String,
): AppVersion = AppVersion(versionNameOf(describeNearestReleaseTag(repository)), parseVersionCode(versionProperties))
