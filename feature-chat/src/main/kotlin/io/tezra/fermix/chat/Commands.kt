package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor

/** The command whose row the palette draws in the error colour (design section 13.6): it stops the reply. */
const val STOP_COMMAND = "stop"

/**
 * What the palette filters by while the field holds "/" and a word with nothing after it (design section
 * 13.6, "Slash palette"): the word, none once a space or a line follows it, or the field is not a command.
 */
fun paletteQuery(field: String): String? {
    if (!field.startsWith("/")) return null
    val word = field.drop(1)
    return if (word.any { it.isWhitespace() }) null else word
}

/** The wire's rule for a command's name (PROTOCOL.md: lowercase letters, digits and `_`), which the codec holds. */
private val COMMAND_NAME = Regex("[a-z0-9_]+")

/**
 * The daemon's [commands] the phone can send: those whose name keeps the wire's rule ([COMMAND_NAME]). The caps hold a
 * name to no form (the decoder asks only that it is there), and the codec refuses a `command` whose name breaks it, so
 * the palette never offers such a command and its words go as a `msg`.
 */
fun sendableCommands(commands: List<CommandDescriptor>): List<CommandDescriptor> =
    commands.filter { COMMAND_NAME.matches(it.name) }

/** [commands] whose name or an alias starts with [query], ignoring case, in the daemon's order. */
fun paletteCommands(
    commands: List<CommandDescriptor>,
    query: String,
): List<CommandDescriptor> =
    commands.filter { command ->
        (listOf(command.name) + command.aliases).any { it.startsWith(query, ignoreCase = true) }
    }

/** What a picked command puts in the field: "/name ", ready for its arguments. */
fun pickedCommand(command: CommandDescriptor): String = "/${command.name} "

/**
 * What the composer sends for [text] (design section 13.6): "/name args", where name is a command of the
 * daemon's or one of its aliases and args follow it after a space or a line, goes as that `command` by its
 * name; anything else is a `msg`. [text] is sent as the owner typed it, but for the spaces around it; a blank
 * one sends nothing.
 */
fun requestOf(
    text: String,
    commands: List<CommandDescriptor>,
    clientMsgId: String,
    profileId: String,
): ClientEvent? {
    require(clientMsgId.isNotEmpty() && profileId.isNotEmpty()) { "a request names itself and its profile" }
    val words = text.trim()
    val (name, rest) = commandWords(words)
    val command = if (words.startsWith("/")) commandOf(name, commands) else null
    return when {
        words.isEmpty() -> null
        command != null -> ClientEvent.Command(clientMsgId, profileId, command.name, rest.trim().ifEmpty { null })
        else -> ClientEvent.Msg(clientMsgId, profileId, words, emptyList())
    }
}

/**
 * Whether [text] is the model command alone, by its name or one of its aliases among the daemon's [commands]:
 * "/model" with nothing after it, which asks the daemon for its models (design section 7, `models`).
 */
fun asksForModels(
    text: String,
    commands: List<CommandDescriptor>,
): Boolean {
    val words = text.trim()
    if (!words.startsWith("/") || words.any { it.isWhitespace() }) return false
    return commandOf(words.drop(1), commands)?.name == MODEL_COMMAND
}

/** A command's name and what follows it, split at the first whitespace of any kind after the "/". */
private fun commandWords(words: String): Pair<String, String> {
    val body = words.drop(1)
    val end = body.indexOfFirst { it.isWhitespace() }
    return if (end < 0) body to "" else body.substring(0, end) to body.substring(end)
}

private fun commandOf(
    name: String,
    commands: List<CommandDescriptor>,
): CommandDescriptor? =
    commands.firstOrNull { command ->
        command.name.equals(name, ignoreCase = true) || command.aliases.any { it.equals(name, ignoreCase = true) }
    }

/** "Reset to default" on a `model_unavailable` card (design section 13.9): `command{name:"model", args:"reset"}`. */
fun resetModel(
    clientMsgId: String,
    profileId: String,
): ClientEvent = ClientEvent.Command(clientMsgId, profileId, "model", "reset")
