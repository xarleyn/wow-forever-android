package app.gamenative.ui.screen.wow

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FexRuntimeFiles
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deliberately corrupts only the disposable emulator's FEX package; never run against a phone. */
@RunWith(AndroidJUnit4::class)
class FexRuntimeRepairTest {
    @Test fun repairsCachedPackageAndInstalledControlZPrefixesWithoutDownloadingOrResettingPrefix() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Stage the pinned archive in the emulator first",
            File(BattleNetLauncher.toolsDirectory(context), "FEXCore-2609.wcp").isFile)
        val packageDir = File(ContentsManager.getContentTypeDir(context,
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE), BattleNetLauncher.FEX_VERSION)
        BattleNetLauncher.ensureEmulator(context) { }
        assertTrue(FexRuntimeFiles.isPackageValid(packageDir))
        val fixture = File.createTempFile("fex-repair-test-", "", context.cacheDir).apply { delete(); mkdirs() }
        val prefix = File(fixture, "system32").apply { mkdirs() }
        val unix = File(fixture, "aarch64-unix").apply { mkdirs() }
        val entries = listOf("system32/libwow64fex.dll" to 337, "system32/libarm64ecfex.dll" to 474,
            "aarch64-unix/libwow64fex.so" to 1740, "aarch64-unix/libarm64ecfex.so" to 1740)
        try {
            for ((relative, offset) in entries) {
                val source = File(packageDir, relative)
                val head = source.inputStream().use { input -> ByteArray(offset).also { assertEquals(offset, input.read(it)) } }
                source.writeBytes(head)
                File(if (relative.startsWith("system32/")) prefix else unix, source.name).writeBytes(head)
            }
            assertFalse(BattleNetLauncher.emulatorFilesPresent(packageDir))
            BattleNetLauncher.ensureEmulator(context) { }
            assertTrue(BattleNetLauncher.emulatorFilesPresent(packageDir))
            FexRuntimeFiles.install(packageDir, prefix, unix)
            for ((relative, _) in entries) {
                val source = File(packageDir, relative)
                val installed = File(if (relative.startsWith("system32/")) prefix else unix, source.name)
                assertArrayEquals(source.readBytes(), installed.readBytes())
            }
            assertEquals(5308416L, File(prefix, "libwow64fex.dll").length())
            assertEquals(5910528L, File(prefix, "libarm64ecfex.dll").length())
            assertEquals(113184L, File(unix, "libwow64fex.so").length())
        } finally {
            BattleNetLauncher.ensureEmulator(context) { }
            fixture.deleteRecursively()
        }
    }
}
