package io.tezra.fermix.buildlogic

import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.StringReader
import java.util.Properties

// Signing keys never live in the repository (onboarding section 2.5). Each role is read from
// FERMIX_<ROLE>_* environment variables, or else from a keystore.properties file outside the tree.

/** Where keystore.properties lives, relative to the developer's home directory. */
private const val KEYSTORE_PROPERTIES = ".config/fermix-android/keystore.properties"

/** The debug tasks that write a signed artifact; they refuse to run without a development key. */
private val DEBUG_PACKAGING_TASKS = setOf("packageDebug", "bundleDebug")

internal enum class SigningRole(
    private val propertyPrefix: String,
    private val environmentPrefix: String,
) {
    DEBUG("debug", "FERMIX_DEBUG"),
    RELEASE("release", "FERMIX_RELEASE"),
    ;

    fun property(field: KeyField) = "$propertyPrefix.${field.property}"

    fun variable(field: KeyField) = "${environmentPrefix}_${field.variable}"
}

internal enum class KeyField(
    val property: String,
    val variable: String,
) {
    STORE_FILE("storeFile", "STORE_FILE"),
    STORE_PASSWORD("storePassword", "STORE_PASSWORD"),
    KEY_ALIAS("keyAlias", "KEY_ALIAS"),
    KEY_PASSWORD("keyPassword", "KEY_PASSWORD"),
}

internal data class SigningKey(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/** What the build was given: the environment, and keystore.properties with its text if it exists. */
internal class SigningInputs(
    val environment: (String) -> String?,
    val propertiesFile: File,
    val propertiesText: String?,
)

internal fun Project.readSigningInputs(): SigningInputs {
    val home =
        providers.systemProperty("user.home").orNull
            ?: throw GradleException("The user.home system property is not set.")
    val propertiesFile = File(home, KEYSTORE_PROPERTIES)
    val propertiesText = providers.fileContents(layout.projectDirectory.file(propertiesFile.path)).asText.orNull
    return SigningInputs({ name -> providers.environmentVariable(name).orNull }, propertiesFile, propertiesText)
}

/**
 * One role's key: from the environment when it sets any of the role's four values, else from
 * keystore.properties when that sets any, else none. A key described in part fails the build and
 * names what is missing; a part in the environment never falls back to the file.
 */
internal fun signingKey(
    role: SigningRole,
    inputs: SigningInputs,
): SigningKey? = keyFromEnvironment(role, inputs.environment) ?: keyFromProperties(role, inputs)

/** Debug signs with the developer's key or nothing; release signs only when a key is given. */
internal fun ApplicationExtension.configureSigning(
    debugKey: SigningKey?,
    releaseKey: SigningKey?,
) {
    // Never the SDK's generated debug key: every daemon refuses it (onboarding section 2.5).
    buildTypes.getByName("debug").signingConfig =
        debugKey?.let { key -> signingConfigs.getByName("debug").also { it.use(key) } }
    buildTypes.getByName("release").signingConfig =
        releaseKey?.let { key -> signingConfigs.create("release").also { it.use(key) } }
}

/** Without a development key, packaging a debug build fails with the one sentence that says why. */
internal fun Project.refuseDebugPackagingWithoutKey(propertiesFile: File) {
    val sentence =
        "No development key for debug builds: create ${propertiesFile.parentFile}/fermix-dev.jks and " +
            "$propertiesFile as README.md \"Developer keystore\" describes (onboarding section 2.5), " +
            "or set the FERMIX_DEBUG_* environment variables; the SDK's own debug key is never used."
    val refusal =
        tasks.register<DefaultTask>("refuseDebugPackagingWithoutKey") {
            group = "verification"
            description = "Fails because no development key is configured for debug builds."
            doLast { throw GradleException(sentence) }
        }
    // A function value with a named parameter, not an Action lambda: detekt's type resolution runs
    // without kotlin-dsl's SAM-with-receiver compiler plugin, so it would not see a lambda's receiver.
    val waitForRefusal: (Task) -> Unit = { task -> task.dependsOn(refusal) }
    tasks.named { it in DEBUG_PACKAGING_TASKS }.configureEach(waitForRefusal)
}

private fun keyFromEnvironment(
    role: SigningRole,
    environment: (String) -> String?,
): SigningKey? {
    val values = KeyField.entries.associateWith { environment(role.variable(it)) }
    if (values.values.all { it == null }) return null
    val key = completeKey(values, "The environment", role::variable)
    if (!key.storeFile.isAbsolute) {
        throw GradleException("${role.variable(KeyField.STORE_FILE)} must be an absolute path.")
    }
    return key
}

private fun keyFromProperties(
    role: SigningRole,
    inputs: SigningInputs,
): SigningKey? {
    val properties = inputs.propertiesText?.let(::parseProperties)
    val values = KeyField.entries.associateWith { properties?.getProperty(role.property(it)) }
    if (values.values.all { it == null }) return null
    val key = completeKey(values, inputs.propertiesFile.path, role::property)
    // A relative storeFile is relative to keystore.properties itself.
    return key.copy(storeFile = inputs.propertiesFile.parentFile.resolve(key.storeFile.path))
}

private fun completeKey(
    values: Map<KeyField, String?>,
    source: String,
    name: (KeyField) -> String,
): SigningKey {
    val missing = values.filterValues { it.isNullOrBlank() }.keys
    if (missing.isNotEmpty()) {
        val names = missing.joinToString(", ", transform = name)
        throw GradleException("$source describes a signing key without $names.")
    }
    val value = { field: KeyField -> values.getValue(field).orEmpty() }
    return SigningKey(
        storeFile = File(value(KeyField.STORE_FILE)),
        storePassword = value(KeyField.STORE_PASSWORD),
        keyAlias = value(KeyField.KEY_ALIAS),
        keyPassword = value(KeyField.KEY_PASSWORD),
    )
}

private fun parseProperties(text: String): Properties {
    val properties = Properties()
    StringReader(text).use(properties::load)
    return properties
}

private fun ApkSigningConfig.use(key: SigningKey) {
    storeFile = key.storeFile
    storePassword = key.storePassword
    keyAlias = key.keyAlias
    keyPassword = key.keyPassword
}
