package io.tezra.fermix.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files

/**
 * A chat's files as ProfileDatabases keeps them: each attachment staged by its id in a directory of its own
 * ([staged], every path in order), none once [gone] or past the first [stagesLeft], and a stage that throws while
 * [failing], as a full disk does; a send never taken lets its files go ([released], unless [store]'s outbox names
 * one); a blob exported from [store]'s media cache; the voice draft's file beside them ([draft]).
 */
class FakeChatFiles(
    private val store: FakeChatStore,
) : ChatFiles {
    private val directory: File by lazy { Files.createTempDirectory("staged").toFile() }

    val staged = MutableStateFlow<List<String>>(emptyList())
    val released = MutableStateFlow<List<String>>(emptyList())
    var gone = false
    var stagesLeft = Int.MAX_VALUE
    var failing = false

    /** The voice draft's file, which lasts as long as this fake, as the profile's does across the chat's visits. */
    val draft: File get() = File(directory, "voice-draft.ogg")

    override suspend fun stage(
        file: File,
        attachId: String,
    ): String? {
        if (failing) throw IOException("no space left to stage $attachId")
        if (gone || stagesLeft <= 0) return null
        stagesLeft--
        val into = File(directory, attachId)
        Files.move(file.toPath(), into.toPath())
        staged.update { it + into.path }
        return into.path
    }

    override suspend fun voiceDraft(): File? = if (gone) null else draft

    override suspend fun release(paths: List<String>) {
        val named =
            store.outbox.value
                .flatMap { item -> item.attachments.map { it.source } }
                .toSet()
        val gone = paths.filterNot { it in named }
        gone.forEach { File(it).delete() }
        released.update { it + gone }
    }

    override suspend fun export(
        sha256: String,
        into: File,
    ): Boolean {
        val bytes = store.media.value[sha256] ?: return false
        into.writeBytes(bytes)
        return true
    }
}

/**
 * The phone's media as the tests set it: a URI describes as [describe] says, an image of 1,000 bytes by default;
 * an item's bytes are [bytes]' for its URI, a file's own for a file's URI, or its URI's own; an image that goes as
 * a JPEG comes out "image/jpeg" under a ".jpg" name, its bytes [jpeg]'s of the image's; a URI in [unreadable] throws
 * an IOException, one in [refused] a SecurityException, as a read grant that ended does, its words the platform's,
 * which name the URI whole, and one in [faulty] an IllegalStateException, as another app's provider that fails does
 * across the binder. Every prepare is recorded, with whether it went as a file, and every landing copy ([copied])
 * with the bytes it wrote, which the app's own bounded copy writes.
 */
class FakePipeline : MediaPipeline {
    var describe: (String, PickedFrom) -> Picked? = { uri, from ->
        Picked(uri, uri, PickedKind.IMAGE, "image/png", uri.substringAfterLast('/'), DEFAULT_SIZE, from)
    }
    var bytes: Map<String, ByteArray> = emptyMap()
    var unreadable: Set<String> = emptySet()
    var refused: Set<String> = emptySet()
    var faulty: Set<String> = emptySet()
    var colour: Int? = PLACEHOLDER

    /** An image's JPEG made of its bytes: the same bytes, unless a test makes it smaller. */
    var jpeg: (ByteArray) -> ByteArray = { it }

    val prepared = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
    val copied = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val shaded = MutableStateFlow<List<Int>>(emptyList())

    override suspend fun describe(
        uri: String,
        from: PickedFrom,
    ): Picked? = describe.invoke(uri, from)

    override suspend fun copyAtMost(
        picked: Picked,
        into: File,
        maxBytes: Long,
    ): Long {
        readable(picked)
        val source = ByteArrayInputStream(bytesOf(picked))
        val written = into.outputStream().use { copyAtMost(source, it, maxBytes) }
        copied.update { it + (picked.uri to written) }
        return written
    }

    override suspend fun prepare(
        picked: Picked,
        asFile: Boolean,
        into: File,
    ): Prepared {
        readable(picked)
        prepared.update { it + (picked.uri to asFile) }
        val asJpeg = picked.kind == PickedKind.IMAGE && !asFile
        into.writeBytes(if (asJpeg) jpeg(bytesOf(picked)) else bytesOf(picked))
        return if (asJpeg) {
            Prepared(
                "image/jpeg",
                picked.name.substringBeforeLast('.') + ".jpg",
            )
        } else {
            Prepared(picked.mime, picked.name)
        }
    }

