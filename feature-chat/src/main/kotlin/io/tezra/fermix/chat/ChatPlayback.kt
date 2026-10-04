package io.tezra.fermix.chat

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

/** The speeds the voice note's chip steps through, in order (design section 13.5, "Voice notes"). */
val SPEEDS = listOf(1f, 1.5f, 2f)

/** The key the composer's voice draft plays under. */
const val DRAFT_KEY = "draft"

/** How often a playing note's position is read. */
private const val TICK_MS = 100L

/** Ticks past a note's length the position is still read for, a slow player's slack. */
private const val SLACK_TICKS = 50L

/** The note the player holds: its [key], where it stands, how long it is, its speed, and whether it plays. */
data class Playing(
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val running: Boolean,
)

/**
 * The one player voice notes play through (design section 13.5, "Voice notes"; [ChatParts.player]): one note at a
 * time, by its key, from a scratch copy of its file the chat owns, deleted as another note takes the player or
 * [stop] lets it go; the speed chip steps 1× · 1.5× · 2×. What it reads of a note's file, its length and its 40
 * bars, it keeps by the note's key, so a bubble draws them before it plays, whoever recorded the note. Every call
 * comes on the main thread.
 */
class ChatPlayback(
    private val parts: ChatParts,
    private val scope: CoroutineScope,
) {
    private val io: CoroutineDispatcher = parts.io

    private val now = MutableStateFlow<Playing?>(null)
    private val known = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val drawn = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    private var held: File? = null
    private var ticker: Job? = null

    val playing: StateFlow<Playing?> = now.asStateFlow()

    /** Each note's length the player read, by its key, so its bubble says it before it plays. */
    val lengths: StateFlow<Map<String, Long>> = known.asStateFlow()

    /** Each note's bars, read from its file with its length, by its key. */
    val bars: StateFlow<Map<String, List<Float>>> = drawn.asStateFlow()

    /**
     * [key]'s length, read once with its bars from a scratch copy that [copy] makes and that is deleted however it
     * ends; none when the note cannot be had now or its file cannot be read, which is logged.
     */
    suspend fun length(
        key: String,
        copy: suspend (File) -> Boolean,
    ): Long? {
        known.value[key]?.let { return it }
        val file = withContext(io) { parts.scratch() }
        val read =
            try {
                if (copied(parts, key, copy, file)) readNote(key, file) else null
            } finally {
                withContext(NonCancellable + io) { file.delete() }
            }
        return read?.durationMs
    }

    /**
     * Play or pause [key]; another note playing stops first. [copy] puts the note's bytes in the file it is given,
     * the first time: whether it could.
     */
    fun toggle(
        key: String,
        copy: suspend (File) -> Boolean,
    ) {
        val current = now.value
        when {
            current?.key == key && current.running -> pause(current)
            current?.key == key -> play(current)
            else -> scope.launch { load(key, copy) }
        }
    }

    /** The speed chip: the next speed, the note going on from where it stands. */
    fun faster() {
        val current = now.value ?: return
        val next = SPEEDS[(SPEEDS.indexOf(current.speed) + 1) % SPEEDS.size]
        val moved = current.copy(speed = next, positionMs = positionOf(current))
        if (current.running) play(moved) else now.value = moved
    }

    /** Lets the player go: nothing plays, and the copy is deleted, on the app's scope, which outlives the chat's. */
    fun stop() {
        if (now.value != null) parts.player.stop()
        ticker?.cancel()
        now.value = null
        val file = held
        held = null
        if (file != null) parts.background.launch(io) { file.delete() }
    }

    /** [key] copied into its own scratch file and held for the player, which plays it from its start. */
    private suspend fun load(
        key: String,
        copy: suspend (File) -> Boolean,
    ) {
        stop()
        val file = withContext(io) { parts.scratch() }
        var kept = false
        try {
            val read = if (copied(parts, key, copy, file)) readNote(key, file) else null
            if (read == null) return parts.log("Voice note $key could not be had to play", null)
            held = file
            kept = true
            play(Playing(key, 0L, read.durationMs, SPEEDS.first(), running = false))
        } finally {
            if (!kept) withContext(NonCancellable + io) { file.delete() }
        }
    }

    /** [file]'s length and bars, kept by [key]; none, logged, when the player cannot read it. */
    private suspend fun readNote(
        key: String,
        file: File,
    ): NoteFile? {
        val read =
            try {
                withContext(io) { parts.player.read(file) }
            } catch (unreadable: IOException) {
                parts.log("Voice note $key could not be read", unreadable)
                return null
            }
        known.update { kept(it, key, read.durationMs) }
        drawn.update { kept(it, key, barsOf(read.levels)) }
        return read
    }

    /** Plays [from]; a note the player cannot open is logged and stays as it was, not running. */
    private fun play(from: Playing) {
        val file = held ?: return
        val start = if (from.positionMs >= from.durationMs) 0L else from.positionMs
        val playing = from.copy(positionMs = start, running = true)
        try {
            parts.player.play(file, start, playing.speed) { ended(playing.key) }
        } catch (unplayable: IOException) {
            parts.log("Voice note ${from.key} could not be played", unplayable)
            now.value = from.copy(running = false)
            return
        }
        now.value = playing
        ticker?.cancel()
        ticker = scope.launch { tick(playing) }
    }

    private fun pause(current: Playing) {
        ticker?.cancel()
        now.value = current.copy(positionMs = parts.player.pause(), running = false)
    }

    private fun ended(key: String) {
        val current = now.value?.takeIf { it.key == key } ?: return
        ticker?.cancel()
        now.value = current.copy(positionMs = 0L, running = false)
    }

    /** The position read while [playing] plays, bounded by its length at its speed and some slack. */
    private suspend fun tick(playing: Playing) {
        val ticks = (playing.durationMs / playing.speed).toLong() / TICK_MS + SLACK_TICKS
        repeat(ticks.toInt()) {
            delay(TICK_MS)
            val current = now.value?.takeIf { held -> held.key == playing.key && held.running } ?: return
            now.value = current.copy(positionMs = positionOf(current))
        }
    }

    private fun positionOf(current: Playing): Long =
        if (current.running) parts.player.position() else current.positionMs
}

/** Whether [copy] put [key]'s bytes in [file]; a copy whose source went as it read is logged, and is not. */
private suspend fun copied(
    parts: ChatParts,
    key: String,
    copy: suspend (File) -> Boolean,
    file: File,
): Boolean =
    try {
        copy(file)
    } catch (unreadable: IOException) {
        parts.log("Voice note $key could not be copied", unreadable)
        false
    }
