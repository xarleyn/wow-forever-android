package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BattleNetLauncherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() { context.getSharedPreferences("wow_launcher", 0).edit().clear().commit() }

    @Test fun existingInstallsKeepDirectLaunchUntilExplicitlySwitched() {
        assertEquals(WoWLaunchMode.DIRECT, BattleNetLauncher.loadMode(context))
        BattleNetLauncher.saveMode(context, WoWLaunchMode.BATTLE_NET)
        assertEquals(WoWLaunchMode.BATTLE_NET, BattleNetLauncher.loadMode(context))
        BattleNetLauncher.saveMode(context, WoWLaunchMode.DIRECT)
        assertEquals(WoWLaunchMode.DIRECT, BattleNetLauncher.loadMode(context))
    }

    @Test fun missingFilesIncludingOverlayNullsDoNotPretendLauncherIsInstalled() {
        assertNull(BattleNetLauncher.installedTarget { null })
        assertNull(BattleNetLauncher.installedTarget { File(temporary.root, it) })
    }

    @Test fun windowsPathsReachWineWithoutLiteralQuotesIncludingSpaces() {
        for (path in listOf("B:\\Battle.net-Setup.exe", "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe")) {
            val args = com.winlator.core.ProcessHelper.splitCommand("wine explorer /desktop=shell,1280x720 " + BattleNetLauncher.commandArgument(path))
            assertEquals(path, args.last())
            assertEquals(4, args.size)
        }
    }

    @Test fun gameFilesAreDisconnectedUntilExplicitlyEnabled() {
        assertFalse(BattleNetLauncher.useGameFiles(context))
        BattleNetLauncher.useGameFiles(context, true)
        assertTrue(BattleNetLauncher.useGameFiles(context))
    }

    @Test fun nonemptyFexFilesWithoutPinnedIntegrityAreIncomplete() {
        val directory = temporary.newFolder()
        File(directory, "profile.json").writeText("fixture")
        assertFalse(BattleNetLauncher.emulatorFilesPresent(directory))
        for (path in listOf("system32/libwow64fex.dll", "system32/libarm64ecfex.dll",
            "aarch64-unix/libwow64fex.so", "aarch64-unix/libarm64ecfex.so")) {
            File(directory, path).apply { parentFile!!.mkdirs(); writeText("fixture") }
        }
        assertFalse(BattleNetLauncher.emulatorFilesPresent(directory))
    }

    @Test fun installedLauncherIsFoundInEitherDefaultProgramFilesDirectory() {
        val root = temporary.newFolder()
        File(root, "Program Files/Battle.net/Battle.net Launcher.exe").apply {
            parentFile!!.mkdirs(); writeText("fixture")
        }
        val target = BattleNetLauncher.installedTarget { File(root, it) }
        assertEquals("C:\\Program Files\\Battle.net\\Battle.net Launcher.exe", target?.executable)
        assertEquals(false, target?.installer)
    }

    @Test fun cachedInstallerDoesNotDownloadOrRequireSavedCredentials() {
        val tools = BattleNetLauncher.toolsDirectory(context).apply { mkdirs() }
        File(tools, BattleNetLauncher.INSTALLER_NAME).writeBytes(ByteArray(70_000).apply {
            this[0] = 'M'.code.toByte(); this[1] = 'Z'.code.toByte()
        })
        val target = BattleNetLauncher.prepare(context, null) { }
        assertEquals("B:\\Battle.net-Setup.exe", target.executable)
        assertTrue(target.installer)
    }
}
