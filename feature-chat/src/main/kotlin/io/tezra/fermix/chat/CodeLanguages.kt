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

/**
 * The most characters a fence holds and is still tinted. The card tints as it composes, so that the tint is in its
 * first frame, and highlights' work grows faster than the fence. On a laptop's desktop-class core, warm and at best,
 * it takes about 10 ms at this bound (2 ms at 4 Ki characters, 120 ms at 64 Ki, 2 s at 256 Ki), and the first call
 * in a process about 110 ms (55 ms at 1 Ki); a phone's core is slower, and is not measured yet. A
 * folded card tints only the lines it shows, so the whole bound is spent when a card is unfolded or a fence of
 * fourteen lines or fewer is long. A longer fence is drawn as an untinted language's is.
 */
const val TINT_MAX_CHARS = 16_384

/** The lexer that tints [code], a fence of [language]: none for an untinted language, or past [TINT_MAX_CHARS]. */
fun tintOf(
    language: String?,
    code: String,
): SyntaxLanguage? = if (code.length > TINT_MAX_CHARS) null else tintOf(language)

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
