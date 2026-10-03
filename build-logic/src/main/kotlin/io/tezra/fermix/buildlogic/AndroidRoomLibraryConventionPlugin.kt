package io.tezra.fermix.buildlogic

import androidx.room.gradle.RoomExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** Where a Room module keeps its exported schema, one JSON file per database version, committed. */
internal const val ROOM_SCHEMA_DIRECTORY = "schemas"

/** The task that copies this machine's SQLite library out of sqlite-bundled-jvm for the JVM tests. */
private const val EXTRACT_SQLITE_NATIVE = "extractSqliteNative"

/**
 * A library module with a Room database (design section 12.1: Room 2.8, no SQLCipher): the library
 * convention, Room's compiler under KSP, Room's Gradle plugin exporting each schema version into
 * [ROOM_SCHEMA_DIRECTORY] for the migrations to come, and the bundled SQLite driver. The module's JVM
 * tests run Room's Android runtime on that driver too, with this machine's build of the same SQLite
 * (BundledSqliteNative.kt), so they need neither Robolectric nor a device. The data module applies it.
 */
class AndroidRoomLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(AndroidLibraryConventionPlugin::class.java)
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("androidx.room")

            extensions.getByType<RoomExtension>().schemaDirectory(layout.projectDirectory.dir(ROOM_SCHEMA_DIRECTORY))
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            // A module's database is a RoomDatabase in its public API, so callers compile against Room.
            dependencies.addProvider("api", libs.library("androidx-room-runtime"))
            dependencies.addProvider("implementation", libs.library("androidx-sqlite-bundled"))
            dependencies.addProvider("ksp", libs.library("androidx-room-compiler"))

            val native = registerSqliteNative(libs)
            val android = extensions.getByType<LibraryExtension>()
            // A function value with a named parameter, not an Action lambda (Signing.kt).
            android.testOptions.unitTests.all { test -> loadSqliteNative(test, native) }
        }
    }
}

/**
 * [ExtractSqliteNative] over sqlite-bundled-jvm, which Gradle resolves and checks against its sha256: a Room
 * module's, and the app's, whose Robolectric tests draw the Chats list over the data module's databases.
 */
internal fun Project.registerSqliteNative(libs: VersionCatalog): TaskProvider<ExtractSqliteNative> {
    val desktopSqlite =
        configurations.detachedConfiguration(dependencies.create(libs.library("androidx-sqlite-bundled-jvm").get()))
    desktopSqlite.isTransitive = false
    val osName = providers.systemProperty("os.name")
    val osArch = providers.systemProperty("os.arch")
    val output = layout.buildDirectory.dir("sqlite-native")
    return tasks.register<ExtractSqliteNative>(EXTRACT_SQLITE_NATIVE) {
        group = "verification"
        description = "Copies this machine's SQLite library out of sqlite-bundled-jvm for the JVM tests."
        jar.from(desktopSqlite)
        this.osName.set(osName)
        this.osArch.set(osArch)
        outputDirectory.set(output)
    }
}

/** A JVM test task loads the bundled driver's library from what [extract] wrote. */
internal fun loadSqliteNative(
    test: Test,
    extract: TaskProvider<ExtractSqliteNative>,
) {
    test.inputs
        .files(extract)
        .withPropertyName("sqliteNative")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
    val fileName =
        extract.flatMap { task ->
            task.osName.zip(task.osArch) { os, arch -> sqliteNativeFor(os, arch).fileName }
        }
    test.jvmArgumentProviders.add(SqliteNativeProperties(extract.flatMap { task -> task.outputDirectory }, fileName))
}
