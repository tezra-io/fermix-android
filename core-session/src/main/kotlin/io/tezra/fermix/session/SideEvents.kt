package io.tezra.fermix.session

import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.ServerEvent

/** The owner's role on the wire, whose rows an approval's answer is written as. */
private const val OWNER_ROLE = "user"

/**
 * The events beside a turn and the timeline (Dispatch): a blob's frames to the fetch that streams it, or to
 * the app; approvals to the session's cards and the app; reactions and link previews into the store, then
 * to the app; a search's page and a pull's models to the one-shot that asked; and model changes to the app,
 * each as a typed event.
 */
internal class SideEvents(
    private val core: SessionCore,
    private val live: Live,
) {
    /** [event], its frame's [raw] tail with it: whether it was one of these; any other is Dispatch's. */
    suspend fun take(
        event: ServerEvent.Known,
        raw: ByteArray,
    ): Boolean {
        when (event) {
            is ServerEvent.MediaBegin -> media(event)
            is ServerEvent.MediaChunk -> if (!live.fetches.chunk(event, raw)) core.emit(SessionEvent.Server(event))
            is ServerEvent.MediaEnd -> if (!live.fetches.end(event)) core.emit(SessionEvent.Server(event))
            is ServerEvent.Approval, is ServerEvent.ApprovalResolved -> approval(event)
            is ServerEvent.Reaction, is ServerEvent.LinkPreview -> onRow(event)
            is ServerEvent.SearchResults, is ServerEvent.Models, is ServerEvent.ModelChanged -> answer(event)
            else -> return false
        }
        return true
    }

    /**
     * A blob's start: a fetch's, which takes its frames, or a media reply's, whose row comes whole from
     * history: the session applies no row live without its media refs, and the blob's frames are the app's.
     */
    private suspend fun media(event: ServerEvent.MediaBegin) {
        if (live.fetches.begin(event)) return
        val timeline = core.timeline()
        timeline.saw(event.serverSeq)
        if (event.serverSeq > timeline.cursor) live.pullForward()
        core.emit(SessionEvent.Server(event))
    }

    /**
     * A card, shown or replayed, whose routes and token the session keeps while the app gets the rest; or its
     * end, which lets them go.
     */
    private suspend fun approval(event: ServerEvent.Known) {
        when (event) {
            is ServerEvent.Approval -> {
                core.approvals.shown(event, core.now())
                core.emit(SessionEvent.Approval(event.approvalId, event.kind, event.text, event.detail, event.ttlS))
            }

            is ServerEvent.ApprovalResolved -> {
                core.approvals.resolved(event.approvalId)
                core.emit(SessionEvent.ApprovalResolved(event.approvalId, event.outcome))
            }

            else -> {
                error("${nameOf(event)} is no card's")
            }
        }
    }

    /**
     * What the daemon puts on a row it sent already: a reaction on the owner's message, kept in its metadata as
     * the mutation feed keeps it, or a link preview, kept with the row's previews as its history carries them.
     * The store keeps it on the row it caches; one it does not cache gets it from its history.
     */
    private suspend fun onRow(event: ServerEvent.Known) {
        val store = core.parts.store
        when (event) {
            is ServerEvent.Reaction -> {
                val stored = store.applyReaction(event.inReplyTo, event.emoji)
                core.emit(SessionEvent.Reaction(event.inReplyTo, event.emoji, stored))
            }

            is ServerEvent.LinkPreview -> {
                val card = LinkPreviewCard(event.url, event.site, event.title, event.description, event.imageRef)
                val stored = store.addLinkPreview(event.inReplyTo, card)
                core.emit(SessionEvent.LinkPreview(event.inReplyTo, card, stored))
            }

            else -> {
                error("${nameOf(event)} is no row's")
            }
        }
    }

    /**
     * A search's page, without a hit on an approval's answer, and a pull's `models` go to the one-shot that
     * asked; a `models` none asked for, the answer to a `/model` command, and a model change go to the app. A
     * model change for another profile is noted and left, as Dispatch leaves that profile's rows.
     */
    private suspend fun answer(event: ServerEvent.Known) {
        when (event) {
            is ServerEvent.SearchResults -> {
                live.asked.searchResults(withoutAnswers(event))
            }

            is ServerEvent.Models -> {
                if (!live.asked.models(event)) core.emit(SessionEvent.Models(event.entries, event.next == true))
            }

            is ServerEvent.ModelChanged -> {
                modelChanged(event)
            }

            else -> {
                error("${nameOf(event)} answers nothing")
            }
        }
    }

    /**
     * [results] without a hit on an approval's answer: the daemon indexes the owner's row it wrote for the answer,
     * the route with the card's token, which is never rendered (PROTOCOL.md "Approvals"). Its row the phone keeps
     * without its words (keptMessage); its hit is told by the route it starts with (ShownApprovals.answers).
     */
    private fun withoutAnswers(results: ServerEvent.SearchResults): ServerEvent.SearchResults =
        results.copy(hits = results.hits.filterNot { it.role == OWNER_ROLE && core.approvals.answers(it.excerpt) })

    private suspend fun modelChanged(event: ServerEvent.ModelChanged) {
        if (event.profileId != core.instance.profileId) {
            return core.log(DiagnosticKind.OTHER_PROFILE, "${nameOf(event)} for ${event.profileId}")
        }
        core.emit(SessionEvent.ModelChanged(event.provider, event.model, event.label, event.source, event.note))
    }
}
