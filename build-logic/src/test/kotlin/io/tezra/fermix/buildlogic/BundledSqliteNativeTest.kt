package io.tezra.fermix.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BundledSqliteNativeTest {
    @Test
    fun `each desktop the jar has a library for finds its own`() {
        assertEquals(SqliteNative("natives/linux_x64", "libsqliteJni.so"), sqliteNativeFor("Linux", "amd64"))
        assertEquals(SqliteNative("natives/linux_arm64", "libsqliteJni.so"), sqliteNativeFor("Linux", "aarch64"))
        assertEquals(SqliteNative("natives/osx_arm64", "libsqliteJni.dylib"), sqliteNativeFor("Mac OS X", "aarch64"))
        assertEquals(SqliteNative("natives/osx_x64", "libsqliteJni.dylib"), sqliteNativeFor("Mac OS X", "x86_64"))
        assertEquals(SqliteNative("natives/windows_x64", "sqliteJni.dll"), sqliteNativeFor("Windows 11", "amd64"))
    }

    @Test
    fun `a desktop the jar has no library for fails and names it`() {
        listOf("Windows 11" to "aarch64", "FreeBSD" to "amd64", "Linux" to "riscv64").forEach { (os, arch) ->
            val refusal = assertThrows<GradleException> { sqliteNativeFor(os, arch) }
            assertEquals(
                "androidx.sqlite:sqlite-bundled-jvm has no native library for $os on $arch, so the JVM tests " +
                    "of a Room module cannot run on this machine.",
                refusal.message,
            )
        }
    }
}
