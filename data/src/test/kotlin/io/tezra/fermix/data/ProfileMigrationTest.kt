package io.tezra.fermix.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MessageKind
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceLocation
import io.tezra.fermix.protocol.ReplyBlock
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Version 1's exported schema, which data/build.gradle.kts puts on the test classpath with the module's schemas. */
private const val VERSION_ONE = "/io.tezra.fermix.data.ProfileDatabase/1.json"

/** The placeholder Room's exported schema writes for an entity's table in its statements. */
private const val TABLE_NAME = "\${TABLE_NAME}"

/**
 * What version 1 kept, as it wrote it: the owner's question and the agent's answer with a link preview, both whole,
 * a reply sealed before its row came, the cursors and the chat's draft.
 */
private val VERSION_ONE_ROWS =
    listOf(
        "INSERT INTO timeline (server_seq, whole, role, kind, ts, content, client_msg_id, in_reply_to, media_refs, " +
            "metadata, link_previews, truncated, turn_id, route, row_json) VALUES (11, 1, 'user', 'text', " +
            "'2026-10-15T09:00:00Z', 'Is there a guide for task timeouts?', 'm-11', NULL, '[]', NULL, NULL, NULL, " +
            "NULL, NULL, '{\"server_seq\":11,\"role\":\"user\",\"content\":\"Is there a guide for task timeouts?\"," +
            "\"ts\":\"2026-10-15T09:00:00Z\",\"media_refs\":[],\"kind\":\"text\",\"client_msg_id\":\"m-11\"}')",
        "INSERT INTO timeline (server_seq, whole, role, kind, ts, content, client_msg_id, in_reply_to, media_refs, " +
            "metadata, link_previews, truncated, turn_id, route, row_json) VALUES (12, 1, 'assistant', 'text', " +
            "'2026-10-15T09:00:04Z', 'The guide is on hexdocs.', NULL, 'm-11', '[]', '{\"turn_id\":\"turn-m-11\"}', " +
            "'[{\"url\":\"https://hexdocs.pm/elixir/Task.html\",\"site\":\"hexdocs.pm\",\"title\":\"Task\"}]', NULL, " +
            "NULL, NULL, '{\"server_seq\":12,\"role\":\"assistant\",\"content\":\"The guide is on hexdocs.\"," +
            "\"ts\":\"2026-10-15T09:00:04Z\",\"media_refs\":[],\"kind\":\"text\",\"in_reply_to\":\"m-11\"," +
            "\"metadata\":{\"turn_id\":\"turn-m-11\"},\"link_previews\":[{\"url\":\"https://hexdocs.pm/elixir/" +
            "Task.html\",\"site\":\"hexdocs.pm\",\"title\":\"Task\"}]}')",
        "INSERT INTO timeline (server_seq, whole, role, kind, ts, content, client_msg_id, in_reply_to, media_refs, " +
            "metadata, link_previews, truncated, turn_id, route, row_json) VALUES (13, 0, NULL, NULL, NULL, " +
            "'Sealed before its row came.', NULL, NULL, NULL, NULL, " +
            "'[{\"url\":\"https://example.com\",\"site\":\"Example\",\"title\":\"Example page\"}]', 0, 'turn-m-12', " +
            "'{\"provider\":\"openai\",\"model\":\"gpt-6\"}', NULL)",
        "INSERT INTO cursors (id, last_server_seq, read_up_to_seq, last_mutation_seq, announced_up_to_seq, " +
            "last_unannounced_seq) VALUES (0, 13, 12, 3, 13, 0)",
        "INSERT INTO chat_state (id, draft, agent_name, previews) VALUES (0, 'half a thought', NULL, 1)",
    )

private val CORNER = PlaceLocation(40.7233, -74.003)

private val GUIDE = LinkPreviewCard("https://hexdocs.pm/elixir/Task.html", "hexdocs.pm", "Task")

private val KEPT =
    listOf(
        TimelineRow.Message(
            HistoryMessage(
                11uL,
                "user",
                "Is there a guide for task timeouts?",
                "2026-10-15T09:00:00Z",
                emptyList(),
                MessageKind.TEXT,
                clientMsgId = "m-11",
            ),
        ),
        TimelineRow.Message(
            HistoryMessage(
                12uL,
                "assistant",
                "The guide is on hexdocs.",
                "2026-10-15T09:00:04Z",
                emptyList(),
                MessageKind.TEXT,
                inReplyTo = "m-11",
                metadata = JsonObject(mapOf("turn_id" to JsonPrimitive("turn-m-11"))),
                linkPreviews = listOf(GUIDE),
            ),
        ),
        TimelineRow.Reply(
            13uL,
            "turn-m-12",
            "Sealed before its row came.",
            truncated = false,
            route = Route("openai", "gpt-6"),
            linkPreviews = listOf(LinkPreviewCard("https://example.com", "Example", "Example page")),
        ),
    )

