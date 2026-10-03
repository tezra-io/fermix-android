package io.tezra.fermix.chat

import dev.snipme.highlights.model.SyntaxLanguage

/**
 * The design's lexers (section 8.3) that highlights 1.1.0 has, by the names a fence's info string spells
 * them: kotlin, swift, JavaScript and TypeScript, Python, and bash as highlights' shell. Elixir, JSON, YAML,
 * SQL and diff have no lexer there, so their code stays untinted, as does a language the design does not
 * name, even one highlights has: the card tints the design's lexers alone.
 */
private val TINTED: Map<String, SyntaxLanguage> =
    mapOf(
        "kotlin" to SyntaxLanguage.KOTLIN,
        "kt" to SyntaxLanguage.KOTLIN,
        "kts" to SyntaxLanguage.KOTLIN,
        "swift" to SyntaxLanguage.SWIFT,
        "javascript" to SyntaxLanguage.JAVASCRIPT,
        "js" to SyntaxLanguage.JAVASCRIPT,
        "jsx" to SyntaxLanguage.JAVASCRIPT,
        "mjs" to SyntaxLanguage.JAVASCRIPT,
        "cjs" to SyntaxLanguage.JAVASCRIPT,
        "typescript" to SyntaxLanguage.TYPESCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT,
        "tsx" to SyntaxLanguage.TYPESCRIPT,
        "python" to SyntaxLanguage.PYTHON,
        "py" to SyntaxLanguage.PYTHON,
        "bash" to SyntaxLanguage.SHELL,
        "sh" to SyntaxLanguage.SHELL,
        "shell" to SyntaxLanguage.SHELL,
        "zsh" to SyntaxLanguage.SHELL,
    )

/** The lexer that tints a fence of [language], none for an untinted one. */
fun tintOf(language: String?): SyntaxLanguage? = language?.let { TINTED[it.lowercase()] }

/** A fence's info string read: the language its chip shows, and the file its header names, when it names one. */
data class FenceInfo(
    val language: String?,
    val filename: String?,
)

private val FILE_ATTRIBUTE = Regex("""^(?:title|file|filename)=(.+)$""")

/**
 * A fence's info string (the text after the opening backticks): its first word is the language, or
 * `language:file`; a second word, bare or as `title=`, `file=` or `filename=`, quoted or not, is the file.
 */
fun fenceInfo(info: String): FenceInfo {
    val words = info.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val first = words.firstOrNull() ?: return FenceInfo(null, null)
    val language = first.substringBefore(':').ifEmpty { null }
    val joined = first.substringAfter(':', "").ifEmpty { null }
    val second = words.getOrNull(1)?.let { FILE_ATTRIBUTE.find(it)?.groupValues?.get(1) ?: it }
    val filename = (joined ?: second)?.trim('"', '\'')?.ifEmpty { null }
    return FenceInfo(language, filename)
}

/** The lines a collapsed code card shows (design section 13.5); a longer fence folds past them. */
const val CODE_FOLD_LINES = 14

/** Whether [code] folds on its card. */
fun folds(code: String): Boolean = code.lines().size > CODE_FOLD_LINES

/** [code] as a folded card shows it: its first [CODE_FOLD_LINES] lines. */
fun folded(code: String): String = code.lines().take(CODE_FOLD_LINES).joinToString("\n")
