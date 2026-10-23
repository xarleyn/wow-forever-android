package com.winlator.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FexRuntimeFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun repairsExactPrefixesTruncatedAtControlZAndPreservesTheRemainingBinary() {
        for (offset in listOf(337, 474, 1740)) {
            val bytes = ByteArray(150_000) { 0x41 }.also { it[offset] = 0x1a; it[it.lastIndex] = 0x7f }
            val source = temporary.newFile("source-$offset.dll").apply { writeBytes(bytes) }
            val target = temporary.newFile("installed-$offset.dll").apply { writeBytes(bytes.copyOf(offset)) }
            assertFalse(FexRuntimeFiles.matches(target, bytes.size.toLong(), hash(bytes)))
            FexRuntimeFiles.copyVerifiedFile(source, target, bytes.size.toLong(), hash(bytes))
            assertArrayEquals(bytes, target.readBytes())
            assertTrue(FexRuntimeFiles.matches(target, bytes.size.toLong(), hash(bytes)))
        }
    }

    @Test fun sameSizeCorruptionIsDetectedAndRepaired() {
        val bytes = ByteArray(12_000) { (it % 251).toByte() }
        val source = temporary.newFile("source.dll").apply { writeBytes(bytes) }
        val target = temporary.newFile("installed.dll").apply { writeBytes(bytes.copyOf().also { it[100] = 0 }) }
        assertFalse(FexRuntimeFiles.matches(target, bytes.size.toLong(), hash(bytes)))
        FexRuntimeFiles.copyVerifiedFile(source, target, bytes.size.toLong(), hash(bytes))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun invalidPackageCopyNeverReplacesAnExistingInstalledFile() {
        val expected = byteArrayOf(1, 0x1a, 3, 4)
        val source = temporary.newFile("source.dll").apply { writeBytes(expected.copyOf(1)) }
        val target = temporary.newFile("installed.dll").apply { writeText("keep existing runtime") }
        assertThrows(java.io.IOException::class.java) {
            FexRuntimeFiles.copyVerifiedFile(source, target, expected.size.toLong(), hash(expected))
        }
        assertEquals("keep existing runtime", target.readText())
        assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".part") })
    }
}