/**
 * A profile database version 1 wrote, as the owner's phone and the preview emulator hold one, opened by this build:
 * Room's auto-migration to version 2 adds the reply's `blocks` column, and every row, cursor and draft version 1
 * kept reads back whole, the full-text index with them; a reply sealed after it keeps its blocks. Version 1 is made
 * from its own exported schema, statement by statement, as Room would have made it, on the bundled SQLite the app
 * ships.
 */
class ProfileMigrationTest {
    private val directory = Files.createTempDirectory("profile").toFile()
    private val file = File(directory, "profile.db")

    @AfterEach
    fun remove() {
        directory.deleteRecursively()
    }

    @Test
    fun `a version 1 database's rows, cursors and draft read back whole after the migration`() =
        runTest {
            writeVersionOne(file)
            val database = fileDatabase(file)
            try {
                val timeline = database.timeline()
                assertEquals(KEPT, timeline.rows(listOf(11uL, 12uL, 13uL)).sortedBy { it.serverSeq })
                assertEquals(listOf(11uL), timeline.search("timeouts", limit = 5).map { it.serverSeq })
                val cursors = RoomSessionStore(database, scratchStaging()).cursors()
                assertEquals(StoredCursors(13uL, 12uL, 3uL, 13uL, 0uL), cursors)
                assertEquals(
                    "half a thought",
                    database
                        .chat()
                        .state()
                        .first()
                        .draft,
                )
                val places = ReplyBlock.Places(listOf(Place("Corner Tea", "https://tea.example/", null, CORNER)))
                val sealed = TimelineRow.Reply(14uL, "turn-m-13", "Try this", false, null, blocks = listOf(places))
                timeline.persist(sealed)
                assertEquals(sealed, timeline.row(14uL))
            } finally {
                database.close()
            }
        }
}

/** The profile database in [file], as the app opens one, its queries on [queries]. */
private fun fileDatabase(
    file: File,
    queries: CoroutineDispatcher = Dispatchers.IO,
): ProfileDatabase {
    val builder = Room.databaseBuilder(TestContext, ProfileDatabase::class.java, file.path)
    return builder.buildProfileDatabase(queries)
}

/** A database at [file] as version 1 made it: its tables, triggers and identity from its schema, then its rows. */
private fun writeVersionOne(file: File) {
    val schema =
        Json
            .parseToJsonElement(resourceText(VERSION_ONE))
            .jsonObject
            .getValue("database")
            .jsonObject
    check(schema.getValue("version").jsonPrimitive.content == "1") { "$VERSION_ONE is not version 1's" }
    val connection = BundledSQLiteDriver().open(file.path)
    try {
        createdBy(schema).forEach(connection::execSQL)
        VERSION_ONE_ROWS.forEach(connection::execSQL)
        connection.execSQL("PRAGMA user_version = 1")
    } finally {
        connection.close()
    }
}

/**
 * The statements that made [schema]'s database, in Room's order: each entity's table and indices, the full-text
 * index's sync triggers, then the identity Room checks on open.
 */
private fun createdBy(schema: JsonObject): List<String> {
    val entities = schema.getValue("entities").jsonArray.map { it.jsonObject }
    val tables =
        entities.flatMap { entity ->
            val name = entity.getValue("tableName").jsonPrimitive.content
            val indices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql") }
            (listOf(entity.getValue("createSql")) + indices).map { it.jsonPrimitive.content.replace(TABLE_NAME, name) }
        }
    val triggers = entities.flatMap { entity -> entity["contentSyncTriggers"]?.jsonArray.orEmpty() }
    val setup = schema.getValue("setupQueries").jsonArray
    return tables + (triggers + setup).map { it.jsonPrimitive.content }
}

private fun resourceText(path: String): String {
    val stream = checkNotNull(TestContext::class.java.getResourceAsStream(path)) { "$path is not on the classpath" }
    return stream.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
}
