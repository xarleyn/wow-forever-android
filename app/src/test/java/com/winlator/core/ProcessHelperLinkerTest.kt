package com.winlator.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessHelperLinkerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun elf(machine: Int): File = temporary.newFile("tool $machine").apply {
        writeBytes(ByteArray(20).apply {
            this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2; this[5] = 1; this[18] = machine.toByte()
        })
    }

    @Test fun arm64ExecutableUsesAvailableBridgeWithQuotedOrEscapedPath() {
        val bridge = temporary.newFile("linker64")
        val executable = elf(183)
        assertEquals(bridge.path, ProcessHelper.modernLinker("\"${executable.path}\" --version", bridge))
        assertEquals(bridge.path, ProcessHelper.modernLinker(executable.path.replace(" ", "\\ ") + " --version", bridge))
    }

    @Test fun nativeX86AndMissingBridgeRetainSystemLinker() {
        val bridge = temporary.newFile("linker64")
        assertEquals("/system/bin/linker64", ProcessHelper.modernLinker("\"${elf(62).path}\"", bridge))
        assertEquals("/system/bin/linker64", ProcessHelper.modernLinker("\"${elf(183).path}\"", File(temporary.root, "missing")))
    }
}
