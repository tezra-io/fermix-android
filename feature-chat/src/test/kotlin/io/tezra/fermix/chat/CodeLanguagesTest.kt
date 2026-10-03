package io.tezra.fermix.chat

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The code card's lexers (design section 8.3) and how it reads a fence's info string. */
class CodeLanguagesTest {
    @Test
    fun `the design's lexers that highlights has are tinted and the rest are not`() {
        val table =
            mapOf(
                "kotlin" to SyntaxLanguage.KOTLIN,
                "kt" to SyntaxLanguage.KOTLIN,
                "Kotlin" to SyntaxLanguage.KOTLIN,
                "swift" to SyntaxLanguage.SWIFT,
                "js" to SyntaxLanguage.JAVASCRIPT,
                "javascript" to SyntaxLanguage.JAVASCRIPT,
                "ts" to SyntaxLanguage.TYPESCRIPT,
                "typescript" to SyntaxLanguage.TYPESCRIPT,
                "python" to SyntaxLanguage.PYTHON,
                "py" to SyntaxLanguage.PYTHON,
                "bash" to SyntaxLanguage.SHELL,
                "sh" to SyntaxLanguage.SHELL,
                "elixir" to null,
                "json" to null,
                "yaml" to null,
                "sql" to null,
                "diff" to null,
                // highlights has Rust and Go, but the design names neither: untinted.
                "rust" to null,
                "go" to null,
            )
        table.forEach { (language, lexer) -> assertEquals(lexer, tintOf(language), language) }
        assertNull(tintOf(null))
    }

    @Test
    fun `a fence's info string gives its language and the file it names`() {
        assertEquals(FenceInfo("elixir", "report_job.ex"), fenceInfo("elixir report_job.ex"))
        assertEquals(FenceInfo("kotlin", "Main.kt"), fenceInfo("kotlin:Main.kt"))
        assertEquals(FenceInfo("ts", "app.ts"), fenceInfo("ts title=\"app.ts\""))
        assertEquals(FenceInfo("python", null), fenceInfo("  python  "))
        assertEquals(FenceInfo(null, null), fenceInfo(""))
    }

    @Test
    fun `a fence folds past fourteen lines`() {
        val fourteen = (1..14).joinToString("\n") { "line $it" }
        val twenty = (1..20).joinToString("\n") { "line $it" }
        assertFalse(folds(fourteen))
        assertTrue(folds(twenty))
        assertEquals(fourteen, folded(twenty))
    }
}
