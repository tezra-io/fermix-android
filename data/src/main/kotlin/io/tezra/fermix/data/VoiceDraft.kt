package io.tezra.fermix.data

import java.io.File

private const val VOICE_DRAFT = "voice-draft.ogg"

/**
 * [instanceId]'s voice draft file for [profileId] (design section 13.6, "Drafts persist per chat"): the one a voice
 * note records into and its unsent draft stays in, in the profile's directory beside its database, so the draft
 * outlives the chat's screen and the process, and goes with the instance. A use of its database, which opening
 * makes the directory for, and which a removal waits for; [Use.Gone] once the instance is gone. Only the path is
 * returned: the file is the caller's to write, read and delete.
 */
suspend fun ProfileDatabases.voiceDraft(
    instanceId: String,
    profileId: String,
): Use<File> {
    val file = File(mediaDirectory(instanceId, profileId).parentFile, VOICE_DRAFT)
    return withDatabase(instanceId, profileId) { file }
}