    override fun dominantColour(bytes: ByteArray): Int? {
        shaded.update { it + bytes.size }
        return colour
    }

    /** Throws as the phone would for [picked]: an IOException, a SecurityException or a provider's fault. */
    private fun readable(picked: Picked) {
        if (picked.uri in unreadable) throw IOException("${picked.uri} cannot be read")
        if (picked.uri in refused) {
            throw SecurityException("Permission Denial: reading uri ${picked.uri} requires a grant")
        }
        check(picked.uri !in faulty) { "the provider of ${picked.uri} failed" }
    }

    /** [picked]'s bytes: [bytes]' for its URI, a file's own for a file's URI, or its URI's own. */
    private fun bytesOf(picked: Picked): ByteArray {
        val file = picked.uri.takeIf { it.startsWith("file:") }?.let { File(URI(it)) }
        return bytes[picked.uri] ?: file?.readBytes() ?: picked.uri.toByteArray()
    }

    companion object {
        const val DEFAULT_SIZE = 1_000L
        const val PLACEHOLDER = 0xFF336699.toInt()
    }
}

/** The clipboard: [media]'s URI, none while it holds no image or file. */
class FakeClip(
    var clip: String? = null,
) : ChatClip {
    override fun media(): String? = clip
}

/**
 * A microphone: [start] begins a take into its file, which [stop] fills with [recording] (none when it kept
 * nothing, [keeps] false); [amplitudes] answer the level asks in turn, then silence; [loseFocus] cuts it as the
 * phone would, a call or another app taking audio focus. [busy] makes start throw.
 */
class FakeRecorder : VoiceRecorder {
    var amplitudes: ArrayDeque<Int> = ArrayDeque()
    var recording: ByteArray = byteArrayOf(0x4f, 0x67, 0x67, 0x53)
    var keeps = true
    var busy = false

    val starts = MutableStateFlow(0)
    val pauses = MutableStateFlow(0)
    val resumes = MutableStateFlow(0)
    val stops = MutableStateFlow(0)
    var recordingInto: File? = null
        private set
    private var interrupted: (() -> Unit)? = null

    override fun start(
        into: File,
        onInterrupted: () -> Unit,
    ) {
        if (busy) throw IOException("the microphone is busy")
        recordingInto = into
        interrupted = onInterrupted
        starts.update { it + 1 }
    }

    override fun amplitude(): Int = amplitudes.removeFirstOrNull() ?: 0

    override fun pause() {
        pauses.update { it + 1 }
    }

    override fun resume() {
        resumes.update { it + 1 }
    }

    override fun stop(): Boolean {
        stops.update { it + 1 }
        interrupted = null
        val into = recordingInto ?: return false
        recordingInto = null
        if (keeps) into.writeBytes(recording)
        return keeps
    }

    /** Audio focus lost while recording, as a call takes it. */
    fun loseFocus() {
        interrupted?.invoke()
    }
}

/**
 * The one player: what it plays, from where and at what speed ([plays]); [position] where it stands; a file
 * lasts [lengthMs] and reads as [levels]; [end] plays the note to its end. [unplayable] makes play throw, and
 * [unreadable] makes read throw.
 */
class FakePlayer : VoicePlayer {
    var position = 0L
    var lengthMs = 11_000L
    var levels: List<Float> = listOf(QUIET_LEVEL, 1f, QUIET_LEVEL)
    var unplayable = false
    var unreadable = false

    val plays = MutableStateFlow<List<Triple<String, Long, Float>>>(emptyList())
    val stopped = MutableStateFlow(0)
    private var onEnd: (() -> Unit)? = null

    override fun play(
        file: File,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ) {
        if (unplayable) throw IOException("$file cannot be opened")
        plays.update { it + Triple(file.readText(), fromMs, speed) }
        position = fromMs
        this.onEnd = onEnd
    }

    override fun pause(): Long = position

    override fun position(): Long = position

    override fun stop() {
        stopped.update { it + 1 }
        onEnd = null
    }

    override fun read(file: File): NoteFile {
        if (unreadable) throw IOException("$file cannot be read")
        return NoteFile(lengthMs, levels)
    }

    fun end() {
        onEnd?.invoke()
    }
}
