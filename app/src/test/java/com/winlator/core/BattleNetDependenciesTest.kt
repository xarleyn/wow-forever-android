package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BattleNetDependenciesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun pe(machine: Int = 0x14c, imports: List<String> = emptyList(), delay: List<String> = emptyList()): ByteArray {
        val bytes = ByteArray(4096)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun w(at: Int, value: Int) { b.putShort(at, value.toShort()) }
        fun d(at: Int, value: Int) { b.putInt(at, value) }
        w(0, 0x5a4d); d(60, 128); d(128, 0x4550); w(132, machine); w(134, 1)
        val wide = machine != 0x14c
        w(148, if (wide) 240 else 224); w(152, if (wide) 0x20b else 0x10b)
        val directory = 152 + if (wide) 112 else 96
        d(directory - 4, 16)
        val section = 152 + if (wide) 240 else 224
        d(section + 12, 0x1000); d(section + 16, 3584); d(section + 20, 512)
        var stringAt = 2200
        fun table(names: List<String>, at: Int, stride: Int, directoryIndex: Int) {
            if (names.isEmpty()) return
            d(directory + directoryIndex * 8, at - 512 + 0x1000)
            for ((i, name) in names.withIndex()) {
                if (stride == 32) d(at + i * stride, 1)
                d(at + i * stride + if (stride == 32) 4 else 12, stringAt - 512 + 0x1000)
                name.toByteArray(Charsets.US_ASCII).copyInto(bytes, stringAt)
                stringAt += name.length + 1
            }
        }
        table(imports, 512, 20, 1); table(delay, 1300, 32, 13)
        return bytes
    }

    private fun file(root: File, path: String, bytes: ByteArray): File = File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun readsBothPeArchitecturesAndDelayImportsAndRejectsTruncation() {
        for (machine in listOf(0x14c, 0x8664, 0xaa64, 0xa641)) {
            val source = file(temp.root, "$machine.dll", pe(machine, listOf("COMCTL32.dll"), listOf("NDFAPI.DLL")))
            val image = PeDependencies.read(source)
            assertEquals(machine, image.machine)
            assertEquals(listOf("COMCTL32.dll"), image.imports)
            assertEquals(listOf("NDFAPI.DLL"), image.delayImports)
            source.writeBytes(source.readBytes().copyOf(700))
            assertThrows(java.io.IOException::class.java) { PeDependencies.read(source) }
        }
    }

    @Test fun stagesWholeTransitiveClosureRepairsDamagedUpperAndReportsOptionalGap() {
        val upper = temp.newFolder("upper"); val lower = temp.newFolder("lower"); val wine = temp.newFolder("wine")
        val app = temp.newFolder("app")
        file(app, "Battle.net.exe", pe(imports = listOf("libcef.dll", "api-ms-win-core-test-l1-1-0.dll")))
        file(app, "libcef.dll", pe(imports = listOf("COMCTL32.dll"), delay = listOf("ndfapi.dll")))
        val comctl = pe(imports = listOf("USER32.dll"))
        file(lower, "drive_c/windows/syswow64/comctl32.dll", comctl)
        file(lower, "drive_c/windows/syswow64/user32.dll", pe())
        file(upper, "drive_c/windows/syswow64/comctl32.dll", comctl.copyOf(337))
        val report = BattleNetDependencies.prepare(upper, lower, wine, listOf(app))
        assertArrayEquals(comctl, File(upper, "drive_c/windows/syswow64/comctl32.dll").readBytes())
        assertTrue(File(upper, "drive_c/windows/syswow64/user32.dll").isFile)
        assertTrue(report.contains("DELAY libcef.dll -> ndfapi.dll MISSING"))
        assertTrue(report.contains("applicationPE=2 systemPE=2 staged=2 unresolvedEdges=1"))
        val again = BattleNetDependencies.prepare(upper, lower, wine, listOf(app))
        assertTrue(again.contains("staged=0"))
        assertFalse(app.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun rejectsWrongArchitectureAndPreservesValidNativeOverridesAndNonPeFiles() {
        val upper = temp.newFolder("upper"); val lower = temp.newFolder("lower"); val wine = temp.newFolder("wine")
        val app = temp.newFolder("app")
        file(app, "Battle.net.exe", pe(imports = listOf("COMCTL32.dll", "USER32.dll")))
        val native = pe().also { it[4000] = 77 }
        file(upper, "drive_c/windows/syswow64/comctl32.dll", native)
        file(lower, "drive_c/windows/syswow64/comctl32.dll", pe())
        file(lower, "drive_c/windows/syswow64/user32.dll", pe(0xaa64))
        val state = file(app, "session.json", "keep session".toByteArray())
        val report = BattleNetDependencies.prepare(upper, lower, wine, listOf(app))
        assertArrayEquals(native, File(upper, "drive_c/windows/syswow64/comctl32.dll").readBytes())
        assertFalse(File(upper, "drive_c/windows/syswow64/user32.dll").exists())
        assertTrue(report.contains("INVALID_PE_OR_ARCH"))
        assertEquals("keep session", state.readText())
    }

    @Test fun recoversSystemDllFromSelectedWineWhenBasePrefixCopyIsBad() {
        val upper = temp.newFolder("upper"); val lower = temp.newFolder("lower"); val wine = temp.newFolder("wine")
        val app = temp.newFolder("app")
        file(app, "Battle.net.exe", pe(imports = listOf("comctl32.dll")))
        file(lower, "drive_c/windows/syswow64/comctl32.dll", byteArrayOf(77, 90))
        file(wine, "lib/wine/i386-windows/comctl32.dll", pe())
        val report = BattleNetDependencies.prepare(upper, lower, wine, listOf(app))
        assertTrue(report.contains("systemPE=1 staged=1 unresolvedEdges=0"))
    }

    @Test fun stagesManifestRedirectedCommonControlsWithoutReplacingExistingNativeCopy() {
        val upper = temp.newFolder("upper"); val lower = temp.newFolder("lower")
        val assembly = "x86_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef"
        val dll = "drive_c/windows/winsxs/$assembly/comctl32.dll"
        val manifest = "drive_c/windows/winsxs/manifests/$assembly.manifest"
        val native = pe().also { it[4000] = 99 }
        file(lower, dll, pe()); file(lower, manifest, "<assembly/>".toByteArray())
        val report = StringBuilder()
        BattleNetDependencies.prepareCommonControls(upper, lower, report)
        assertTrue(File(upper, dll).isFile)
        assertTrue(File(File(upper, dll).parentFile, "COMCTL32.dll").isFile)
        assertEquals("<assembly/>", File(upper, manifest).readText())
        file(upper, dll, native)
        BattleNetDependencies.prepareCommonControls(upper, lower, StringBuilder())
        assertArrayEquals(native, File(upper, dll).readBytes())
        assertTrue(report.contains("SXS common-controls $assembly"))
    }

    @Test fun refreshesManagedAliasesAfterAtomicCanonicalReplacement() {
        val upper = temp.newFolder("upper"); val lower = temp.newFolder("lower")
        val path = "drive_c/windows/syswow64/comctl32.dll"
        val canonical = file(upper, path, pe())
        BattleNetDependencies.prepareCommonControls(upper, lower, StringBuilder())
        val replacement = pe().also { it[4000] = 42 }
        val staged = file(upper, "replacement.dll", replacement)
        java.nio.file.Files.move(staged.toPath(), canonical.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        BattleNetDependencies.prepareCommonControls(upper, lower, StringBuilder())
        assertArrayEquals(replacement, File(canonical.parentFile, "COMCTL32.dll").readBytes())
        assertArrayEquals(replacement, canonical.readBytes())
    }
}
