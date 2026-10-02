package io.tezra.fermix.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.zip.ZipFile

// A Room module's JVM tests run Room's Android runtime on the bundled SQLite driver. That driver's
// Android build loads libsqliteJni from the APK, which the JVM does not have, unless these two system
// properties name a library file; sqlite-bundled-jvm, the same SQLite built for desktops, carries one
// for each desktop it supports, with the same JNI entry points.

private const val PATH_PROPERTY = "androidx.sqlite.driver.bundled.path"
private const val NAME_PROPERTY = "androidx.sqlite.driver.bundled.name"

/** Each desktop sqlite-bundled-jvm carries a native library for, as `natives/<os>_<arch>`, and the file. */
private val LIBRARIES =
    mapOf(
        "linux_x64" to "libsqliteJni.so",
        "linux_arm64" to "libsqliteJni.so",
        "osx_x64" to "libsqliteJni.dylib",
        "osx_arm64" to "libsqliteJni.dylib",
        "windows_x64" to "sqliteJni.dll",
    )

/** Where in sqlite-bundled-jvm a desktop's native library is, and its file name. */
internal data class SqliteNative(
    val directory: String,
    val fileName: String,
)

/** The native library for the JVM's `os.name` and `os.arch`; a desktop the jar has none for fails. */
internal fun sqliteNativeFor(
    osName: String,
    osArch: String,
): SqliteNative {
    val os =
        when {
            osName.startsWith("Linux") -> "linux"
            osName.startsWith("Mac") -> "osx"
            osName.startsWith("Windows") -> "windows"
            else -> "unknown"
        }
    val arch =
        when (osArch) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> "unknown"
        }
    val platform = "${os}_$arch"
    val fileName =
        LIBRARIES[platform] ?: throw GradleException(
            "androidx.sqlite:sqlite-bundled-jvm has no native library for $osName on $osArch, so the JVM tests " +
                "of a Room module cannot run on this machine.",
        )
    return SqliteNative("natives/$platform", fileName)
}

/** Copies this machine's native library out of sqlite-bundled-jvm, as Gradle resolved and verified it. */
@DisableCachingByDefault(because = "It copies one file out of a jar, which is quicker than the cache.")
abstract class ExtractSqliteNative : DefaultTask() {
    /** sqlite-bundled-jvm's jar, alone. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: ConfigurableFileCollection

    @get:Input
    abstract val osName: Property<String>

    @get:Input
    abstract val osArch: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val native = sqliteNativeFor(osName.get(), osArch.get())
        val target = File(outputDirectory.get().asFile, native.fileName)
        ZipFile(jar.singleFile).use { zip -> copyEntry(zip, "${native.directory}/${native.fileName}", target) }
    }

    private fun copyEntry(
        zip: ZipFile,
        path: String,
        target: File,
    ) {
        val entry = zip.getEntry(path) ?: throw GradleException("${jar.singleFile.name} holds no $path.")
        zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }
}

/** The two system properties that point the bundled driver at the library [ExtractSqliteNative] wrote. */
internal class SqliteNativeProperties(
    // The directory differs from machine to machine; the test task takes the library itself as an input.
    @get:Internal val directory: Provider<Directory>,
    @get:Internal val fileName: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> =
        listOf("-D$PATH_PROPERTY=${directory.get().asFile.path}", "-D$NAME_PROPERTY=${fileName.get()}")
}
