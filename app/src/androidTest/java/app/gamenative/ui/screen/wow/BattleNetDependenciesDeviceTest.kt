package app.gamenative.ui.screen.wow

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.core.BattleNetDependencies
import com.winlator.core.PeDependencies
import com.winlator.core.ProcessOutputCapture
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests real shipped PE modules in a disposable prefix; never executes PC binaries. */
@RunWith(AndroidJUnit4::class)
class BattleNetDependenciesDeviceTest {
    @Test fun probeCaptureBoundsActualAndroidProcessOutputAndTimeout() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val verbose = ProcessBuilder("/system/bin/sh", "-c", "head -c 524288 /dev/zero; printf TAIL_MARKER")
            .redirectErrorStream(true).start()
        val tail = ProcessOutputCapture.capture(verbose, 5, 4096)
        assertTrue(tail.contains("exit=0"))
        assertTrue(tail.contains("truncated"))
        assertTrue(tail.endsWith("TAIL_MARKER"))
        assertTrue(tail.length < 4200)
        val hanging = ProcessBuilder("/system/bin/sh", "-c", "printf STARTED; sleep 10")
            .redirectErrorStream(true).start()
        val started = android.os.SystemClock.elapsedRealtime()
        val timed = ProcessOutputCapture.capture(hanging, 1, 4096)
        assertTrue(timed, timed.contains("timeout=1s"))
        assertTrue(timed.contains("STARTED"))
        assertTrue("Probe timeout must not wait for inherited pipes", android.os.SystemClock.elapsedRealtime() - started < 3000)
    }

    @Test fun preparationStagesTheRealWin32ProbeWithoutDownloadingAnInstaller() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = BattleNetLauncher.toolsDirectory(context)
        assumeTrue("Cached official installer required", File(directory, BattleNetLauncher.INSTALLER_NAME).length() > 65536)
        val probe = File(directory, "BattleNetProbe.exe").apply { writeBytes(byteArrayOf(77, 90)) }
        assertTrue(BattleNetLauncher.prepare(context, null) { }.installer)
        assertArrayEquals(context.assets.open("battlenet/BattleNetProbe.exe").use { it.readBytes() }, probe.readBytes())
        val image = PeDependencies.read(probe)
        assertEquals(0x14c, image.machine)
        assertEquals(listOf("KERNEL32.dll"), image.imports)
    }

    @Test fun inventoriesRealClientAndRepairsComctlAndItsTransitiveDependencies() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = File(context.getExternalFilesDir(null), "dependency-fixture")
        assertTrue("Stage official PC PE files on emulator-5560 first", apps.isDirectory)
        val wine = File(ImageFs.getSharedProtonDir(context), "proton-11.0-90624-arm64ec")
        val lower = File(wine, "base_prefix/.wine")
        assertTrue(lower.isDirectory)
        val upper = File.createTempFile("bnet-deps-", "", context.cacheDir).apply { delete(); mkdirs() }
        try {
            val broken = File(upper, "drive_c/windows/syswow64/comctl32.dll").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(77, 90)) }
            val report = BattleNetDependencies.prepare(upper, lower, wine, listOf(apps))
            File(context.getExternalFilesDir(null), "dependency-fixture-report.txt").writeText(report)
            assertEquals(0x14c, PeDependencies.read(broken).machine)
            assertEquals(1298432L, broken.length())
            assertArrayEquals(File(lower, "drive_c/windows/syswow64/comctl32.dll").readBytes(), broken.readBytes())
            assertTrue(File(upper, "drive_c/windows/syswow64/user32.dll").isFile)
            assertTrue(File(upper, "drive_c/windows/syswow64/COMCTL32.dll").isFile)
            val sxs = "drive_c/windows/winsxs/x86_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef/"
            assertEquals(3522560L, File(upper, sxs + "comctl32.dll").length())
            assertArrayEquals(File(upper, sxs + "comctl32.dll").readBytes(), File(upper, sxs + "COMCTL32.dll").readBytes())
            assertFalse(report.contains("INVALID_APP_PE"))
            assertFalse(report.contains("UNSUPPORTED_APP_ARCH"))
            val unresolved = report.lineSequence().filter { "MISSING" in it || "INVALID_PE_OR_ARCH" in it }.toList()
            // The stock package lacks only Chromium's optional network-diagnostics DLL.
            assertTrue(unresolved.joinToString("\n"), unresolved.all { it.startsWith("DELAY ") && "ndfapi.dll" in it.lowercase() })
            assertTrue(report.contains("SUMMARY applicationPE=117 "))
            val second = BattleNetDependencies.prepare(upper, lower, wine, listOf(apps))
            assertTrue(second.contains(" staged=0 "))

            // Android is case-sensitive: a Windows host test cannot exercise two distinct names.
            val uppercase = File(broken.parentFile, "COMCTL32.dll")
            assertFalse(java.nio.file.Files.isSameFile(broken.toPath(), File(lower, "drive_c/windows/syswow64/comctl32.dll").toPath()))
            val replacement = broken.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            val staged = File(upper, "replacement.dll").apply { writeBytes(replacement) }
            java.nio.file.Files.move(staged.toPath(), broken.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFalse(uppercase.readBytes().contentEquals(replacement))
            BattleNetDependencies.prepare(upper, lower, wine, listOf(apps))
            assertArrayEquals(replacement, uppercase.readBytes())

            // An unrelated valid uppercase native override has no receipt and must survive.
            assertTrue(File(broken.parentFile, ".wow-forever-comctl-alias.sha256").delete())
            staged.writeBytes(File(lower, "drive_c/windows/syswow64/comctl32.dll").readBytes())
            java.nio.file.Files.move(staged.toPath(), uppercase.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val conflict = BattleNetDependencies.prepare(upper, lower, wine, listOf(apps))
            assertTrue(conflict.contains("CONFLICT preserved existing native"))
            assertArrayEquals(replacement, broken.readBytes())
            assertArrayEquals(File(lower, "drive_c/windows/syswow64/comctl32.dll").readBytes(), uppercase.readBytes())
        } finally { upper.deleteRecursively() }
    }
}
