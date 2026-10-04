package io.tezra.fermix.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The voice notes as the owner plays and sends them (design sections 8.5 and 13.5): a note's play or pause, its
 * file fetched once into the player's copy ([ChatBlobs.file]); the draft's play; a note's length read once from
 * its file; and the take sent or let go, the draft let go of the player first. Every call comes on the main
 * thread; the draft's copy is made on [io].
 */
class ChatNotes(
    private val voice: ChatVoice,
    private val playback: ChatPlayback,
    private val blobs: ChatBlobs,
    private val io: CoroutineDispatcher,
) {
    /** A note's play or pause. */
    fun play(note: ShownMedia) {
        playback.toggle(note.cacheName) { into -> blobs.file(note, into) is Blob.InFile }
    }

    /** The voice draft's play or pause ("play · send · discard"). */
    fun playDraft() {
        val draft = voice.draftFile ?: return
        playback.toggle(DRAFT_KEY) { into -> withContext(io) { draft.copyTo(into, overwrite = true) }.isFile }
    }

    /** A note's length, read once from its file; none when it cannot be had now. */
    suspend fun length(note: ShownMedia): Long? =
        playback.length(note.cacheName) { into -> blobs.file(note, into) is Blob.InFile }

    /** The take sent, the locked send or the draft's; [onTaken] once the session took it. */
    fun send(onTaken: () -> Unit) {
        if (playback.playing.value?.key == DRAFT_KEY) playback.stop()
        voice.send(onTaken)
    }

    /** The held mic let go: what it still records goes (ChatVoice.release); [onTaken] once the session took it. */
    fun release(onTaken: () -> Unit) {
        voice.release(onTaken)
    }

    /** The take let go: slid past the cancel line, or the draft's trash. */
    fun discard() {
        if (playback.playing.value?.key == DRAFT_KEY) playback.stop()
        voice.discard()
    }
}
