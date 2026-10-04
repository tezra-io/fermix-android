package io.tezra.fermix.chat

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How often the recording row samples the microphone's level: ten bars a second. */
const val SAMPLE_MS = 100L

/**
 * The longest voice note, ten minutes: the sampling loop's bound, past which the take becomes a draft. Ten
 * minutes of Opus at MediaRecorder's speech rate is a few MB, well inside `caps.max_media_bytes`.
 */
const val MAX_VOICE_MS = 600_000L

/** The most notes whose bars and lengths the chat remembers for their bubbles, the oldest let go first. */
private const val MAX_NOTES = 64

/** The voice composer's state (design sections 8.5 and 13.6). */
sealed interface VoiceUi {
    data object Idle : VoiceUi

    /** Recording, for [elapsedMs]: the live [bars]; locked hands-free, and paused while locked. */
    data class Recording(
        val elapsedMs: Long,
        val bars: List<Float>,
        val locked: Boolean,
        val paused: Boolean,
    ) : VoiceUi

    /** An unsent take of [durationMs] (play · send · discard): "Recording stopped — send or discard". */
    data class Draft(
        val durationMs: Long,
        val bars: List<Float>,
    ) : VoiceUi
}

/** One take: its file, every level sampled, and its clock, paused time left out. */
private class Take(
    val file: File,
    val startedMs: Long,
) {
    val levels = ArrayList<Float>()
    var locked = false
    var pausedMs = 0L
    var pausedAt: Long? = null

    fun elapsed(now: Long): Long = now - startedMs - pausedMs - (pausedAt?.let { now - it } ?: 0L)
}

/**
 * The voice note (design sections 8.5 and 13.6, D15): hold the mic to record, through [ChatParts.recorder], into
 * the chat's voice draft file (ChatFiles.voiceDraft); release sends what still records ([release]), sliding left
 * cancels ([discard]), sliding up locks, and locked it pauses, stops or sends. [stop] keeps the take as an unsent
 * draft (play · send · discard): the stop button, the recorder's interruption (audio focus lost, a call among it),
 * the app leaving the foreground, a hold the phone took from the finger, the chat closing (ChatViewModel) and the
 * ten-minute cap all stop, and a release after that sends nothing; nothing is sent without a tap. The draft stays
 * in its file across the chat's visits and the process, and comes back as the chat opens (design section 13.6,
 * "Drafts persist per chat"). A note goes as one `msg` with no words and one `audio` attachment, "audio/ogg"
 * (OGG/Opus): a copy of the take staged as any attachment is, so one its session does not take is the draft again.
 * The bars it was recorded with stay with its digest ([bars]) so its bubble draws them. Every call comes on the
 * main thread, the recorder's interruption included.
 */
