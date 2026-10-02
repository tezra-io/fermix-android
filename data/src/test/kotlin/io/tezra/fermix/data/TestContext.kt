package io.tezra.fermix.data

import android.content.ContextWrapper
import androidx.room.Room
import io.tezra.fermix.protocol.Frame
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.decodeServerEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The Context Room's Android builder takes, on the JVM: android.jar's stubs throw for every call, so
 * anything Room asks of it that is not overridden here fails the test. It answers database paths alone,
 * which ProfileDatabases makes absolute, as the platform's own Context returns an absolute one as it is.
 */
internal object TestContext : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File = File(name)
}

/** A profile database in memory, built as ProfileDatabases builds one on disk, its queries on [queries]. */
internal fun inMemoryDatabase(queries: CoroutineDispatcher = Dispatchers.IO): ProfileDatabase =
    Room.inMemoryDatabaseBuilder(TestContext, ProfileDatabase::class.java).buildProfileDatabase(queries)

/** One vendored file's text, from contracts/mobile, which data/build.gradle.kts puts on the test classpath. */
internal fun vendored(path: String): String {
    val stream =
        checkNotNull(TestContext::class.java.getResourceAsStream("/$path")) {
            "/$path is not on the test classpath; data/build.gradle.kts puts contracts/mobile there"
        }
    return stream.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
}

/** Every server event of the vendored fixtures, decoded by core-protocol from the frame each line heads. */
internal fun fixtureServerEvents(): List<ServerEvent> =
    vendored("fixtures/server_events.jsonl")
        .lines()
        .filter { it.isNotEmpty() }
        .map { line -> decodeServerEvent(Frame(line.encodeToByteArray(), ByteArray(0)).encode()).event }
