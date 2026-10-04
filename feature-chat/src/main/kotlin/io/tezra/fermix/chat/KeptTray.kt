package io.tezra.fermix.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The key a chat's saved state keeps its tray's own files under, so that a process death keeps a share's, a paste's,
 * the keyboard's and the camera's copies and Edit's (design section 13.6): those are the chat's files, while another
 * app's grant to a picked item ends with the process.
 */
internal const val KEPT_TRAY = "tray"

private const val ID = "id"
private const val URI = "uri"
private const val KIND = "kind"
private const val MIME = "mime"
private const val NAME = "name"
private const val SIZE = "size"
private const val FROM = "from"

/** Whether [picked] is one the saved state keeps: a file the chat made, which a `file:` URI names (ownsFile). */
internal fun kept(picked: Picked): Boolean = ownsFile(picked) && picked.uri.startsWith("file:")

/** [picked]'s items the saved state keeps ([kept]), in order, as a JSON array. */
internal fun keptTrayOf(picked: List<Picked>): String =
    JsonArray(
        picked.filter(::kept).map {
            JsonObject(
                mapOf(
                    ID to JsonPrimitive(it.id),
                    URI to JsonPrimitive(it.uri),
                    KIND to JsonPrimitive(it.kind.name),
                    MIME to JsonPrimitive(it.mime),
                    NAME to JsonPrimitive(it.name),
                    SIZE to JsonPrimitive(it.sizeBytes),
                    FROM to JsonPrimitive(it.from.name),
                ),
            )
        },
    ).toString()

/**
 * The tray the saved state kept as [text] ([keptTrayOf]), none when it kept none; an item that is not one it keeps
 * ([kept]) is left out. The text is the app's own, written by [keptTrayOf]: one that does not read fails loud.
 */
internal fun trayKept(text: String?): List<Picked> {
    if (text == null) return emptyList()
    return Json
        .parseToJsonElement(text)
        .jsonArray
        .map { element ->
            val item = element.jsonObject

            fun field(key: String) = item.getValue(key).jsonPrimitive
            Picked(
                id = field(ID).content,
                uri = field(URI).content,
                kind = PickedKind.valueOf(field(KIND).content),
                mime = field(MIME).content,
                name = field(NAME).content,
                sizeBytes = field(SIZE).long,
                from = PickedFrom.valueOf(field(FROM).content),
            )
        }.filter(::kept)
}
