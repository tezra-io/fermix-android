package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import java.io.StringReader
import java.util.Properties

/** The release version: the tag without its `v`, and the code the release pull request raises. */
internal data class AppVersion(
    val name: String,
    val code: Int,
)

private val VERSION_NAME = Regex("""\d+\.\d+\.\d+""")

/** Parses `version.properties` (CI/CD design section 4.4). */
internal fun parseAppVersion(text: String): AppVersion {
    val properties = Properties()
    StringReader(text).use(properties::load)
    val name = properties.getProperty("versionName").orEmpty()
    val codeText = properties.getProperty("versionCode").orEmpty()
    val code = codeText.toIntOrNull() ?: 0
    if (!VERSION_NAME.matches(name) || code < 1) {
        throw GradleException(
            "version.properties needs versionName as MAJOR.MINOR.PATCH and versionCode as a whole number " +
                "from 1; it has versionName '$name' and versionCode '$codeText'.",
        )
    }
    return AppVersion(name, code)
}
