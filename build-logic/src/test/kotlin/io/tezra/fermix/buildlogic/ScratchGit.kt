package io.tezra.fermix.buildlogic

import java.io.File
import java.util.concurrent.TimeUnit

private const val GIT_TIMEOUT_SECONDS = 30L

/**
 * The whole environment of a git a test starts (AGENTS.md). Nothing of the machine's configuration: no system or
 * global file, which can name an identity or none, sign every tag with a program that fails or run hooks; no system
 * attributes file; no HOME, under which git reads its own ignore and attributes files; and no template, from which
 * init copies hooks into the repository, as an empty GIT_TEMPLATE_DIR copies none, whatever the machine's git holds
 * or its configuration names. None of the caller's git variables: git takes the repository from GIT_DIR,
 * GIT_INDEX_FILE, GIT_OBJECT_DIRECTORY and their kin before -C, and exports GIT_DIR to a hook or an alias in a linked
 * worktree, so a test run from there would commit into and tag the caller's repository. No repository above
 * [repository]: looking for one, git never climbs out of it. The identity is the test's, a commit's and an annotated
 * tag's tagger alike, and git guesses none from the machine's user and host name (user.useConfigOnly), so a call
 * given none fails here as it does on CI's runner.
 */
private fun scratchGitEnvironment(repository: File): Map<String, String> =
    mapOf(
        "PATH" to checkNotNull(System.getenv("PATH")) { "the tests run git from the PATH, and there is none" },
        "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_CONFIG_GLOBAL" to "/dev/null",
        "GIT_ATTR_NOSYSTEM" to "1",
        "GIT_TEMPLATE_DIR" to "",
        "GIT_CEILING_DIRECTORIES" to repository.absoluteFile.parent,
        "GIT_CONFIG_COUNT" to "1",
        "GIT_CONFIG_KEY_0" to "user.useConfigOnly",
        "GIT_CONFIG_VALUE_0" to "true",
        "GIT_AUTHOR_NAME" to "Probe",
        "GIT_AUTHOR_EMAIL" to "probe@example.com",
        "GIT_COMMITTER_NAME" to "Probe",
        "GIT_COMMITTER_EMAIL" to "probe@example.com",
    )

/**
 * Runs git with [arguments] in [repository], a scratch repository of the test's, with [scratchGitEnvironment] alone,
 * and fails the test with git's words when git fails. Bounded: git's output goes to [log], so the wait never blocks
 * on a pipe it has not drained, and however the wait ends, past its bound or interrupted, git is killed and waited
 * for, with the same bound, before the test goes on.
 */
internal fun scratchGit(
    repository: File,
    log: File,
    vararg arguments: String,
) {
    val builder =
        ProcessBuilder(listOf("git", "-C", repository.path) + arguments)
            .redirectErrorStream(true)
            .redirectOutput(log)
    builder.environment().clear()
    builder.environment().putAll(scratchGitEnvironment(repository))
    val process = builder.start()
    try {
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            error("git ${arguments.toList()} did not finish in $GIT_TIMEOUT_SECONDS s")
        }
    } finally {
        if (process.isAlive) process.destroyForcibly().waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
    check(process.exitValue() == 0) { "git ${arguments.toList()} failed: ${log.readText()}" }
}
