package io.tezra.fermix.data

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val UPLOADS_DIRECTORY = "uploads"

/** An attachment id as a file name: what the wire allows of it beyond that is never staged. */
private val STAGED_NAME = Regex("[A-Za-z0-9._-]{1,128}")

/** The names that match [STAGED_NAME] and still name a directory, never a staged file. */
private val DIRECTORY_NAMES = setOf(".", "..")

/**
 * One outbox attachment as the outbox row keeps it, in its `attachments` column (OutboxEntity): core-session's
 * OutboxAttachment, its fields named as `attach_begin` names them, with the local file and whether it is in.
 */
@Serializable
internal data class StoredAttachment(
    @SerialName("attach_id") val attachId: String,
    @SerialName("kind") val kind: AttachKind,
    @SerialName("mime") val mime: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("name") val name: String? = null,
    @SerialName("source") val source: String,
    @SerialName("uploaded") val uploaded: Boolean,
)

private val ATTACHMENTS = ListSerializer(serializer<StoredAttachment>())

/** [attachments] as the `attachments` column holds them. */
internal fun encodeAttachments(attachments: List<OutboxAttachment>): String =
    STORED_JSON.encodeToString(
        ATTACHMENTS,
        attachments.map {
            StoredAttachment(it.attachId, it.kind, it.mime, it.sizeBytes, it.sha256, it.name, it.source, it.uploaded)
        },
    )

/** The `attachments` column read back as core-session's attachments, each checked as it is made. */
internal fun decodeAttachments(column: String): List<OutboxAttachment> =
    STORED_JSON.decodeFromString(ATTACHMENTS, column).map {
        OutboxAttachment(it.attachId, it.kind, it.mime, it.sizeBytes, it.sha256, it.name, it.source, it.uploaded)
    }

/**
 * One (instance, profile)'s staged uploads (design section 8.5): the file each outbox attachment goes up from,
 * kept in [directory] beside the profile's database and media, under the attachment's id, until its item leaves
 * the outbox, whatever the media cache evicts meanwhile. The chat stages a file before it sends ([stage],
 * through ProfileDatabases.withStagedUploads); the session's store lets go of an item's files once no item names
 * them ([release]); and the launch check deletes what no item names, a process death between the two included
 * ([sweep]); a send its session never took lets its own go ([release], through releaseStagedUploads). An
 * instance's removal deletes the directory with the rest of its files. The methods do file I/O.
 */
class StagedUploads internal constructor(
    private val directory: File,
) {
    /**
     * Moves [file] in as [attachId]'s source, replacing one staged under that id; the staged file, whose path the
     * outbox item names. The move copies when [file] is on another filesystem.
     */
    fun stage(
        file: File,
        attachId: String,
    ): File {
        val named = STAGED_NAME.matches(attachId) && attachId !in DIRECTORY_NAMES
        require(named) { "$attachId is no name a staged file can take" }
        require(file.isFile) { "$file is not a file to stage" }
        makeDirectory(directory)
        val staged = File(directory, attachId)
        Files.move(file.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return staged
    }

    /** Deletes each of [paths] that is a file staged here; a path elsewhere is never touched. */
    internal fun release(paths: Collection<String>) {
        paths.map(::File).filter { it.parentFile == directory }.forEach(::deleteStaged)
    }

    /** Deletes every staged file whose path [keep] does not hold. */
    internal fun sweep(keep: Set<String>) {
        if (!directory.exists()) return
        val listed = directory.listFiles() ?: throw IOException("could not list $directory")
        listed.filter { it.path !in keep }.forEach(::deleteStaged)
    }

    private fun deleteStaged(file: File) {
        if (file.exists() && !file.delete()) throw IOException("could not delete the staged $file")
    }
}

/** Every local file [attachments] names, from the `attachments` column. */
internal fun sourcesOf(attachments: String): List<String> = decodeAttachments(attachments).map { it.source }

/**
 * [instanceId]'s staged uploads for [profileId], beside its media: for its session's store, which holds them
 * outside the readers as it holds its database (ProfileDatabases.open). Making it touches no file.
 */
fun ProfileDatabases.stagedUploads(
    instanceId: String,
    profileId: String,
): StagedUploads = StagedUploads(File(mediaDirectory(instanceId, profileId).parentFile, UPLOADS_DIRECTORY))

/**
 * [block] over [instanceId]'s staged uploads for [profileId], counted as a use of its database, which a removal
 * waits for: the chat stages each attachment's file there before it sends; [Use.Gone] once the instance is gone.
 */
suspend fun <T> ProfileDatabases.withStagedUploads(
    instanceId: String,
    profileId: String,
    block: (StagedUploads) -> T,
): Use<T> {
    val staged = stagedUploads(instanceId, profileId)
    return withDatabase(instanceId, profileId) { block(staged) }
}

/**
 * Deletes each of [paths] staged for [instanceId]'s [profileId] that no outbox item names: an attachment the chat
 * staged for a send its session never took. A use of its database, which a removal waits for; [Use.Gone] once the
 * instance is gone, its staged files with it.
 */
suspend fun ProfileDatabases.releaseStagedUploads(
    instanceId: String,
    profileId: String,
    paths: Collection<String>,
): Use<Unit> {
    val staged = stagedUploads(instanceId, profileId)
    return withDatabase(instanceId, profileId) { database ->
        val named =
            database
                .uploads()
                .attachments()
                .flatMap(::sourcesOf)
                .toSet()
        staged.release(paths.filterNot { it in named })
    }
}

/**
 * Deletes the staged files of [instanceId]'s [profileId] that its outbox no longer names: a process that died
 * between an item leaving the outbox and its files going. The launch check runs it before any session opens.
 */
internal suspend fun ProfileDatabases.sweepStagedUploads(
    instanceId: String,
    profileId: String,
) {
    val staged = stagedUploads(instanceId, profileId)
    withDatabase(instanceId, profileId) { database ->
        staged.sweep(
            database
                .uploads()
                .attachments()
                .flatMap(::sourcesOf)
                .toSet(),
        )
    }
}
