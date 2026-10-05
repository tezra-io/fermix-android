package io.tezra.fermix.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ScratchGitTest {
    @TempDir
    lateinit var scratch: File

    // git init copies the machine's template into the repository, its hooks included, which git then runs; a stock
    // template holds sample hooks, an exclude file and a description, so on any stock machine this shows whether
    // a repository a test makes took anything of it (AGENTS.md).
    @Test
    fun `a repository a test makes holds nothing of the machine's template`() {
        val repository = scratch.resolve("repository").also { it.mkdirs() }
        scratchGit(repository, scratch.resolve("git.log"), "init", "--quiet")
        val fromTemplate = listOf("hooks", "info", "description", "branches")
        assertEquals(emptyList<String>(), fromTemplate.filter { repository.resolve(".git").resolve(it).exists() })
    }
}
