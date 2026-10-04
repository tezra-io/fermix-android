package io.tezra.fermix.chats

import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.showsDevTag

/**
 * The category a conversation's shortcut carries so that the share sheet offers it as a Direct Share target (design
 * section 13.6, "Share into Fermix"): the app's `shortcuts.xml` names it in its `<share-target>`, with the types a
 * share may carry and the share entry that takes them.
 */
const val SHARE_CATEGORY = "io.tezra.fermix.category.SHARE_TARGET"

/**
 * One (instance, profile)'s conversation (design section 9.1): its conversation shortcut and its
 * notification channel, both under [id], named and tinted as its row: its [title], the host-owned [agent]'s
 * name when that is not "Fermix", and the DEV tag when [dev] (section 9.2), so that two daemons of one
 * computer are told apart in the phone's settings and its share sheet as on the list.
 */
data class Conversation(
    val instanceId: String,
    val profileId: String,
    val title: String,
    val agent: String?,
    val dev: Boolean,
    val tint: String,
) {
    init {
        require(instanceId.isNotBlank() && profileId.isNotBlank()) { "a conversation names its instance and profile" }
    }

    /** The shortcut's and the channel's id: the instance's, then the profile's. */
    val id: String get() = "$instanceId$SEPARATOR$profileId"

    companion object {
        /** Between the instance's id, which is hex, and the profile's in [id]. */
        const val SEPARATOR = ":"
    }
}

/**
 * The conversations [records] have, each instance's agent named by [agents], by instance id: one per
 * (instance, profile), and every instance has `main` alone.
 */
fun conversationsOf(
    records: List<Instance>,
    agents: Map<String, String?>,
): List<Conversation> =
    records.map {
        Conversation(
            instanceId = it.id,
            profileId = MAIN_PROFILE,
            title = it.title,
            agent = hostAgent(agents[it.id]),
            dev = showsDevTag(it.profile, it.title),
            tint = it.tint,
        )
    }

/**
 * Where conversations are published: the system's shortcuts and notification channels on the phone,
 * and in the tests a fake. [published] says which ids are there now.
 */
interface ConversationSurface {
    fun published(): Set<String>

    /** Makes [conversation]'s shortcut and channel, or brings their title and tint up to date. */
    fun publish(conversation: Conversation)

    fun remove(ids: Set<String>)
}

/**
 * Keeps [surface] to the records: a row's conversation is made with it and changed with it, its name with its
 * row's, and goes when it does, by unpairing, removal or a restore that dropped it. Its first sync after the
 * process starts publishes every conversation once; after that, only what changed.
 */
class ConversationSync(
    private val surface: ConversationSurface,
) {
    private var last: Map<String, Conversation>? = null

    /** Brings the surface to [records], each instance's agent named by [agents], by instance id. */
    fun sync(
        records: List<Instance>,
        agents: Map<String, String?>,
    ) {
        val wanted = conversationsOf(records, agents).associateBy { it.id }
        val present = last?.keys ?: surface.published()
        val gone = present - wanted.keys
        if (gone.isNotEmpty()) surface.remove(gone)
        val previous = last.orEmpty()
        wanted.values.filter { previous[it.id] != it }.forEach(surface::publish)
        last = wanted
    }
}