class ChatVoice(
    private val parts: ChatParts,
    private val requests: ChatRequests,
    private val scope: CoroutineScope,
) {
    private val io: CoroutineDispatcher = parts.io

    private val shown = MutableStateFlow<VoiceUi>(VoiceUi.Idle)
    private val notes = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    private val timed = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var take: Take? = null
    private var sampler: Job? = null

    // The draft's file, found as the chat opened (restore): no take starts before it is, or once the chat is gone.
    private var draftAt: File? = null

    val ui: StateFlow<VoiceUi> = shown.asStateFlow()

    /** Each note recorded here, by its SHA-256: the bars its bubble draws. */
    val bars: StateFlow<Map<String, List<Float>>> = notes.asStateFlow()

    /** Each note recorded here, by its SHA-256: how long it is, paused time left out. */
    val lengths: StateFlow<Map<String, Long>> = timed.asStateFlow()

    init {
        // As the chat opens: the draft's file found, and the draft its last visit kept back in the composer.
        scope.launch {
            val file = parts.files.voiceDraft() ?: return@launch parts.log("The chat is gone; no voice draft", null)
            val note = withContext(io) { keptNote(file, parts) }
            if (note != null) {
                take = Take(file, parts.clock.monoMs())
                shown.value = VoiceUi.Draft(note.durationMs, barsOf(note.levels))
            }
            draftAt = file
        }
    }

    /**
     * The mic held: recording starts into the draft's file, unless a take is held already (a draft, or a note on its
     * way), the file is not found yet (in the chat's first moment, or once it is gone), or the microphone cannot
     * record.
     */
    fun start() {
        val file = draftAt
        if (take != null || file == null) return
        try {
            parts.recorder.start(file, ::stop)
        } catch (busy: IOException) {
            parts.log("The microphone could not record", busy)
            file.delete()
            return
        }
        val held = Take(file, parts.clock.monoMs())
        take = held
        shown.value = recordingOf(held, held.startedMs)
        sampler = scope.launch { sample(held) }
    }

    /** Slid up: the recording goes on hands-free (pause · stop · send). */
    fun lock() {
        val held = take ?: return
        if (shown.value !is VoiceUi.Recording) return
        held.locked = true
        shown.value = recordingOf(held, parts.clock.monoMs())
    }

    /** Pause, locked: the microphone holds and the timer stops. */
    fun pause() {
        val held = take?.takeIf { it.locked && it.pausedAt == null && shown.value is VoiceUi.Recording } ?: return
        parts.recorder.pause()
        held.pausedAt = parts.clock.monoMs()
        shown.value = recordingOf(held, parts.clock.monoMs())
    }

    fun resume() {
        val held = take ?: return
        val pausedAt = held.pausedAt ?: return
        parts.recorder.resume()
        held.pausedMs += parts.clock.monoMs() - pausedAt
        held.pausedAt = null
        shown.value = recordingOf(held, parts.clock.monoMs())
    }

    /**
     * Stops the recording and keeps it as a draft: the stop button, and whatever cuts it, never a send. A take the
     * recorder kept nothing of is let go.
     */
    fun stop() {
        val held = take ?: return
        if (shown.value !is VoiceUi.Recording) return
        val duration = held.elapsed(parts.clock.monoMs())
        sampler?.cancel()
        if (parts.recorder.stop()) {
            shown.value = VoiceUi.Draft(duration, barsOf(held.levels))
        } else {
            parts.log("The recording kept nothing to send", null)
            release(held)
        }
    }

    /**
     * The held mic let go: the take goes while it is still the unlocked recording the hold started. One the phone
     * stopped into a draft meanwhile (audio focus lost, a call, the app leaving, the touch taken) stays a draft,
     * since nothing is sent without a tap. [onTaken] once the session took it.
     */
    fun release(onTaken: () -> Unit) {
        val now = shown.value
        if (now is VoiceUi.Recording && !now.locked) send(onTaken)
    }

    /**
     * Sends the take, recording or a draft: the locked send, the draft's, or a release; [onTaken] once the session
     * took it. With no session to take it, a recording stops into a draft, which waits for the owner's tap. The take
     * stays held while it goes, so no other starts into its file, and one not taken is the draft again.
     */
    fun send(onTaken: () -> Unit) {
        val now = shown.value
        val held = take?.takeIf { now != VoiceUi.Idle } ?: return
        if (!requests.hasSession()) {
            parts.log("No session takes the voice note now; it stays a draft", null)
            return stop()
        }
        val draft = now as? VoiceUi.Draft ?: VoiceUi.Draft(held.elapsed(parts.clock.monoMs()), barsOf(held.levels))
        val recorded = now !is VoiceUi.Recording || parts.recorder.stop()
        sampler?.cancel()
        shown.value = VoiceUi.Idle
        if (recorded) scope.launch { deliver(held, draft, onTaken) } else release(held)
    }

    /** Slid left past the cancel line, or the draft's trash: the take is let go, its file deleted. */
    fun discard() {
        val held = take ?: return
        val now = shown.value
        if (now == VoiceUi.Idle) return
        if (now is VoiceUi.Recording) parts.recorder.stop()
        release(held)
    }

    /** The draft's file, for its play button; none while there is no draft. */
    val draftFile: File? get() = take?.file?.takeIf { shown.value is VoiceUi.Draft }

    /** Samples the level every [SAMPLE_MS] while [held] records, at most [MAX_VOICE_MS] of it, then stops it. */
    private suspend fun sample(held: Take) {
        repeat((MAX_VOICE_MS / SAMPLE_MS).toInt()) {
            delay(SAMPLE_MS)
            if (take !== held) return
            if (held.pausedAt == null) held.levels += levelOf(parts.recorder.amplitude())
            shown.value = recordingOf(held, parts.clock.monoMs())
        }
        stop()
    }

    /**
     * [held]'s note, [draft] as it would show, as one `msg` with its `audio` attachment, a staged copy of its file;
     * [onTaken] once the session took it, its bars and length kept by its digest and the take let go. One that was
     * not staged or taken is the draft again.
     */
    private suspend fun deliver(
        held: Take,
        draft: VoiceUi.Draft,
        onTaken: () -> Unit,
    ) {
        val taken = noteSent(parts, requests, held.file)
        if (taken == null) {
            shown.value = draft
            return
        }
        onTaken()
        notes.update { kept(it, taken.sha256, draft.bars) }
        timed.update { kept(it, taken.sha256, draft.durationMs) }
        take = null
    }

    /** Lets [held] go: no take, the row idle, its file deleted now, before another take can start into it. */
    private fun release(held: Take) {
        sampler?.cancel()
        take = null
        shown.value = VoiceUi.Idle
        held.file.delete()
    }
}

