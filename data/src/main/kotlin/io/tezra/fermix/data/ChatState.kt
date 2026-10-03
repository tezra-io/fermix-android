package io.tezra.fermix.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The id of the one chat row, which the database's creation writes (ProfileDatabase.kt). */
internal const val CHAT_ROW = 0

/**
 * What one (instance, profile)'s chat keeps besides its timeline: the composer's [draft], which section 13.6
 * keeps per chat and the Chats list shows as "Draft: …" (section 9.4); [agentName], the host-owned agent's
 * name from `hello_ack.profiles[]`, which the row's title carries when it is not "Fermix" (section 9.2); and
 * whether its notifications show [previews] (section 13.7). None of it is a secret, and it goes with the
 * instance's files.
 */
data class ChatState(
    val draft: String?,
    val agentName: String?,
    val previews: Boolean,
) {
    init {
        require(draft == null || draft.isNotBlank()) { "a draft holds something" }
        require(agentName == null || agentName.isNotBlank()) { "an agent's name is not blank" }
    }
}

/** The one row of [ChatState], made with no draft, no agent name and previews on when the database is created. */
@Entity(tableName = "chat_state")
internal data class ChatStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "draft") val draft: String?,
    @ColumnInfo(name = "agent_name") val agentName: String?,
    @ColumnInfo(name = "previews") val previews: Boolean,
)

/** The chat's [ChatState]: one row, so every write is an update of it, and a missing row fails loud. */
@Dao
abstract class ChatStateDao {
    /** The chat's state, again on every change. */
    fun state(): Flow<ChatState> =
        observed().map { row ->
            checkNotNull(row) { "the chat row is missing" }
            ChatState(row.draft, row.agentName, row.previews)
        }

    /** Keeps [text] as the chat's draft; blank or null clears it. */
    suspend fun setDraft(text: String?) = requireChatRow(updateDraft(text?.takeIf { it.isNotBlank() }))

    /** Keeps [name], the host-owned agent's name `hello_ack` reported, or none. */
    suspend fun setAgentName(name: String?) = requireChatRow(updateAgentName(name?.takeIf { it.isNotBlank() }))

    suspend fun setPreviews(on: Boolean) = requireChatRow(updatePreviews(on))

    @Query("SELECT * FROM chat_state WHERE id = $CHAT_ROW")
    internal abstract fun observed(): Flow<ChatStateEntity?>

    @Query("UPDATE chat_state SET draft = :draft WHERE id = $CHAT_ROW")
    internal abstract suspend fun updateDraft(draft: String?): Int

    @Query("UPDATE chat_state SET agent_name = :name WHERE id = $CHAT_ROW")
    internal abstract suspend fun updateAgentName(name: String?): Int

    @Query("UPDATE chat_state SET previews = :on WHERE id = $CHAT_ROW")
    internal abstract suspend fun updatePreviews(on: Boolean): Int
}

private fun requireChatRow(changed: Int) {
    check(changed == 1) { "the chat row changed $changed times, not once" }
}