/** What [file] holds as a kept draft, none when it holds none; one that cannot be read is let go, logged. */
private fun keptNote(
    file: File,
    parts: ChatParts,
): NoteFile? {
    if (!file.isFile) return null
    return try {
        parts.player.read(file)
    } catch (unreadable: IOException) {
        parts.log("The kept voice draft could not be read; it was let go", unreadable)
        file.delete()
        null
    }
}

/**
 * The voice note in [file] as one `msg` with its `audio` attachment, a staged copy of the file, through [requests]:
 * the attachment once the session took it, when the draft's [file] is deleted with it, or the next visit would offer
 * a note sent already; none, logged, when it could not be copied, staged or taken, its staged copy let go.
 */
private suspend fun noteSent(
    parts: ChatParts,
    requests: ChatRequests,
    file: File,
): OutboxAttachment? {
    val attachment = stagedCopy(parts, file) ?: return null
    val request = ClientEvent.Msg(parts.newId(), parts.profileId, "", listOf(attachment.attachId))
    return withContext(NonCancellable) {
        val taken = requests.send(request, listOf(attachment))
        if (taken) {
            withContext(parts.io) { file.delete() }
        } else {
            parts.log("The voice note was not taken: its session ended as it went; it stays a draft", null)
            parts.files.release(listOf(attachment.source))
        }
        attachment.takeIf { taken }
    }
}

/** A copy of [file] staged as a new `audio` attachment; none, logged, when it could not be copied or staged. */
private suspend fun stagedCopy(
    parts: ChatParts,
    file: File,
): OutboxAttachment? {
    val copy = withContext(parts.io) { parts.scratch() }
    var attachment: OutboxAttachment? = null
    try {
        val sha256 = withContext(parts.io) { copyHashed(file, copy) }
        attachment = stageAttachment(parts, copy, AttachKind.AUDIO, Prepared(VOICE_MIME, VOICE_NAME), sha256)
    } catch (unreadable: IOException) {
        parts.log("The voice note could not be copied and staged; it stays a draft", unreadable)
    } finally {
        if (attachment == null) withContext(NonCancellable + parts.io) { copy.delete() }
    }
    return attachment
}

/** [from] copied into [into]: the copy's SHA-256; the caller runs it off the main thread. */
private fun copyHashed(
    from: File,
    into: File,
): String {
    from.copyTo(into, overwrite = true)
    return sha256Of(into)
}

/** [held] as the recording row shows it at [now]. */
private fun recordingOf(
    held: Take,
    now: Long,
): VoiceUi.Recording = VoiceUi.Recording(held.elapsed(now), liveBars(held.levels), held.locked, held.pausedAt != null)

/** [held] with [key]'s [value] last, at most [MAX_NOTES], the oldest let go first: a note's bars or length. */
internal fun <T> kept(
    held: Map<String, T>,
    key: String,
    value: T,
): Map<String, T> =
    (held - key + (key to value))
        .entries
        .toList()
        .takeLast(MAX_NOTES)
        .associate { it.toPair() }
